package com.autostock.strategy;

import com.autostock.common.event.Candle;
import com.autostock.common.util.MarketConstants;
import com.autostock.common.util.StockNames;
import com.autostock.market.KiwoomDailyChartService;
import com.autostock.market.KiwoomWebSocketClient;
import com.autostock.market.MarketCalendarService;
import com.autostock.market.MarketDataPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 다전략(단타·종가→익일·차트 기법)이 함께 쓰는 시세 창구(2026-10-16, aiDoc/multi-strategy-paper-2026-10-16.md).
 *
 * <ul>
 *   <li><b>완료된 일봉</b>: 오늘 날짜 봉을 뺀 일봉(어제 종가까지) — 하루 한 번 받아 둔다. 평일 08:45에 등록된 전 종목을 미리 받는다.</li>
 *   <li><b>오늘 시가</b>: ka10001 시가 — 평일 09:00:05부터 등록된 종목을 차례로 받는다. 키움 TR은 종류마다 초당 1건이라
 *       25종목이면 약 25초 걸린다. 받기 전에는 null(전략은 그 종목 판단을 미룬다).</li>
 *   <li><b>실시간 체결(0B)</b>: 등록한 종목을 WS로 구독한다 — 전략은 {@code MarketTick} 이벤트를 받는다.</li>
 * </ul>
 * 조회는 모두 순서대로 한 스레드에서 한다(스케줄 스레드) — 같은 TR을 여러 스레드가 동시에 부르면 유량 대기(10초)를 넘겨 실패한다.
 */
@Component
public class LiveMarket {

    private static final Logger log = LoggerFactory.getLogger(LiveMarket.class);
    /** 차트 기법 SMA200·볼린저 120일 등 — 한 페이지(600봉)면 충분하다(10/16 실측 600행). */
    static final int BAR_COUNT = 400;

    private final KiwoomDailyChartService chartService;
    private final MarketDataPort marketData;
    private final ObjectProvider<KiwoomWebSocketClient> wsClient;
    private final MarketCalendarService calendar;
    private final Clock clock;

    private final Map<String, Set<String>> owners = new ConcurrentHashMap<>();
    private final Map<String, Bars> bars = new ConcurrentHashMap<>();
    private final Map<String, Open> opens = new ConcurrentHashMap<>();
    private final Set<String> subscribed = ConcurrentHashMap.newKeySet();

    private record Bars(LocalDate day, List<Candle> candles) {
    }

    private record Open(LocalDate day, BigDecimal price) {
    }

    public LiveMarket(KiwoomDailyChartService chartService, MarketDataPort marketData,
                      ObjectProvider<KiwoomWebSocketClient> wsClient, MarketCalendarService calendar, Clock clock) {
        this.chartService = chartService;
        this.marketData = marketData;
        this.wsClient = wsClient;
        this.calendar = calendar;
        this.clock = clock;
    }

    /** 전략이 쓸 종목을 등록한다 — 08:45 일봉·09:00 시가 미리 받기와 실시간 구독 대상. */
    public void register(String owner, Collection<String> symbols) {
        owners.put(owner, new LinkedHashSet<>(symbols));
    }

    /** 등록된 전 종목(중복 제거, 등록 순서). */
    public Set<String> registeredSymbols() {
        Set<String> all = new LinkedHashSet<>();
        owners.values().forEach(all::addAll);
        return all;
    }

