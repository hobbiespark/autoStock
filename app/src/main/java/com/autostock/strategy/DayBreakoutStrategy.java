package com.autostock.strategy;

import com.autostock.common.event.Candle;
import com.autostock.common.event.MarketTick;
import com.autostock.common.event.StrategyRunCompleted;
import com.autostock.common.util.StockCode;
import com.autostock.common.util.StockNames;
import com.autostock.portfolio.PositionBook;
import com.autostock.portfolio.StrategyBook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 단타 변동성 돌파(VB-DAY) — 모의투자 다전략 중 하나(2026-10-16, aiDoc/multi-strategy-paper-2026-10-16.md 3-1).
 *
 * <p>흐름(거래일):
 * <ol>
 *   <li>08:50 준비 — 20종목의 완료된 일봉으로 전일 봉·SMA20·전일 양봉을 센다({@link DayBreakoutMath#setup}).</li>
 *   <li>09:00:02 — 어제 못 판 보유가 있으면 판다(시간 청산 실패분).</li>
 *   <li>시가 확인 뒤(09:00:05~) ~ 14:30 — 실시간 체결가가 목표가 이상이면 매수. 종목당 하루 한 번, 동시 보유 3, 하루 6건.</li>
 *   <li>보유 중 — 실시간 체결가가 평단 +1.8% 이상이면 익절, −1.5% 이하면 손절.</li>
 *   <li>15:15 — 남은 보유 전부 매도, 15:18 한 번 더. 15:20 하루 요약.</li>
 * </ol>
 * 실시간 체결 처리(WS 스레드)는 메모리만 보고 판단하고, 신호는 {@link SignalOutbox}에 넣는다. 조회(TR)는 스케줄 스레드에서만 한다.
 */
@Component
public class DayBreakoutStrategy {

    private static final Logger log = LoggerFactory.getLogger(DayBreakoutStrategy.class);
    static final String STRATEGY_ID = "VB-DAY";
    private static final String HORIZON = "DAY";
    private static final long EXIT_RETRY_MILLIS = 60_000;
    private static final long ACTIVE_RECHECK_MILLIS = 30_000;

    private final DayBreakoutProperties properties;
    private final LiveMarket market;
    private final StrategyBook book;
    private final SignalOutbox outbox;
    private final StrategyGuard guard;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    private final Map<String, DayBreakoutMath.Setup> setups = new ConcurrentHashMap<>();
    private final Set<String> entered = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastExitAttempt = new ConcurrentHashMap<>();
    private final Map<String, String> skipped = new ConcurrentHashMap<>();
    private final AtomicInteger entries = new AtomicInteger();
    private final AtomicInteger takeProfits = new AtomicInteger();
    private final AtomicInteger stopLosses = new AtomicInteger();
    private final AtomicInteger timeExits = new AtomicInteger();
    private volatile LocalDate day;
    private volatile boolean active;
    private volatile long activeCheckedAt;

    public DayBreakoutStrategy(DayBreakoutProperties properties, LiveMarket market, StrategyBook book,
                               SignalOutbox outbox, StrategyGuard guard, ApplicationEventPublisher publisher,
                               Clock clock) {
        this.properties = properties;
        this.market = market;
        this.book = book;
        this.outbox = outbox;
        this.guard = guard;
        this.publisher = publisher;
        this.clock = clock;
    }

    /** 기동 — 종목 등록과 실시간 체결 구독(켜져 있을 때만). */
    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        if (!properties.enabled()) {
            return;
        }
        market.register(STRATEGY_ID, properties.symbols());
        market.subscribeTicks(properties.symbols());
        log.info("{}: 단타 변동성 돌파 켜짐 — {}종목, k={}, 익절 +{}%·손절 −{}%, 진입 ~{}, 시간 청산 {}", STRATEGY_ID,
                properties.symbols().size(), properties.k(), pct(properties.takeProfit()), pct(properties.stopLoss()),
                properties.entryUntil(), properties.exitAt());
    }

    /** 08:50 장전 준비. */
    @Scheduled(cron = "0 50 8 * * MON-FRI", zone = "Asia/Seoul")
    public void prepare() {
        if (!guard.active(properties.enabled())) {
            return;
        }
        String line = prepareToday();
        log.info(line);
        publisher.publishEvent(new StrategyRunCompleted(STRATEGY_ID, guard.today(), line, 0, clock.instant()));
    }

    /** 준비값을 센다(오늘 처음이면 하루 상태를 비운다). 요약 한 줄을 돌려준다. */
    String prepareToday() {
        resetIfNewDay();
        int ready = 0;
        int prevUp = 0;
        List<String> noData = new ArrayList<>();
        for (String symbol : properties.symbols()) {
            DayBreakoutMath.Setup setup = setupOf(market.completedBars(symbol));
            if (setup == null) {
                noData.add(StockNames.label(symbol));
                continue;
            }
            setups.put(symbol, setup);
            ready++;
            if (setup.prevUp()) {
                prevUp++;
            }
        }
        return "%s %s 준비 — %d종목 중 일봉 준비 %d · 전일 양봉 %d(시가 확인 뒤 시가>SMA%d·목표가 판단)%s".formatted(
                STRATEGY_ID, guard.today(), properties.symbols().size(), ready, prevUp, properties.smaDays(),
                noData.isEmpty() ? "" : " · 데이터 부족 " + String.join(", ", noData));
    }

    /** 완료된 일봉 → 준비값(봉이 없거나 모자라면 null). */
    private DayBreakoutMath.Setup setupOf(List<Candle> bars) {
        if (bars == null || bars.isEmpty()) {
            return null;
        }
        Candle prev = bars.get(bars.size() - 1);
        return DayBreakoutMath.setup(bars.stream().map(Candle::close).toList(), prev.open(), prev.high(), prev.low(),
                properties.smaDays());
    }

    private void resetIfNewDay() {
        LocalDate today = guard.today();
        if (!today.equals(day)) {
            day = today;
            setups.clear();
            entered.clear();
            lastExitAttempt.clear();
            skipped.clear();
            entries.set(0);
            takeProfits.set(0);
            stopLosses.set(0);
            timeExits.set(0);
        }
    }

    /** 장중 재기동 — 준비값이 없으면 1분마다 확인해 만든다(조회는 이 스케줄 스레드에서). */
    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    public void catchUp() {
        if (!properties.enabled()) {
            return;
        }
        LocalTime now = guard.now();
        if (now.isBefore(LocalTime.of(8, 50)) || !now.isBefore(properties.exitAt())) {
            return;
        }
        if (setups.isEmpty() || !guard.today().equals(day)) {
            if (guard.active(properties.enabled())) {
                log.info("{}: 오늘 준비값 없음(장중 기동) — 지금 만든다: {}", STRATEGY_ID, prepareToday());
            }
        }
        if (!now.isBefore(LocalTime.of(9, 0))) {
            for (String symbol : properties.symbols()) {
                if (market.knownOpen(symbol) == null) {
                    market.todayOpen(symbol);
                }
            }
        }
    }

    /** 09:00:02 — 지난 거래일 시간 청산이 실패해 남은 보유를 판다(오늘 진입은 시가 확인 뒤라 아직 없다). */
    @Scheduled(cron = "2 0 9 * * MON-FRI", zone = "Asia/Seoul")
    public void sellCarriedOver() {
        if (!guard.active(properties.enabled())) {
            return;
        }
        Map<StockCode, PositionBook.Position> held = book.positions(STRATEGY_ID);
        if (held.isEmpty()) {
            return;
        }
        log.warn("{}: 지난 거래일에서 넘어온 보유 {}종목 — 지금 판다(시간 청산 실패분)", STRATEGY_ID, held.size());
        held.keySet().forEach(code -> sellNow(code.value(), "지난 거래일 시간 청산 실패분 정리"));
    }

    /** 실시간 체결 — 청산 먼저, 그다음 진입. */
    @EventListener
    public void onTick(MarketTick tick) {
        if (!properties.enabled() || tick.price() == null) {
            return;
        }
        String symbol = tick.symbol().value();
        if (!properties.symbols().contains(symbol) || !isActive()) {
            return;
        }
        BigDecimal price = tick.price().value();
        LocalTime now = guard.now();
        if (now.isBefore(LocalTime.of(9, 0)) || !now.isBefore(LocalTime.of(15, 20))) {
            return;
        }
        book.position(STRATEGY_ID, tick.symbol()).ifPresentOrElse(
                position -> checkExit(symbol, price, position),
                () -> checkEntry(symbol, price, now));
    }

    private void checkExit(String symbol, BigDecimal price, PositionBook.Position position) {
        if (position.avgPrice() == null) {
            return;
        }
        String reason = null;
        if (DayBreakoutMath.hitsTakeProfit(price, position.avgPrice(), properties.takeProfit())) {
            reason = "익절";
        } else if (DayBreakoutMath.hitsStopLoss(price, position.avgPrice(), properties.stopLoss())) {
            reason = "손절";
        }
        if (reason == null || !exitAllowedNow(symbol)) {
            return;
        }
        (reason.equals("익절") ? takeProfits : stopLosses).incrementAndGet();
        Map<String, String> metrics = new LinkedHashMap<>();
        metrics.put("price", price.toPlainString());
        metrics.put("avgPrice", position.avgPrice().toPlainString());
        metrics.put("rule", reason.equals("익절") ? "평단×(1+" + properties.takeProfit() + ")"
                : "평단×(1−" + properties.stopLoss() + ")");
        log.info("{}: {} {} — 체결가 {} / 평단 {}", STRATEGY_ID, reason, StockNames.label(symbol), price.toPlainString(),
                position.avgPrice().toPlainString());
        outbox.sell(STRATEGY_ID, HORIZON, symbol, SignalOutbox.times(price, 1 - properties.sellSlack()),
                reason + " — 체결가 " + price.toPlainString() + ", 평단 " + position.avgPrice().toPlainString(), metrics);
    }

    private void checkEntry(String symbol, BigDecimal price, LocalTime now) {
        if (!now.isBefore(properties.entryUntil()) || entered.contains(symbol)) {
            return;
        }
        DayBreakoutMath.Setup setup = setups.get(symbol);
        BigDecimal open = market.knownOpen(symbol);
        if (setup == null || open == null) {
            return;
        }
        if (!DayBreakoutMath.passesFilters(setup, open)) {
            if (skipped.putIfAbsent(symbol, "필터") == null) {
                outbox.decision(STRATEGY_ID, HORIZON, symbol, "SKIP",
                        !setup.prevUp() ? "전일 음봉 — 오늘 진입 안 함" : "시가 ≤ SMA" + properties.smaDays() + " — 오늘 진입 안 함",
                        Map.of("open", open.toPlainString(), "sma", setup.smaClose().toPlainString()));
            }
            return;
        }
        BigDecimal target = DayBreakoutMath.target(setup, open, properties.k());
        if (price.compareTo(target) < 0) {
            return;
        }
        if (book.openPositionCount(STRATEGY_ID) >= properties.maxPositions()
                || entries.get() >= properties.maxEntriesPerDay()) {
            if (skipped.putIfAbsent(symbol, "한도") == null) {
                outbox.decision(STRATEGY_ID, HORIZON, symbol, "SKIP", "돌파했지만 동시 보유·하루 진입 한도 — 진입 안 함",
                        Map.of("target", target.toPlainString(), "price", price.toPlainString()));
            }
            return;
        }
        if (!entered.add(symbol)) {
            return;
        }
        entries.incrementAndGet();
        Map<String, String> metrics = new LinkedHashMap<>();
        metrics.put("open", open.toPlainString());
        metrics.put("prevHigh", setup.prevHigh().toPlainString());
        metrics.put("prevLow", setup.prevLow().toPlainString());
        metrics.put("k", String.valueOf(properties.k()));
        metrics.put("target", target.toPlainString());
        metrics.put("price", price.toPlainString());
        metrics.put("sma", setup.smaClose().toPlainString());
        log.info("{}: 돌파 매수 {} — 체결가 {} ≥ 목표가 {}(시가 {} + {}×전일폭)", STRATEGY_ID, StockNames.label(symbol),
                price.toPlainString(), target.toPlainString(), open.toPlainString(), properties.k());
        outbox.buy(STRATEGY_ID, HORIZON, symbol, SignalOutbox.times(price, 1 + properties.buySlack()), 1.0,
                "변동성 돌파 — 체결가 " + price.toPlainString() + " ≥ 목표가 " + target.toPlainString(), metrics);
    }

    /** 15:15 시간 청산. */
    @Scheduled(cron = "0 15 15 * * MON-FRI", zone = "Asia/Seoul")
    public void timeExit() {
        if (!guard.active(properties.enabled())) {
            return;
        }
        Map<StockCode, PositionBook.Position> held = book.positions(STRATEGY_ID);
        held.keySet().forEach(code -> {
            timeExits.incrementAndGet();
            sellNow(code.value(), "시간 청산(" + properties.exitAt() + ")");
        });
        if (!held.isEmpty()) {
            log.info("{}: 시간 청산 {}종목", STRATEGY_ID, held.size());
        }
    }

    /** 15:18 — 시간 청산이 아직 체결되지 않은 보유를 한 번 더(미체결 매도가 남아 있으면 RiskGate가 막는다). */
    @Scheduled(cron = "0 18 15 * * MON-FRI", zone = "Asia/Seoul")
    public void timeExitRetry() {
        if (!guard.active(properties.enabled())) {
            return;
        }
        Map<StockCode, PositionBook.Position> held = book.positions(STRATEGY_ID);
        if (!held.isEmpty()) {
            log.warn("{}: 15:18 아직 보유 {}종목 — 한 번 더 매도", STRATEGY_ID, held.size());
            held.keySet().forEach(code -> sellNow(code.value(), "시간 청산 재시도"));
        }
    }

    /** 15:20 하루 요약. */
    @Scheduled(cron = "0 20 15 * * MON-FRI", zone = "Asia/Seoul")
    public void summary() {
        if (!guard.active(properties.enabled())) {
            return;
        }
        int left = book.openPositionCount(STRATEGY_ID);
        String line = "%s %s 요약 — 진입 %d · 익절 %d · 손절 %d · 시간 청산 %d%s".formatted(STRATEGY_ID, guard.today(),
                entries.get(), takeProfits.get(), stopLosses.get(), timeExits.get(),
                left > 0 ? " · 아직 보유 " + left + "종목(내일 09:00 정리)" : "");
        log.info(line);
        publisher.publishEvent(new StrategyRunCompleted(STRATEGY_ID, guard.today(), line, left > 0 ? 1 : 0,
                clock.instant()));
    }

    private void sellNow(String symbol, String reason) {
        BigDecimal bid = market.bestBid(symbol);
        outbox.sell(STRATEGY_ID, HORIZON, symbol, SignalOutbox.times(bid, 1 - properties.sellSlack()), reason,
                Map.of("bestBid", bid == null ? "N/A" : bid.toPlainString()));
    }

    private boolean exitAllowedNow(String symbol) {
        long nowMs = clock.millis();
        Long last = lastExitAttempt.get(symbol);
        if (last != null && nowMs - last < EXIT_RETRY_MILLIS) {
            return false;
        }
        lastExitAttempt.put(symbol, nowMs);
        return true;
    }

    /** 실시간 처리용 활성 여부 — 30초마다 다시 본다(거래일·운영 상태 조회를 체결마다 하지 않게). */
    private boolean isActive() {
        long nowMs = clock.millis();
        if (nowMs - activeCheckedAt > ACTIVE_RECHECK_MILLIS) {
            active = guard.active(properties.enabled());
            activeCheckedAt = nowMs;
            if (active) {
                resetIfNewDay();
            }
        }
        return active;
    }

    private static String pct(double v) {
        return BigDecimal.valueOf(v).movePointRight(2).stripTrailingZeros().toPlainString();
    }
}
