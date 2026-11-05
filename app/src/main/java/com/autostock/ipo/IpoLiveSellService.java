package com.autostock.ipo;

import com.autostock.common.event.IpoLiveSellRecord;
import com.autostock.common.event.MarketTick;
import com.autostock.common.util.MarketConstants;
import com.autostock.kiwoom.KiwoomLiveSellClient;
import com.autostock.market.KiwoomWebSocketClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.text.DecimalFormat;
import java.time.Clock;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;

/**
 * 공모주 상장일 <b>실거래</b> 매도(2026-11-05 사용자 확정, aiDoc/ipo-automation.md 8절, 결정 로그 D-24).
 *
 * <p>이 기능만 실전 서버로 주문한다 — 단타·스윙·C3·차트 전략은 모의 서버 그대로다(D239 모의 우선 원칙의 좁은 예외).
 * 주문 경로는 {@link IpoLiveBroker}(실전 전용 클라이언트) 하나뿐이고 RiskGate·모의 브로커를 거치지 않는다. 매수 경로는 없다.
 *
 * <p>하루 흐름(KST, 평일):
 * <ol>
 *   <li>08:30 — 오늘 상장하는 배정 딜마다 "회사 N주 3분할(1차·2차·3차 수량)"과 무작위 4자리 코드를 보낸다</li>
 *   <li>08:55까지 {@code /ipo 매도 1234}가 와야 확정. 코드는 한 번 쓰면 끝(맞든 틀리든). 답이 없으면 팔지 않고 알린다</li>
 *   <li>08:56 — 1차 50%: 지정가 = 공모가 60%(시초가 하한) 올림 → 장전 동시호가에 내 시초가에 체결</li>
 *   <li>09:00~14:30 — 실시간 체결(모의 WS 0B)로 장중 고점을 따라가다 −5%면 2차 30%, −10%면 3차 20%(체결가×0.99 지정가)</li>
 *   <li>14:30 — 남은 매도 가능 수량 전부(마지막 체결가×0.99 지정가)</li>
 *   <li>1분마다 미체결 조회로 접수 주문이 빠졌는지 보고 알린다. 15:35 정리 알림</li>
 * </ol>
 *
 * <p>안전: 실전 키가 없거나 {@code ipo.live-sell.enabled=false}면 주문하지 않는다(키 없음은 기동 실패가 아니다). 수량은
 * min(배정 수량, 실전 매도 가능 수량). 차수마다 하루 한 번만 보내고, 거부·결과 불명이면 다시 보내지 않고 알린다.
 * 상태는 메모리에만 있다 — 상장일에 재기동하면 그날 남은 자동 매도는 멈춘다(안전 쪽, 기동 때 알림).
 */
@Service
public class IpoLiveSellService {

    private static final Logger log = LoggerFactory.getLogger(IpoLiveSellService.class);

    static final LocalTime ANNOUNCE_AT = LocalTime.of(8, 30);
    static final LocalTime CONFIRM_DEADLINE = LocalTime.of(8, 55);
    static final LocalTime FIRST_ORDER_AT = LocalTime.of(8, 56);
    static final LocalTime MARKET_OPEN = LocalTime.of(9, 0);
    static final LocalTime CLOSE_OUT_AT = LocalTime.of(14, 30);
    /** 14:30 정리 예약이 늦게 돌면(절전 복귀 등) 이 시각 뒤에는 보내지 않는다 — 정규장 종가 동시호가(15:20) 전. */
    static final LocalTime CLOSE_OUT_LATEST = LocalTime.of(15, 20);
    static final LocalTime FILL_CHECK_END = LocalTime.of(15, 40);
    /** 이 날 수 전부터 청약 시작한 딜까지 상장일 후보로 본다. */
    static final int LOOKBACK_DAYS = 60;
    /** 장중 가격 제한폭 — 상장일은 시초가 기준 ±30%. 장중 지정가 하한 = max(공모가 60%, 시초가 70%). */
    static final BigDecimal INTRADAY_LIMIT_DOWN = new BigDecimal("0.70");

    static final String ALL_CORPS = "공모주 상장일 매도";

    /** 차수. */
    enum Tranche {
        FIRST("1차"), SECOND("2차"), THIRD("3차"), CLOSE("14:30 잔량");

        final String label;

        Tranche(String label) {
            this.label = label;
        }
    }

    /** 차수 상태 — PENDING만 보낼 수 있다. 한 번 벗어나면 그날 다시 PENDING이 되지 않는다. */
    enum State { PENDING, SENDING, ACCEPTED, REJECTED, UNKNOWN, FILLED, SKIPPED }