    /** 실시간 체결 구독(이미 구독한 종목은 건너뛴다). WS가 꺼져 있으면 아무 것도 안 한다. */
    public void subscribeTicks(Collection<String> symbols) {
        KiwoomWebSocketClient client = wsClient.getIfAvailable();
        if (client == null) {
            return;
        }
        List<String> added = new ArrayList<>();
        for (String symbol : symbols) {
            if (subscribed.add(symbol)) {
                added.add(symbol);
            }
        }
        if (!added.isEmpty()) {
            client.subscribeAll(added);   // REG 묶음 전송 — 종목마다 보내면 105110 거절(2026-10-16)
            log.info("실시간 체결 구독 추가 {}종목(전략용) — 누적 {}종목", added.size(), subscribed.size());
        }
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(MarketConstants.KST));
    }

    /** 미리 받기 마감(2026-10-21) — 절전 복귀로 08:45·09:00:05 예약이 이 시각 뒤에 돌면 받지 않는다(밤에 TR만 쓴다). */
    static final LocalTime PREFETCH_DEADLINE = LocalTime.of(15, 0);

    private boolean tooLate(String job) {
        LocalTime now = LocalTime.now(clock.withZone(MarketConstants.KST));
        if (now.isBefore(PREFETCH_DEADLINE)) {
            return false;
        }
        log.info("{}: 예약 시각을 지나 {}에 실행됨(절전 복귀 등으로 늦게 깨어남) — 건너뜀", job, now.withNano(0));
        return true;
    }

    /** 오늘 이전 완료된 일봉(오름차순). 하루 한 번 받아 둔다. 실패하면 빈 목록(다음 호출에서 다시). */
    public List<Candle> completedBars(String symbol) {
        LocalDate today = today();
        Bars cached = bars.get(symbol);
        if (cached != null && cached.day().equals(today)) {
            return cached.candles();
        }
        try {
            List<Candle> candles = chartService.fetchCompletedDaily(symbol, today, BAR_COUNT);
            if (!candles.isEmpty()) {
                bars.put(symbol, new Bars(today, candles));
            }
            return candles;
        } catch (RuntimeException e) {
            log.warn("일봉 조회 실패({}) — 다음 호출에서 다시: {}", StockNames.label(symbol), e.toString());
            return List.of();
        }
    }

    /** 오늘 시가 — 아직 모르면 한 번 조회한다. 시가가 없으면(장 시작 전·조회 실패) null. */
    public BigDecimal todayOpen(String symbol) {
        LocalDate today = today();
        Open cached = opens.get(symbol);
        if (cached != null && cached.day().equals(today)) {
            return cached.price();
        }
        try {
            BigDecimal open = marketData.stockQuote(symbol).open();
            if (open != null && open.signum() > 0) {
                opens.put(symbol, new Open(today, open));
                return open;
            }
        } catch (RuntimeException e) {
            log.warn("시가 조회 실패({}): {}", StockNames.label(symbol), e.toString());
        }
        return null;
    }

    /** 이미 받아 둔 오늘 시가 — 조회하지 않는다(실시간 체결 처리용). 모르면 null. */
    public BigDecimal knownOpen(String symbol) {
        Open cached = opens.get(symbol);
        return cached != null && cached.day().equals(today()) ? cached.price() : null;
    }

    /** 현재가·시가·고가(ka10001). 실패하면 빈 시세. */
    public MarketDataPort.StockQuote quote(String symbol) {
        try {
            return marketData.stockQuote(symbol);
        } catch (RuntimeException e) {
            log.warn("현재가 조회 실패({}): {}", StockNames.label(symbol), e.toString());
            return MarketDataPort.StockQuote.EMPTY;
        }
    }

    /** 최우선 매도호가(매수 기준) — 없으면 현재가, 둘 다 없으면 null. */
    public BigDecimal bestAsk(String symbol) {
        return best(symbol, true);
    }

    /** 최우선 매수호가(매도 기준) — 없으면 현재가, 둘 다 없으면 null. */
    public BigDecimal bestBid(String symbol) {
        return best(symbol, false);
    }

    private BigDecimal best(String symbol, boolean ask) {
        try {
            MarketDataPort.BestQuote q = marketData.bestQuote(symbol);
            BigDecimal v = ask ? q.bestAsk() : q.bestBid();
            if (v != null && v.signum() > 0) {
                return v;
            }
        } catch (RuntimeException e) {
            log.warn("호가 조회 실패({}): {}", StockNames.label(symbol), e.toString());
        }
        BigDecimal current = quote(symbol).current();
        return current != null && current.signum() > 0 ? current : null;
    }

    /** 등락률 상위(ka10027) — 실패하면 빈 목록. */
    public List<MarketDataPort.RankedStock> risingRanking(String stockCondition) {
        try {
            return marketData.risingRanking(stockCondition, "8", "1000");
        } catch (RuntimeException e) {
            log.warn("등락률 상위 조회 실패(종목조건 {}): {}", stockCondition, e.toString());
            return List.of();
        }
    }

    /** 평일 08:45 — 등록된 종목의 완료된 일봉을 미리 받는다(09:00 판단이 기다리지 않게). */
    @Scheduled(cron = "0 45 8 * * MON-FRI", zone = "Asia/Seoul")
    public void prefetchBars() {
        if (owners.isEmpty() || !calendar.isTradingDay(today()) || tooLate("전략용 일봉 미리 받기(08:45)")) {
            return;
        }
        int ok = 0;
        Set<String> symbols = registeredSymbols();
        for (String symbol : symbols) {
            if (!completedBars(symbol).isEmpty()) {
                ok++;
            }
        }
        log.info("전략용 일봉 미리 받기 — {}/{}종목", ok, symbols.size());
    }

    /** 평일 09:00:05 — 등록된 종목의 오늘 시가를 차례로 받는다. */
    @Scheduled(cron = "5 0 9 * * MON-FRI", zone = "Asia/Seoul")
    public void prefetchOpens() {
        if (owners.isEmpty() || !calendar.isTradingDay(today()) || tooLate("전략용 시가 받기(09:00:05)")) {
            return;
        }
        int ok = 0;
        Set<String> symbols = registeredSymbols();
        for (String symbol : symbols) {
            if (todayOpen(symbol) != null) {
                ok++;
            }
        }
        log.info("전략용 시가 받기 — {}/{}종목", ok, symbols.size());
    }
}
