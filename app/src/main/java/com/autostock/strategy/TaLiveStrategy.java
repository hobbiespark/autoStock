package com.autostock.strategy;

import com.autostock.common.event.Candle;
import com.autostock.common.event.MarketTick;
import com.autostock.common.event.StrategyRunCompleted;
import com.autostock.common.util.StockCode;
import com.autostock.common.util.StockNames;
import com.autostock.portfolio.StrategyBook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 차트 기법 5종(TA-H1~H5) 라이브 — 모의투자 다전략 중 하나(2026-10-16, aiDoc/multi-strategy-paper-2026-10-16.md 3-3).
 *
 * <p>규칙은 백테스트 클래스 그대로다({@link DailyRuleBook}, 구현 {@code backtest.TaRuleBook}). 매 거래일 09:01(시가 확인 뒤)에
 * 규칙·종목마다 "그림자 포지션"과 오늘 할 일을 받아 실제 보유(전략별 장부)와 맞춘다.
 * <ul>
 *   <li>둘 다 미보유 → 오늘 진입 신호를 따른다(시가 매수, 또는 장중 돌파 감시)</li>
 *   <li>둘 다 보유 → 오늘 청산 신호를 따른다(시가 매도, 또는 장중 손절 감시)</li>
 *   <li>규칙상 보유·실제 미보유 → 따라 사지 않는다(이미 지나간 진입)</li>
 *   <li>규칙상 미보유·실제 보유 → 시가에 판다(규칙과 어긋난 보유 정리)</li>
 * </ul>
 * 장중 감시(돌파 매수·손절)는 실시간 체결가로 본다. 전략 ID = 규칙 ID(TA-H1…)라 슬리브·장부가 규칙마다 따로다.
 */
@Component
public class TaLiveStrategy {

    private static final Logger log = LoggerFactory.getLogger(TaLiveStrategy.class);
    static final String SUMMARY_ID = "TA";
    private static final String HORIZON = "SWING";
    /** 하루 판단 마감 — 09:01 예약이든 장중 따라잡기든 이 시각부터는 그날 판단을 하지 않는다(2026-10-21). */
    static final LocalTime JUDGE_DEADLINE = LocalTime.of(15, 0);
    private static final long ACTIVE_RECHECK_MILLIS = 30_000;

    private final TaLiveProperties properties;
    private final ObjectProvider<DailyRuleBook> ruleBooks;
    private final LiveMarket market;
    private final StrategyBook book;
    private final SignalOutbox outbox;
    private final StrategyGuard guard;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    /** (규칙, 종목). */
    record Key(String rule, String symbol) {
    }

    private final Map<Key, BigDecimal> buyStops = new ConcurrentHashMap<>();
    private final Map<Key, BigDecimal> sellStops = new ConcurrentHashMap<>();
    /** 진입과 짝지은 손절 — 매수가 체결돼 보유가 생기면 손절 감시로 옮긴다. */
    private final Map<Key, BigDecimal> pairedStops = new ConcurrentHashMap<>();
    private volatile LocalDate ranDay;
    private volatile boolean active;
    private volatile long activeCheckedAt;

    public TaLiveStrategy(TaLiveProperties properties, ObjectProvider<DailyRuleBook> ruleBooks, LiveMarket market,
                          StrategyBook book, SignalOutbox outbox, StrategyGuard guard,
                          ApplicationEventPublisher publisher, Clock clock) {
        this.properties = properties;
        this.ruleBooks = ruleBooks;
        this.market = market;
        this.book = book;
        this.outbox = outbox;
        this.guard = guard;
        this.publisher = publisher;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        if (!properties.enabled()) {
            return;
        }
        market.register(SUMMARY_ID, properties.symbols());
        market.subscribeTicks(properties.symbols());
        DailyRuleBook rules = ruleBooks.getIfAvailable();
        log.info("{}: 차트 기법 켜짐 — 규칙 {} × {}종목", SUMMARY_ID, rules == null ? "없음(규칙 묶음 빈 없음)" : rules.ruleIds(),
                properties.symbols().size());
    }

    /** 09:01 — 시가 확인 뒤 하루 판단. */
    @Scheduled(cron = "0 1 9 * * MON-FRI", zone = "Asia/Seoul")
    public void run() {
        // 절전 복귀로 09:01 예약이 15:00 뒤에 돌면(10/20 21:50 실측) 그날 판단은 하지 않는다 — 장중 따라잡기와 같은 마감
        if (properties.enabled() && guard.tooLate(SUMMARY_ID + " 09:01 판단", JUDGE_DEADLINE)) {
            return;
        }
        runIfDue("09:01");
    }