    /** 오늘 상장 딜 하나의 매도 상태. */
    static final class DealSell {
        final String corpName;
        final String symbol;
        final int allocated;
        final BigDecimal offerPrice;
        final BigDecimal floor;
        /** 확정 뒤 1차 직전에 정한 매도 수량 — min(배정, 실전 매도 가능). 0이면 그날 자동 매도 안 함. */
        int quantity = -1;
        int[] planned = new int[]{0, 0, 0};
        final EnumMap<Tranche, State> states = new EnumMap<>(Tranche.class);
        final EnumMap<Tranche, String> orderNos = new EnumMap<>(Tranche.class);
        BigDecimal open;
        BigDecimal high;
        BigDecimal last;

        DealSell(String corpName, String symbol, int allocated, BigDecimal offerPrice) {
            this.corpName = corpName;
            this.symbol = symbol;
            this.allocated = allocated;
            this.offerPrice = offerPrice;
            this.floor = IpoSellMath.floorPrice(offerPrice);
            for (Tranche t : Tranche.values()) {
                states.put(t, State.PENDING);
            }
        }

        boolean active() {
            return quantity > 0;
        }

        /** 장중 지정가 하한 — 공모가 60%와 시초가 70%(상장일 장중 하한가) 중 큰 값. */
        BigDecimal intradayFloor() {
            if (open == null) {
                return floor;
            }
            BigDecimal limitDown = IpoSellMath.ceilToTick(open.multiply(INTRADAY_LIMIT_DOWN));
            return limitDown.compareTo(floor) > 0 ? limitDown : floor;
        }

        String label() {
            return corpName + "(" + symbol + ")";
        }
    }

    /** 오늘 상태. */
    static final class Day {
        final LocalDate date;
        final List<DealSell> deals;
        String code;
        Instant codeExpiresAt;
        boolean confirmed;
        boolean closed;

        Day(LocalDate date, List<DealSell> deals) {
            this.date = date;
            this.deals = deals;
        }

        Optional<DealSell> deal(String symbol) {
            return deals.stream().filter(d -> d.symbol.equals(symbol)).findFirst();
        }
    }

    private final IpoDealRepository repository;
    private final IpoLiveSellProperties properties;
    /** 실전 창구 — 실전 키가 없으면 null(기능 꺼짐). */
    private final IpoLiveBroker broker;
    private final ApplicationEventPublisher publisher;
    private final Consumer<Collection<String>> tickSubscriber;
    private final Executor orderExecutor;
    private final Clock clock;
    private final IntSupplier codeSource;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Day day;
    /** 꺼짐 안내를 마지막으로 보낸 날 — 하루 한 번. */
    private LocalDate offNoticeDay;

    @Autowired
    public IpoLiveSellService(IpoDealRepository repository, IpoLiveSellProperties properties,
                              WebClient.Builder webClientBuilder, MeterRegistry meterRegistry,
                              ApplicationEventPublisher publisher, ObjectProvider<KiwoomWebSocketClient> wsClient,
                              Clock clock) {
        this(repository, properties, liveBroker(properties, webClientBuilder, meterRegistry, publisher, clock), publisher,
                symbols -> {
                    KiwoomWebSocketClient ws = wsClient.getIfAvailable();
                    if (ws != null) {
                        ws.subscribeAll(symbols);
                    }
                },
                Executors.newSingleThreadExecutor(Thread.ofVirtual().name("ipo-live-sell").factory()),
                clock, new SecureRandom()::nextInt);
        log.info("공모주 상장일 실거래 매도: {}", properties.stateLabel());
    }

    IpoLiveSellService(IpoDealRepository repository, IpoLiveSellProperties properties, IpoLiveBroker broker,
                       ApplicationEventPublisher publisher, Consumer<Collection<String>> tickSubscriber,
                       Executor orderExecutor, Clock clock, IntSupplier codeSource) {
        this.repository = Objects.requireNonNull(repository);
        this.properties = Objects.requireNonNull(properties);
        this.broker = properties.hasKeys() ? broker : null;
        this.publisher = Objects.requireNonNull(publisher);
        this.tickSubscriber = Objects.requireNonNull(tickSubscriber);
        this.orderExecutor = Objects.requireNonNull(orderExecutor);
        this.clock = Objects.requireNonNull(clock);
        this.codeSource = Objects.requireNonNull(codeSource);
    }

    private static IpoLiveBroker liveBroker(IpoLiveSellProperties properties, WebClient.Builder builder,
                                            MeterRegistry meterRegistry, ApplicationEventPublisher publisher, Clock clock) {
        if (!properties.hasKeys()) {
            return null;
        }
        return new KiwoomIpoLiveBroker(KiwoomLiveSellClient.create(builder, properties.appKey(), properties.appSecret(),
                meterRegistry, publisher, clock));
    }

