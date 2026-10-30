package com.autostock.market;

import com.autostock.common.event.ListingDayPrices;
import com.autostock.common.event.ListingOpenPrices;
import com.autostock.common.event.RecentListings;
import com.autostock.common.util.MarketConstants;
import com.autostock.common.util.SecretMasking;
import com.autostock.common.util.StockNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 신규 상장 확인 — 거래일 아침 코스피·코스닥 상장 종목 목록에서 상장일이 최근인 종목을 추려 {@link RecentListings}를 발행한다
 * (2026-10-13, 사용자 "공모주 기능 자동화 강화"). ipo 모듈이 회사명으로 공모주 딜과 맞춰 상장일·종목코드를 남기고 상장일 알림을 보낸다.
 *
 * <ul>
 *   <li><b>언제</b>: 거래일 08:05와 08:40 — 그날 상장 종목이 08:05 종목 마스터에 아직 없을 수 있어 장전 동시호가(08:30~) 뒤에 한 번
 *       더 본다. 08:40 이후에 성공하면 그날은 끝이고(시장마다 1회 호출 × 2시장), 실패했으면 09:05~15:05 매시 5분에 다시 한다.
 *       앱이 08:00~20:00 사이에 켜지면 기동 직후 한 번 한다. 상장일 알림은 ipo 모듈이 이 이벤트를 받자마자 보낸다(장 시작 전).</li>
 *   <li><b>범위</b>: 상장일이 오늘 기준 앞뒤 {@value #WINDOW_DAYS}일 안인 종목만 — 공모주는 청약 마감 뒤 1~2주 안에 상장한다.</li>
 *   <li><b>모의투자 서버</b>: 이 조회를 지원하지 않으면(키움 {@code 8104}) 그날은 그만두고 경고를 남긴다 — 상장일은 화면에서 직접 넣는다.</li>
 *   <li><b>설정</b>: {@code autostock.listing-watch.enabled}(기본 꺼짐 — 시작 스크립트가 켠다. CI·테스트에서는 키움을 부르지 않는다).</li>
 * </ul>
 * 브로커 응답 번역(ka10099, 연속 조회)은 {@link MarketDataPort} 구현체가 맡는다.
 *
 * <p><b>상장일 시세</b>(2026-10-15, 공모주 청약 추천 — aiDoc/ipo-subscription-strategy.md): 거래일 15:40에 상장일이 최근
 * {@value #PRICE_DAYS}일 안인 코스피·코스닥 종목(스팩·ETF·ETN 제외)의 상장일 시가·종가를 키움 일봉으로 확인해
 * {@link ListingDayPrices}를 발행한다. ipo 모듈이 공모주 딜에 남기고, 다음 공모주의 "시장 분위기"(직전 상장 5곳의 상장일 종가 수익률)를
 * 센다. 15:40~20:00에 켜지면 기동 직후에도 한 번 한다. 실패한 종목은 다음 거래일에 다시 본다(범위 안이면).
 */
@Component
public class NewListingWatcher {

    private static final Logger log = LoggerFactory.getLogger(NewListingWatcher.class);

    /** 상장일 확인 범위(오늘 앞뒤 일수). */
    static final int WINDOW_DAYS = 14;
    /** 이 시각 뒤에 성공하면 그날 확인을 마친다 — 장전 동시호가(08:30) 뒤라 그날 상장 종목이 종목 마스터에 들어와 있다. */
    static final LocalTime SETTLED_FROM = LocalTime.of(8, 40);
    /** 기동 직후 따라잡기를 하는 시간대 — 종목 마스터가 그날 것으로 바뀐 뒤부터 저녁까지. */
    static final LocalTime CATCH_UP_FROM = LocalTime.of(8, 0);
    static final LocalTime CATCH_UP_UNTIL = LocalTime.of(20, 0);
    /** 상장일 시세를 확인하는 범위(오늘 앞 일수) — 놓친 날(앱 꺼짐)을 다음 거래일에 메운다. */
    static final int PRICE_DAYS = 10;
    /** 상장일 시세 확인 시각 — 정규장 마감(15:30) 뒤. */
    static final LocalTime PRICE_FROM = LocalTime.of(15, 40);
    /** 공모주가 아닌 신규 상장(시장명·이름) — 상장일 시세를 보지 않는다. */
    private static final java.util.regex.Pattern NOT_IPO = java.util.regex.Pattern.compile("스팩|기업인수목적|ETF|ETN|리츠");
    /** 로그에 이름을 다 적는 최근 상장 종목 수 상한. */
    private static final int LOG_NAMES = 10;

    private final MarketDataPort marketData;
    private final MarketCalendarService calendar;
    private final ApplicationEventPublisher publisher;
    private final boolean enabled;
    private final Clock clock;

    private final AtomicBoolean running = new AtomicBoolean();
    /** 그날 확인을 마친 날(KST) — {@link #SETTLED_FROM} 뒤에 성공했거나, 모의투자 서버가 지원하지 않아 그만둔 날. */
    private volatile LocalDate doneDay;
    /** 마지막으로 확인한 최근 상장 종목(앞뒤 {@value #WINDOW_DAYS}일) — 상장일 시세 확인 대상. */
    private volatile List<RecentListings.Listing> lastRecent = List.of();
    /** 상장일 시세 확인을 마친 날. */
    private volatile LocalDate pricesDoneDay;

    public NewListingWatcher(MarketDataPort marketData, MarketCalendarService calendar, ApplicationEventPublisher publisher,
                             @Value("${autostock.listing-watch.enabled:false}") boolean enabled, Clock clock) {
        this.marketData = marketData;
        this.calendar = calendar;
        this.publisher = publisher;
        this.enabled = enabled;
        this.clock = clock;
    }

    /** 거래일 08:05·08:40 — 장 시작 전에 그날 상장 종목을 잡는다. */
    @Scheduled(cron = "0 5,40 8 * * MON-FRI", zone = "Asia/Seoul")
    public void watchScheduled() {
        watch("정기");
    }

    /** 거래일 09:05~15:05 매시 5분 — 아침 확인이 실패했을 때만 다시 한다(마친 날은 아무것도 하지 않는다). */
    @Scheduled(cron = "0 5 9-15 * * MON-FRI", zone = "Asia/Seoul")
    public void retryHourly() {
        watch("재시도");
    }

    /** 기동 직후 — 거래일 08:00~20:00에 켜졌으면 가상 스레드에서 한 번(기동을 막지 않는다). */
    @EventListener(ApplicationReadyEvent.class)
    public void catchUpOnStartup() {
        if (!enabled) {
            return;
        }
        LocalTime time = ZonedDateTime.now(clock.withZone(MarketConstants.KST)).toLocalTime();
        if (time.isBefore(CATCH_UP_FROM) || time.isAfter(CATCH_UP_UNTIL)) {
            return;
        }
        Thread.ofVirtual().name("new-listing-catch-up").start(() -> {
            watch("기동");
            if (!time.isBefore(PRICE_FROM)) {
                recordListingDayPrices("기동");
            }
        });
    }

    /** 상장일 시초가 확인에서 빼는 종목(시장명·이름) — 스팩은 넣는다(2026-10-30 사용자 "스팩도 청약"). */
    private static final java.util.regex.Pattern NOT_OFFERING = java.util.regex.Pattern.compile("ETF|ETN|리츠");
    /** 상장일 시초가 확인을 마친 날. */
    private volatile LocalDate openDoneDay;

    /**
     * 거래일 09:05 — 오늘 상장한 종목의 시초가를 키움 현재가(시가)로 확인해 {@link ListingOpenPrices}를 발행한다(2026-10-30). ipo 모듈이
     * 관심 공모주 딜에 "시초가·공모가 대비 수익률" 알림을 보낸다. 09:05 재시도(신규 상장 확인)와 같은 시각이라 목록이 없으면 먼저 받는다.
     */
    @Scheduled(cron = "0 5 9 * * MON-FRI", zone = "Asia/Seoul")
    public void listingOpenScheduled() {
        recordListingOpenPrices("정기");
    }

    /**
     * 오늘 상장 종목의 시초가를 확인해 발행한다. 시가가 아직 없으면(0·빈 값) 그 종목은 뺀다. 조회가 실패한 종목이 있으면 그날 확인을
     * 마친 것으로 치지 않는다(같은 날 다시 부르면 다시 본다 — ipo 쪽 알림 기록이 중복을 막는다).
     *
     * @return 발행한 종목 수(발행하지 않았으면 -1)
     */
    int recordListingOpenPrices(String reason) {
        if (!enabled) {
            return -1;
        }
        LocalDate today = LocalDate.now(clock.withZone(MarketConstants.KST));
        if (today.equals(openDoneDay) || !calendar.isTradingDay(today)) {
            return -1;
        }
        if (lastRecent.stream().noneMatch(l -> today.equals(l.listedOn()))) {
            watch(reason + "·시초가 전");
        }
        List<RecentListings.Listing> targets = lastRecent.stream()
                .filter(l -> today.equals(l.listedOn()))
                .filter(l -> !NOT_OFFERING.matcher(String.valueOf(l.name()) + " " + l.marketName()).find())
                .toList();
        List<ListingOpenPrices.Entry> entries = new ArrayList<>();
        int failed = 0;
        for (RecentListings.Listing listing : targets) {
            try {
                MarketDataPort.StockQuote quote = marketData.stockQuote(listing.stockCode());
                java.math.BigDecimal open = quote == null ? null : abs(quote.open());
                if (open != null && open.signum() > 0) {
                    entries.add(new ListingOpenPrices.Entry(listing.stockCode(), listing.name(), open, abs(quote.current())));
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("상장일 시초가 확인 실패({}): {}", StockNames.label(listing.stockCode(), listing.name()),
                        SecretMasking.mask(e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }
        if (failed == 0) {
            openDoneDay = today;
        }
        log.info("상장일 시초가 확인({}) — 오늘 상장 {}종목 중 {}종목{}", reason, targets.size(), entries.size(),
                entries.isEmpty() ? "" : ": " + entries.stream().map(e -> "%s(%s) 시가 %s".formatted(e.name(), e.stockCode(),
                        e.open().toPlainString())).collect(Collectors.joining(", ")));
        if (!entries.isEmpty()) {
            publisher.publishEvent(new ListingOpenPrices(today, entries));
        }
        return entries.size();
    }

    /** 거래일 15:40 — 최근 상장 종목의 상장일 시세(클래스 설명). */
    @Scheduled(cron = "0 40 15 * * MON-FRI", zone = "Asia/Seoul")
    public void listingDayPricesScheduled() {
        recordListingDayPrices("정기");
    }

    /**
     * 최근 상장 종목의 상장일 시가·종가를 일봉으로 확인해 발행한다. 아침 확인 목록이 없으면 먼저 목록을 받는다.
     *
     * @return 발행한 종목 수(발행하지 않았으면 -1)
     */
    int recordListingDayPrices(String reason) {
        if (!enabled) {
            return -1;
        }
        LocalDate today = LocalDate.now(clock.withZone(MarketConstants.KST));
        if (today.equals(pricesDoneDay) || !calendar.isTradingDay(today)) {
            return -1;
        }
        if (lastRecent.isEmpty()) {
            watch(reason + "·시세 전");
        }
        LocalDate from = today.minusDays(PRICE_DAYS);
        List<RecentListings.Listing> targets = lastRecent.stream()
                .filter(l -> l.listedOn() != null && !l.listedOn().isBefore(from) && !l.listedOn().isAfter(today))
                .filter(l -> !NOT_IPO.matcher(String.valueOf(l.name()) + " " + l.marketName()).find())
                .toList();
        List<ListingDayPrices.Entry> entries = new ArrayList<>();
        int failed = 0;
        for (RecentListings.Listing listing : targets) {
            try {
                marketData.dailyCandles(listing.stockCode(), today).stream()
                        .filter(c -> listing.listedOn().equals(c.date()))
                        .findFirst()
                        .ifPresent(c -> entries.add(new ListingDayPrices.Entry(listing.stockCode(), listing.name(),
                                listing.listedOn(), abs(c.open()), abs(c.high()), abs(c.low()), abs(c.close()))));
            } catch (RuntimeException e) {
                failed++;
                log.warn("상장일 시세 확인 실패({}) — 다음 거래일에 다시: {}", StockNames.label(listing.stockCode(), listing.name()),
                        SecretMasking.mask(e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }
        if (failed == 0) {
            pricesDoneDay = today;
        }
        log.info("상장일 시세 확인({}) — 최근 {}일 상장 공모주 {}종목 중 {}종목{}", reason, PRICE_DAYS, targets.size(), entries.size(),
                entries.isEmpty() ? "" : ": " + entries.stream().map(e -> "%s(%s) %s 시가 %s 종가 %s".formatted(e.name(),
                        e.stockCode(), e.listedOn(), e.open().toPlainString(), e.close().toPlainString()))
                        .collect(Collectors.joining(", ")));
        if (!entries.isEmpty()) {
            publisher.publishEvent(new ListingDayPrices(today, entries));
        }
        return entries.size();
    }

    private static java.math.BigDecimal abs(java.math.BigDecimal v) {
        return v == null ? null : v.abs();   // 키움 가격은 등락 부호가 붙어 올 수 있다(+27400·-15780)
    }

    /**
     * 한 번 확인한다. 꺼져 있거나, 휴장일이거나, 그날 이미 마쳤거나, 다른 확인이 도는 중이면 아무것도 하지 않는다.
     *
     * @return 발행한 최근 상장 종목 수(발행하지 않았으면 -1)
     */
    int watch(String reason) {
        if (!enabled) {
            return -1;
        }
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(MarketConstants.KST));
        LocalDate today = now.toLocalDate();
        if (today.equals(doneDay) || !calendar.isTradingDay(today)) {
            return -1;
        }
        if (!running.compareAndSet(false, true)) {
            return -1;
        }
        try {
            List<MarketDataPort.ListedStock> all = new ArrayList<>();
            int kospi = fetch(MarketDataPort.ListingMarket.KOSPI, all);
            int kosdaq = fetch(MarketDataPort.ListingMarket.KOSDAQ, all);
            LocalDate from = today.minusDays(WINDOW_DAYS);
            LocalDate until = today.plusDays(WINDOW_DAYS);
            List<RecentListings.Listing> recent = all.stream()
                    .filter(s -> s.listedOn() != null && !s.listedOn().isBefore(from) && !s.listedOn().isAfter(until))
                    .sorted(Comparator.comparing(MarketDataPort.ListedStock::listedOn).reversed())
                    .map(s -> new RecentListings.Listing(s.code(), s.name(), s.listedOn(), s.marketName()))
                    .toList();
            if (!now.toLocalTime().isBefore(SETTLED_FROM)) {
                doneDay = today;   // 08:05 성공은 08:40에 한 번 더 본다 — 그날 상장 종목이 늦게 들어올 수 있다
            }
            lastRecent = recent;
            log.info("신규 상장 확인({}) — 코스피 {}·코스닥 {}종목 중 상장일 {}~{} {}종목{}", reason, kospi, kosdaq, from, until,
                    recent.size(), recent.isEmpty() ? "" : ": " + names(recent));
            if (!recent.isEmpty()) {
                publisher.publishEvent(new RecentListings(today, recent));
            }
            return recent.size();
        } catch (RuntimeException e) {
            String reasonText = SecretMasking.mask(e.getClass().getSimpleName() + ": " + e.getMessage());
            if (String.valueOf(e.getMessage()).contains("8104")) {
                doneDay = today;
                log.warn("신규 상장 확인 불가 — 이 키움 서버(모의투자)가 종목정보 리스트를 지원하지 않음. 공모주 상장일은 화면에서 직접 넣는다: {}",
                        reasonText);
            } else {
                log.warn("신규 상장 확인 실패({}) — 다음 시각(08:40, 그 뒤 매시 5분)에 다시: {}", reason, reasonText);
            }
            return -1;
        } finally {
            running.set(false);
        }
    }

    private int fetch(MarketDataPort.ListingMarket market, List<MarketDataPort.ListedStock> into) {
        List<MarketDataPort.ListedStock> stocks = marketData.listedStocks(market);
        into.addAll(stocks);
        return stocks.size();
    }

    private static String names(List<RecentListings.Listing> recent) {
        String shown = recent.stream().limit(LOG_NAMES)
                .map(l -> "%s(%s, %s %s)".formatted(l.name(), l.stockCode(), l.listedOn(), l.marketName()))
                .collect(Collectors.joining(", "));
        return recent.size() > LOG_NAMES ? shown + " 외 " + (recent.size() - LOG_NAMES) + "종목" : shown;
    }
}