    /** 장중 재기동 — 오늘 판단을 아직 안 했으면 1분마다 확인해 한다(15:00까지). */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void catchUp() {
        if (!properties.enabled()) {
            return;
        }
        LocalTime now = guard.now();
        if (now.isBefore(LocalTime.of(9, 1)) || !now.isBefore(JUDGE_DEADLINE)) {
            return;
        }
        if (!guard.today().equals(ranDay)) {
            runIfDue("장중 기동 " + now.withNano(0));
        }
    }

    synchronized void runIfDue(String label) {
        if (!guard.active(properties.enabled())) {
            return;
        }
        LocalDate today = guard.today();
        if (today.equals(ranDay)) {
            return;
        }
        DailyRuleBook rules = ruleBooks.getIfAvailable();
        if (rules == null) {
            log.error("{}: 규칙 묶음(DailyRuleBook) 빈이 없다 — 오늘 판단 못 함", SUMMARY_ID);
            ranDay = today;
            return;
        }
        Result result = execute(rules, today, label);
        log.info(result.line());
        publisher.publishEvent(new StrategyRunCompleted(SUMMARY_ID, today, result.line(), result.errors(),
                clock.instant()));
    }

    /** 하루 판단 결과 — 요약 한 줄과 오류 수. */
    record Result(String line, int errors) {
    }

    /** 하루 판단. */
    Result execute(DailyRuleBook rules, LocalDate today, String label) {
        buyStops.clear();
        sellStops.clear();
        pairedStops.clear();
        int buys = 0;
        int sells = 0;
        int armedBuys = 0;
        int armedStops = 0;
        int holds = 0;
        int shadowOnly = 0;
        int none = 0;
        int errors = 0;
        for (String symbol : properties.symbols()) {
            List<Candle> candles = market.completedBars(symbol);
            BigDecimal open = market.todayOpen(symbol);
            if (candles.size() < properties.minBars() || open == null) {
                errors++;
                log.warn("{}: {} 데이터 부족 — 봉 {}개(최소 {}), 시가 {} — 오늘 이 종목 판단 안 함", SUMMARY_ID,
                        StockNames.label(symbol), candles.size(), properties.minBars(), open);
                continue;
            }
            StockCode code = new StockCode(symbol);
            for (String rule : rules.ruleIds()) {
                try {
                    DailyRuleBook.RulePlan plan = rules.plan(rule, symbol, candles, today, open);
                    Key key = new Key(rule, symbol);
                    Map<String, String> metrics = new LinkedHashMap<>();
                    metrics.put("open", open.toPlainString());
                    metrics.put("lastClose", candles.get(candles.size() - 1).close().toPlainString());
                    metrics.put("shadowHolding", String.valueOf(plan.shadowHolding()));
                    if (plan.shadowAvgPrice() != null) {
                        metrics.put("shadowAvgPrice", plan.shadowAvgPrice().toPlainString());
                    }
                    String name = rules.ruleName(rule);
                    if (book.holds(rule, code)) {
                        if (!plan.shadowHolding()) {
                            sellNow(rule, symbol, name + " — 규칙상 미보유인데 보유 중: 시가 정리", metrics);
                            sells++;
                        } else if (plan.has(DailyRuleBook.RuleOrder.Kind.SELL_AT_OPEN)) {
                            sellNow(rule, symbol, name + " — 규칙 청산(시가)", metrics);
                            sells++;
                        } else {
                            DailyRuleBook.RuleOrder stop = plan.first(DailyRuleBook.RuleOrder.Kind.SELL_STOP);
                            if (stop != null) {
                                sellStops.put(key, stop.price());
                                metrics.put("stopPrice", stop.price().toPlainString());
                                armedStops++;
                            }
                            outbox.decision(rule, HORIZON, symbol, "HOLD",
                                    name + " — 보유 유지" + (stop == null ? "" : ", 장중 손절 " + stop.price().toPlainString()),
                                    metrics);
                            holds++;
                        }
                    } else if (plan.shadowHolding()) {
                        outbox.decision(rule, HORIZON, symbol, "SKIP", name + " — 규칙상 보유 중(지나간 진입): 따라 사지 않음",
                                metrics);
                        shadowOnly++;
                    } else {
                        DailyRuleBook.RuleOrder stop = plan.first(DailyRuleBook.RuleOrder.Kind.SELL_STOP);
                        if (plan.has(DailyRuleBook.RuleOrder.Kind.BUY_AT_OPEN)) {
                            BigDecimal ask = market.bestAsk(symbol);
                            metrics.put("bestAsk", ask == null ? "N/A" : ask.toPlainString());
                            outbox.buy(rule, HORIZON, symbol, ask, 1.0, name + " — 진입(시가)", metrics);
                            buys++;
                            if (stop != null) {
                                pairedStops.put(key, stop.price());
                            }
                        } else if (plan.has(DailyRuleBook.RuleOrder.Kind.BUY_STOP)) {
                            BigDecimal trigger = plan.first(DailyRuleBook.RuleOrder.Kind.BUY_STOP).price();
                            buyStops.put(key, trigger);
                            if (stop != null) {
                                pairedStops.put(key, stop.price());
                            }
                            metrics.put("trigger", trigger.toPlainString());
                            outbox.decision(rule, HORIZON, symbol, "WATCH", name + " — 장중 돌파 감시 " + trigger.toPlainString(),
                                    metrics);
                            armedBuys++;
                        } else {
                            outbox.decision(rule, HORIZON, symbol, "SKIP", name + " — 진입 조건 없음", metrics);
                            none++;
                        }
                    }
                } catch (RuntimeException e) {
                    errors++;
                    log.error("{}: {} {} 판단 오류 — 이 규칙·종목만 건너뜀", SUMMARY_ID, rule, StockNames.label(symbol), e);
                }
            }
        }
        ranDay = today;
        return new Result("%s %s 차트 기법 5종(%s) — 시가 매수 %d · 시가 매도 %d · 장중 돌파 감시 %d · 손절 감시 %d · 보유 유지 %d · 규칙상 보유(따라 사지 않음) %d · 조건 없음 %d · 오류 %d"
                .formatted(SUMMARY_ID, today, label, buys, sells, armedBuys, armedStops, holds, shadowOnly, none, errors),
                errors);
    }