    /** 주문까지 갈 수 있는가 — 실전 키 + 스위치. */
    public boolean ordersEnabled() {
        return broker != null && properties.enabled();
    }

    /** 상태 한 줄(배정 등록 답장용). */
    public String stateLabel() {
        return properties.stateLabel();
    }

    // ===== 08:30 계획 안내 =====

    @Scheduled(cron = "0 30 8 * * MON-FRI", zone = "Asia/Seoul")
    public void announce() {
        LocalDate today = today();
        if (!nowKst().toLocalTime().isBefore(CONFIRM_DEADLINE)) {
            log.info("공모주 상장일 매도 안내: 예약 시각을 지나 실행됨 — 오늘은 건너뜀");
            return;
        }
        List<Allocation> allocations = allocations(today);
        List<Allocation> listingToday = allocations.stream()
                .filter(a -> today.equals(a.listing())).toList();
        if (!ordersEnabled()) {
            noticeOff(today, allocations, listingToday);
            return;
        }
        List<DealSell> deals = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (Allocation a : listingToday) {
            if (a.stockCode() == null || a.offerPrice() == null) {
                record(a.corpName(), a.stockCode(), "SKIP", null, 0, null, null, null,
                        "[공모주 실거래 매도] " + a.corpName() + " — 오늘 상장이지만 "
                                + (a.stockCode() == null ? "종목코드" : "확정 공모가") + "를 몰라 자동 매도하지 않음. 직접 매도하세요.", true);
                continue;
            }
            DealSell deal = new DealSell(a.corpName(), a.stockCode(), a.allocated(), a.offerPrice());
            deals.add(deal);
            int[] split = IpoSellMath.split(a.allocated());
            lines.add("· " + deal.label() + " 배정 " + a.allocated() + "주 → 3분할 1차 " + split[0] + "주 · 2차 " + split[1]
                    + "주 · 3차 " + split[2] + "주. 1차 지정가 " + won(deal.floor) + "(공모가 " + won(a.offerPrice())
                    + "의 60%, 시초가에 체결)" + sellableNote(deal.symbol, a.allocated()));
        }
        if (deals.isEmpty()) {
            return;
        }
        String code = newCode();
        lock.lock();
        try {
            Day d = new Day(today, List.copyOf(deals));
            d.code = code;
            d.codeExpiresAt = at(today, CONFIRM_DEADLINE);
            day = d;
        } finally {
            lock.unlock();
        }
        tickSubscriber.accept(deals.stream().map(x -> x.symbol).toList());
        record(ALL_CORPS, null, "PLAN", null, 0, null, null, null,
                "[공모주 실거래 매도 확인] 오늘 상장 — 실전 계좌에서 3분할 매도합니다.\n" + String.join("\n", lines)
                        + "\n· 2차: 장중 고점 −5%, 3차: 고점 −10%(체결가×0.99 지정가), 14:30 남은 수량 전부."
                        + "\n08:55까지 /ipo 매도 " + code + " 를 보내야 주문합니다. 답이 없으면 팔지 않습니다.", false);
    }

    private String sellableNote(String symbol, int allocated) {
        try {
            long sellable = broker.sellableQuantity(symbol);
            if (sellable < allocated) {
                return " — 지금 실전 매도 가능 " + sellable + "주(주문 직전에 다시 보고 적은 쪽으로 판다)";
            }
            return "";
        } catch (RuntimeException e) {
            log.warn("공모주 실전 잔고 조회 실패({}): {}", symbol, e.getClass().getSimpleName());
            return " — 실전 잔고 조회 실패(주문 직전에 다시 본다)";
        }
    }

    private void noticeOff(LocalDate today, List<Allocation> allocations, List<Allocation> listingToday) {
        List<Allocation> upcoming = allocations.stream()
                .filter(a -> a.listing() == null || !a.listing().isBefore(today)).toList();
        if (upcoming.isEmpty() || today.equals(offNoticeDay)) {
            return;
        }
        offNoticeDay = today;
        String list = upcoming.stream().map(a -> a.corpName() + " " + a.allocated() + "주"
                        + (a.listing() == null ? "(상장일 미정)" : "(상장 " + IpoAlertPlanner.day(a.listing()) + ")"))
                .collect(Collectors.joining(", "));
        String todayLine = listingToday.isEmpty() ? ""
                : "\n오늘 상장: " + listingToday.stream().map(Allocation::corpName).collect(Collectors.joining(", "))
                + " — 자동 매도하지 않습니다. 직접 매도하세요.";
        record(ALL_CORPS, null, "OFF", null, 0, null, null, null,
                "[공모주 실거래 매도] " + properties.stateLabel() + " — 상장일 실거래 매도 꺼짐. 배정 등록: " + list + todayLine,
                !listingToday.isEmpty());
    }

    // ===== 확인 코드 =====

    /** {@code /ipo 매도} — 오늘 계획이 있고 08:55 전이면 새 코드를 낸다. */
    public String requestCode() {
        lock.lock();
        try {
            Day d = day;
            if (d == null || !d.date.equals(today()) || d.deals.isEmpty()) {
                return "[공모주 실거래 매도] 오늘 확인을 기다리는 상장일 매도가 없습니다. (" + properties.stateLabel() + ")";
            }
            if (d.confirmed) {
                return "[공모주 실거래 매도] 이미 확인됐습니다 — 계획대로 진행합니다.";
            }
            if (d.closed || !clock.instant().isBefore(d.codeExpiresAt)) {
                return "[공모주 실거래 매도] 확인 시각(08:55)이 지났습니다 — 오늘은 자동 매도하지 않습니다.";
            }
            d.code = newCode();
            return "[공모주 실거래 매도] 새 확인 코드 — 08:55까지 /ipo 매도 " + d.code + " 를 보내면 주문합니다.";
        } finally {
            lock.unlock();
        }
    }

    /** {@code /ipo 매도 1234} — 코드가 맞고 08:55 전이면 확정. 코드는 맞든 틀리든 한 번 쓰면 버린다. */
    public String confirm(String code) {
        String reply;
        lock.lock();
        try {
            Day d = day;
            if (d == null || !d.date.equals(today()) || d.deals.isEmpty()) {
                return "[공모주 실거래 매도] 오늘 확인을 기다리는 상장일 매도가 없습니다. (" + properties.stateLabel() + ")";
            }
            if (d.confirmed) {
                return "[공모주 실거래 매도] 이미 확인됐습니다 — 계획대로 진행합니다.";
            }
            String pending = d.code;
            d.code = null;
            if (d.closed || !clock.instant().isBefore(d.codeExpiresAt)) {
                return "[공모주 실거래 매도] 확인 시각(08:55)이 지났습니다 — 오늘은 자동 매도하지 않습니다.";
            }
            if (pending == null) {
                return "[공모주 실거래 매도] 확인 코드가 없습니다(이미 썼거나 틀렸음) — /ipo 매도 로 새 코드를 받으세요. (주문하지 않았음)";
            }
            if (!pending.equals(code)) {
                log.warn("공모주 실거래 매도 확인 코드 불일치 — 확정하지 않음");
                return "[공모주 실거래 매도] 확인 코드가 맞지 않습니다 — 확정하지 않았습니다. /ipo 매도 로 새 코드를 받으세요.";
            }
            d.confirmed = true;
            reply = "[공모주 실거래 매도] 확인됨 — 08:56 1차 주문: " + d.deals.stream().map(DealSell::label)
                    .collect(Collectors.joining(", ")) + ". 주문·체결·거부는 모두 알립니다.";
        } finally {
            lock.unlock();
        }
        record(ALL_CORPS, null, "CONFIRM", null, 0, null, null, null, reply, false);
        return reply;
    }

    @Scheduled(cron = "0 55 8 * * MON-FRI", zone = "Asia/Seoul")
    public void confirmationDeadline() {
        Day d = day;
        if (d == null || !d.date.equals(today())) {
            return;
        }
        boolean missed;
        lock.lock();
        try {
            missed = !d.confirmed && !d.closed;
            d.closed = true;
            d.code = null;
        } finally {
            lock.unlock();
        }
        if (missed) {
            record(ALL_CORPS, null, "SKIP", null, 0, null, null, null,
                    "[공모주 실거래 매도] 08:55까지 확인이 없어 오늘은 자동 매도하지 않습니다 — "
                            + d.deals.stream().map(DealSell::label).collect(Collectors.joining(", ")) + ". 직접 매도하세요.", true);
        }
    }

    // ===== 08:56 1차 =====