    /** 실시간 체결 — 장중 돌파 매수·손절 감시. */
    @EventListener
    public void onTick(MarketTick tick) {
        if (!properties.enabled() || tick.price() == null
                || (buyStops.isEmpty() && sellStops.isEmpty() && pairedStops.isEmpty())) {
            return;
        }
        String symbol = tick.symbol().value();
        if (!properties.symbols().contains(symbol) || !isActive()) {
            return;
        }
        LocalTime now = guard.now();
        if (now.isBefore(LocalTime.of(9, 0)) || !now.isBefore(LocalTime.of(15, 20))) {
            return;
        }
        BigDecimal price = tick.price().value();
        StockCode code = tick.symbol();
        for (Key key : List.copyOf(pairedStops.keySet())) {
            if (key.symbol().equals(symbol) && book.holds(key.rule(), code)) {
                BigDecimal stop = pairedStops.remove(key);
                if (stop != null) {
                    sellStops.put(key, stop);
                }
            }
        }
        for (Key key : List.copyOf(buyStops.keySet())) {
            if (!key.symbol().equals(symbol)) {
                continue;
            }
            BigDecimal trigger = buyStops.get(key);
            if (trigger != null && price.compareTo(trigger) >= 0 && !book.holds(key.rule(), code)
                    && buyStops.remove(key) != null) {
                log.info("{}: 장중 돌파 매수 {} {} — 체결가 {} ≥ {}", SUMMARY_ID, key.rule(), StockNames.label(symbol),
                        price.toPlainString(), trigger.toPlainString());
                outbox.buy(key.rule(), HORIZON, symbol, SignalOutbox.times(price, 1 + properties.buySlack()), 1.0,
                        "장중 돌파 — 체결가 " + price.toPlainString() + " ≥ " + trigger.toPlainString(),
                        Map.of("trigger", trigger.toPlainString(), "price", price.toPlainString()));
            }
        }
        for (Key key : List.copyOf(sellStops.keySet())) {
            if (!key.symbol().equals(symbol)) {
                continue;
            }
            BigDecimal stop = sellStops.get(key);
            if (stop != null && price.compareTo(stop) <= 0 && book.holds(key.rule(), code)
                    && sellStops.remove(key) != null) {
                log.info("{}: 장중 손절 {} {} — 체결가 {} ≤ {}", SUMMARY_ID, key.rule(), StockNames.label(symbol),
                        price.toPlainString(), stop.toPlainString());
                outbox.sell(key.rule(), HORIZON, symbol, SignalOutbox.times(price, 1 - properties.sellSlack()),
                        "장중 손절 — 체결가 " + price.toPlainString() + " ≤ " + stop.toPlainString(),
                        Map.of("stop", stop.toPlainString(), "price", price.toPlainString()));
            }
        }
    }

    private void sellNow(String rule, String symbol, String reason, Map<String, String> metrics) {
        BigDecimal bid = market.bestBid(symbol);
        metrics.put("bestBid", bid == null ? "N/A" : bid.toPlainString());
        outbox.sell(rule, HORIZON, symbol, SignalOutbox.times(bid, 1 - properties.sellSlack()), reason, metrics);
    }

    private boolean isActive() {
        long nowMs = clock.millis();
        if (nowMs - activeCheckedAt > ACTIVE_RECHECK_MILLIS) {
            active = guard.active(properties.enabled());
            activeCheckedAt = nowMs;
        }
        return active;
    }

    /** 시험용. */
    Map<Key, BigDecimal> buyStops() {
        return buyStops;
    }

    Map<Key, BigDecimal> sellStops() {
        return sellStops;
    }

    Map<Key, BigDecimal> pairedStops() {
        return pairedStops;
    }
}