    @Scheduled(cron = "0 56 8 * * MON-FRI", zone = "Asia/Seoul")
    public void placeFirst() {
        Day d = confirmedToday();
        if (d == null || !ordersEnabled()) {
            return;
        }
        boolean late = !nowKst().toLocalTime().isBefore(MARKET_OPEN);
        for (DealSell deal : d.deals) {
            if (deal.quantity >= 0) {
                continue;   // 이미 정했다(같은 날 두 번 돌지 않게)
            }
            long sellable;
            try {
                sellable = broker.sellableQuantity(deal.symbol);
            } catch (RuntimeException e) {
                stop(deal, "실전 잔고 조회 실패(" + e.getClass().getSimpleName() + ") — 오늘 자동 매도 중단. 직접 매도하세요.");
                continue;
            }
            int qty = (int) Math.min(deal.allocated, Math.max(0, sellable));
            lock.lock();
            try {
                deal.quantity = qty;
                deal.planned = IpoSellMath.split(qty);
            } finally {
                lock.unlock();
            }
            if (qty == 0) {
                stop(deal, "실전 계좌에 매도 가능 수량이 없습니다(배정 " + deal.allocated + "주) — 오늘 자동 매도 중단.");
                continue;
            }
            if (late) {
                markSkipped(deal, Tranche.FIRST);
                record(deal, "SKIP", Tranche.FIRST, deal.planned[0], null, null, null,
                        "[공모주 실거래 매도] " + deal.label() + " 1차 예약이 늦게 실행돼(09:00 뒤) 장전 1차를 건너뜁니다. "
                                + "2차·3차·14:30 규칙은 그대로입니다.", true);
                continue;
            }
            send(deal, Tranche.FIRST, deal.planned[0], deal.floor);
        }
    }

    // ===== 장중 2차·3차 =====

    /** 실시간 체결(모의 WS 0B) — 오늘 상장 딜의 장중 고점·마지막 체결가를 따라가고 −5%·−10%에서 주문한다. */
    @EventListener
    public void onTick(MarketTick tick) {
        Day d = day;
        if (d == null || !d.confirmed || tick == null || tick.symbol() == null) {
            return;
        }
        Optional<DealSell> found = d.deal(tick.symbol().value());
        if (found.isEmpty()) {
            return;
        }
        ZonedDateTime at = tick.timestamp().atZone(MarketConstants.KST);
        if (!at.toLocalDate().equals(d.date) || at.toLocalTime().isBefore(MARKET_OPEN)) {
            return;
        }
        DealSell deal = found.get();
        BigDecimal price = tick.price().value();
        List<Runnable> orders = new ArrayList<>();
        lock.lock();
        try {
            if (deal.open == null) {
                deal.open = price;
            }
            deal.last = price;
            if (deal.high == null || price.compareTo(deal.high) > 0) {
                deal.high = price;
            }
            if (!deal.active() || !at.toLocalTime().isBefore(CLOSE_OUT_AT)) {
                return;
            }
            BigDecimal limit = IpoSellMath.intradayLimit(price, deal.intradayFloor());
            if (deal.states.get(Tranche.SECOND) == State.PENDING
                    && IpoSellMath.droppedFromHigh(deal.high, price, IpoSellMath.SECOND_DROP)) {
                orders.add(claim(deal, Tranche.SECOND, limit));
            }
            if (deal.states.get(Tranche.THIRD) == State.PENDING
                    && IpoSellMath.droppedFromHigh(deal.high, price, IpoSellMath.THIRD_DROP)) {
                orders.add(claim(deal, Tranche.THIRD, limit));
            }
        } finally {
            lock.unlock();
        }
        orders.stream().filter(Objects::nonNull).forEach(orderExecutor::execute);
    }

    /** 잠금 안에서 차수를 SENDING으로 잡고 보낼 일을 돌려준다(수량 0이면 건너뜀 표시, null). */
    private Runnable claim(DealSell deal, Tranche tranche, BigDecimal limit) {
        int qty = deal.planned[tranche.ordinal()];
        if (qty <= 0) {
            deal.states.put(tranche, State.SKIPPED);
            return null;
        }
        deal.states.put(tranche, State.SENDING);
        return () -> sendClaimed(deal, tranche, qty, limit);
    }

    // ===== 14:30 잔량 =====

    @Scheduled(cron = "0 30 14 * * MON-FRI", zone = "Asia/Seoul")
    public void closeOut() {
        Day d = confirmedToday();
        if (d == null || !ordersEnabled()) {
            return;
        }
        if (!nowKst().toLocalTime().isBefore(CLOSE_OUT_LATEST)) {
            d.deals.stream().filter(DealSell::active).forEach(deal -> record(deal, "SKIP", Tranche.CLOSE, 0, null, null, null,
                    "[공모주 실거래 매도] " + deal.label() + " 14:30 잔량 예약이 늦게 실행돼 보내지 않습니다 — 직접 확인하세요.", true));
            return;
        }
        for (DealSell deal : d.deals) {
            BigDecimal limit;
            lock.lock();
            try {
                if (!deal.active() || deal.states.get(Tranche.CLOSE) != State.PENDING) {
                    continue;
                }
                for (Tranche t : List.of(Tranche.SECOND, Tranche.THIRD)) {
                    if (deal.states.get(t) == State.PENDING) {
                        deal.states.put(t, State.SKIPPED);
                    }
                }
                deal.states.put(Tranche.CLOSE, State.SENDING);
                limit = deal.last == null ? deal.intradayFloor() : IpoSellMath.intradayLimit(deal.last, deal.intradayFloor());
            } finally {
                lock.unlock();
            }
            long sellable;
            try {
                sellable = broker.sellableQuantity(deal.symbol);
            } catch (RuntimeException e) {
                finish(deal, Tranche.CLOSE, State.SKIPPED);
                record(deal, "SKIP", Tranche.CLOSE, 0, null, null, null, "[공모주 실거래 매도] " + deal.label()
                        + " 14:30 잔량: 실전 잔고 조회 실패(" + e.getClass().getSimpleName() + ") — 보내지 않음. 직접 매도하세요.", true);
                continue;
            }
            int qty = (int) Math.min(deal.quantity, Math.max(0, sellable));
            if (qty == 0) {
                finish(deal, Tranche.CLOSE, State.SKIPPED);
                record(deal, "SKIP", Tranche.CLOSE, 0, null, null, null,
                        "[공모주 실거래 매도] " + deal.label() + " 14:30 잔량 없음 — 매도 가능 0주(다 팔렸거나 미체결 주문에 묶임).", false);
                continue;
            }
            sendClaimed(deal, Tranche.CLOSE, qty, limit);
        }
    }

    // ===== 체결 확인 =====

    /** 1분마다 — 접수된 주문이 실전 미체결 목록에서 빠졌으면 체결로 보고 알린다. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void checkFills() {
        Day d = day;
        if (d == null || !d.confirmed || !d.date.equals(today()) || broker == null) {
            return;
        }
        LocalTime now = nowKst().toLocalTime();
        if (now.isBefore(MARKET_OPEN) || now.isAfter(FILL_CHECK_END)) {
            return;
        }
        for (DealSell deal : d.deals) {
            Map<Tranche, String> open = new LinkedHashMap<>();
            lock.lock();
            try {
                deal.orderNos.forEach((t, no) -> {
                    if (deal.states.get(t) == State.ACCEPTED) {
                        open.put(t, no);
                    }
                });
            } finally {
                lock.unlock();
            }
            if (open.isEmpty()) {
                continue;
            }
            List<String> outstanding;
            try {
                outstanding = broker.outstandingOrderNos(deal.symbol).stream().map(IpoLiveSellService::orderKey).toList();
            } catch (RuntimeException e) {
                log.warn("공모주 실전 미체결 조회 실패({}): {}", deal.symbol, e.getClass().getSimpleName());
                continue;
            }
            open.forEach((t, no) -> {
                if (!outstanding.contains(orderKey(no))) {
                    finish(deal, t, State.FILLED);
                    record(deal, "FILL", t, deal.planned.length > t.ordinal() ? deal.planned[t.ordinal()] : 0, null, no, "FILLED",
                            "[공모주 실거래 매도] " + deal.label() + " " + t.label + " 주문(" + no
                                    + ")이 미체결 목록에서 빠졌습니다 — 체결로 봅니다(체결가는 영웅문에서 확인).", false);
                }
            });
        }
    }

    @Scheduled(cron = "0 35 15 * * MON-FRI", zone = "Asia/Seoul")
    public void summary() {
        Day d = confirmedToday();
        if (d == null) {
            return;
        }
        for (DealSell deal : d.deals) {
            String states;
            lock.lock();
            try {
                states = deal.states.entrySet().stream()
                        .map(e -> e.getKey().label + " " + stateLabel(e.getValue())
                                + (deal.orderNos.containsKey(e.getKey()) ? "(" + deal.orderNos.get(e.getKey()) + ")" : ""))
                        .collect(Collectors.joining(" · "));
            } finally {
                lock.unlock();
            }
            String remain = "";
            if (broker != null) {
                try {
                    remain = " · 실전 매도 가능 잔량 " + broker.sellableQuantity(deal.symbol) + "주";
                } catch (RuntimeException e) {
                    remain = " · 잔량 조회 실패";
                }
            }
            record(deal, "SUMMARY", null, Math.max(deal.quantity, 0), null, null, null,
                    "[공모주 실거래 매도 정리] " + deal.label() + " 매도 대상 " + Math.max(deal.quantity, 0) + "주 — " + states
                            + (deal.high == null ? "" : " · 장중 고점 " + won(deal.high)) + remain, false);
        }
    }

    /** 상장일 장중에 재기동하면 그날 자동 매도는 멈춘다 — 알린다. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        LocalTime now = nowKst().toLocalTime();
        if (!ordersEnabled() || now.isBefore(ANNOUNCE_AT) || now.isAfter(FILL_CHECK_END)) {
            return;
        }
        try {
            LocalDate today = today();
            List<String> names = allocations(today).stream().filter(a -> today.equals(a.listing()))
                    .map(Allocation::corpName).toList();
            if (!names.isEmpty()) {
                record(ALL_CORPS, null, "SKIP", null, 0, null, null, null, "[공모주 실거래 매도] 상장일에 앱이 다시 시작됐습니다 — "
                        + String.join(", ", names) + "의 오늘 남은 자동 매도는 멈춥니다. 직접 확인·매도하세요.", true);
            }
        } catch (RuntimeException e) {
            log.warn("공모주 상장일 재기동 확인 실패: {}", e.getClass().getSimpleName());
        }
    }

    // ===== 주문 =====

    /** 잠금 밖에서 1차를 보낸다 — 먼저 SENDING으로 잡는다. */
    private void send(DealSell deal, Tranche tranche, int qty, BigDecimal limit) {
        lock.lock();
        try {
            if (deal.states.get(tranche) != State.PENDING) {
                return;
            }
            if (qty <= 0) {
                deal.states.put(tranche, State.SKIPPED);
                return;
            }
            deal.states.put(tranche, State.SENDING);
        } finally {
            lock.unlock();
        }
        sendClaimed(deal, tranche, qty, limit);
    }

    /** 이미 SENDING으로 잡은 차수를 한 번 보낸다. 결과가 무엇이든 다시 보내지 않는다. */
    private void sendClaimed(DealSell deal, Tranche tranche, int qty, BigDecimal limit) {
        if (!ordersEnabled()) {
            finish(deal, tranche, State.SKIPPED);
            return;
        }
        IpoLiveBroker.SellResult result;
        try {
            result = broker.sellLimit(deal.symbol, qty, limit);
        } catch (RuntimeException e) {
            result = new IpoLiveBroker.SellResult(IpoLiveBroker.Outcome.UNKNOWN, null, e.getClass().getSimpleName());
        }
        String head = "[공모주 실거래 매도] " + deal.label() + " " + tranche.label + " " + qty + "주 지정가 " + won(limit);
        switch (result.outcome()) {
            case ACCEPTED -> {
                lock.lock();
                try {
                    deal.orderNos.put(tranche, result.orderNo());
                    deal.states.put(tranche, State.ACCEPTED);
                } finally {
                    lock.unlock();
                }
                record(deal, "ORDER", tranche, qty, limit, result.orderNo(), "ACCEPTED",
                        head + " — 접수(주문번호 " + result.orderNo() + ")" + reason(tranche, deal), false);
            }
            case REJECTED -> {
                finish(deal, tranche, State.REJECTED);
                record(deal, "ORDER", tranche, qty, limit, null, "REJECTED",
                        head + " — 거부/오류: " + result.reason() + ". 다시 보내지 않습니다. 영웅문에서 확인하세요.", true);
            }
            case UNKNOWN -> {
                finish(deal, tranche, State.UNKNOWN);
                record(deal, "ORDER", tranche, qty, limit, null, "UNKNOWN",
                        head + " — 결과 불명(" + result.reason() + "). 접수됐을 수 있어 다시 보내지 않습니다. 영웅문에서 확인하세요.", true);
            }
        }
    }

    private static String reason(Tranche tranche, DealSell deal) {
        return switch (tranche) {
            case FIRST -> " — 장전 동시호가, 공모가 60%(시초가에 체결)";
            case SECOND -> " — 장중 고점 " + won(deal.high) + " 대비 −5%";
            case THIRD -> " — 장중 고점 " + won(deal.high) + " 대비 −10%";
            case CLOSE -> " — 14:30 남은 수량";
        };
    }

    private void stop(DealSell deal, String message) {
        lock.lock();
        try {
            deal.quantity = 0;
            deal.states.replaceAll((t, s) -> s == State.PENDING ? State.SKIPPED : s);
        } finally {
            lock.unlock();
        }
        record(deal, "SKIP", null, 0, null, null, null, "[공모주 실거래 매도] " + deal.label() + " " + message, true);
    }

    private void markSkipped(DealSell deal, Tranche tranche) {
        finish(deal, tranche, State.SKIPPED);
    }

    private void finish(DealSell deal, Tranche tranche, State state) {
        lock.lock();
        try {
            deal.states.put(tranche, state);
        } finally {
            lock.unlock();
        }
    }

    // ===== 기록 =====

    private void record(DealSell deal, String action, Tranche tranche, long qty, BigDecimal price, String orderNo,
                        String outcome, String message, boolean urgent) {
        record(deal.corpName, deal.symbol, action, tranche, qty, price, orderNo, outcome, message, urgent);
    }

    private void record(String corpName, String symbol, String action, Tranche tranche, long qty, BigDecimal price,
                        String orderNo, String outcome, String message, boolean urgent) {
        log.info("공모주 실거래 매도 {}: {}", action, message.replace('\n', ' '));
        publisher.publishEvent(new IpoLiveSellRecord(corpName, symbol, action, tranche == null ? null : tranche.name(),
                qty, price, orderNo, outcome, message, urgent, clock.instant()));
    }

    // ===== 읽기 =====

    /** 배정 수량이 있는 공모주(회사별 하나). */
    record Allocation(String corpName, String stockCode, int allocated, LocalDate listing, BigDecimal offerPrice) {
    }

    List<Allocation> allocations(LocalDate today) {
        List<IpoDealEntity> deals = repository.findBySubscriptionStartGreaterThanEqualOrSubscriptionStartIsNull(
                today.minusDays(LOOKBACK_DAYS));
        Map<String, List<IpoDealEntity>> byCorp = deals.stream().filter(x -> !x.isRightsOffering())
                .collect(Collectors.groupingBy(IpoDealEntity::getCorpCode, LinkedHashMap::new, Collectors.toList()));
        List<Allocation> result = new ArrayList<>();
        for (List<IpoDealEntity> rows : byCorp.values()) {
            IpoDealEntity rep = representative(rows);
            Integer allocated = rep.getAllocatedQty() != null ? rep.getAllocatedQty()
                    : rows.stream().map(IpoDealEntity::getAllocatedQty).filter(Objects::nonNull).findFirst().orElse(null);
            if (allocated == null || allocated <= 0) {
                continue;
            }
            LocalDate listing = rows.stream().map(IpoDealEntity::getListingDate).filter(Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElse(null);
            String code = rows.stream().map(IpoDealEntity::getStockCode).filter(Objects::nonNull).findFirst().orElse(null);
            BigDecimal offer = rows.stream().map(IpoDealEntity::getOfferPriceFinal).filter(Objects::nonNull).findFirst()
                    .orElse(null);
            result.add(new Allocation(rep.getCorpName(), code, allocated, listing, offer));
        }
        return result;
    }

    /** 회사의 대표 행 — 청약 시작일이 있는 행 중 최신 접수번호(IpoAlertPlanner와 같은 규칙). */
    static IpoDealEntity representative(List<IpoDealEntity> rows) {
        IpoDealEntity latest = rows.stream().max(Comparator.comparing(IpoDealEntity::getRceptNo)).orElseThrow();
        return rows.stream().filter(d -> d.getSubscriptionStart() != null)
                .max(Comparator.comparing(IpoDealEntity::getRceptNo)).orElse(latest);
    }

    /** 시험·조회용 — 오늘 상태. */
    Day currentDay() {
        return day;
    }

    private Day confirmedToday() {
        Day d = day;
        return d != null && d.confirmed && d.date.equals(today()) ? d : null;
    }

    private String newCode() {
        return "%04d".formatted(Math.floorMod(codeSource.getAsInt(), 10_000));
    }

    private LocalDate today() {
        return nowKst().toLocalDate();
    }

    private ZonedDateTime nowKst() {
        return ZonedDateTime.ofInstant(clock.instant(), MarketConstants.KST);
    }

    private static Instant at(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(MarketConstants.KST).toInstant();
    }

    private static String orderKey(String orderNo) {
        String text = orderNo == null ? "" : orderNo.trim();
        String stripped = text.replaceFirst("^0+", "");
        return stripped.isEmpty() ? text : stripped;
    }

    private static String stateLabel(State state) {
        return switch (state) {
            case PENDING -> "안 함";
            case SENDING -> "보내는 중";
            case ACCEPTED -> "접수(미체결)";
            case REJECTED -> "거부";
            case UNKNOWN -> "결과 불명";
            case FILLED -> "체결";
            case SKIPPED -> "건너뜀";
        };
    }

    static String won(BigDecimal value) {
        return value == null ? "?" : new DecimalFormat("#,##0").format(value.setScale(0, RoundingMode.HALF_UP)) + "원";
    }
}
