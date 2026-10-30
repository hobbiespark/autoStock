package com.autostock.strategy;

import com.autostock.common.event.Candle;
import com.autostock.common.event.Fill;
import com.autostock.common.event.Side;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 단타 → 스윙·중기 이월과 3분할 추가 매수(2026-10-30, aiDoc/split-buy-swing-carry-2026-10-30.md).
 *
 * <p>사용자 지시: "단타가 스윙, 중, 장기 투자로 이어진다는 생각으로 3분할 매수", ON-CLOSE 갭 하락 종목은 "오히려 스윙으로
 * 갔어야". 그래서
 * <ul>
 *   <li>단타(VB-DAY 15:15 시간 청산, ON-CLOSE 09:00:30 아침 매도)가 비용 후 수익이 아니면 팔지 않고 {@link #carry}로 넘긴다.</li>
 *   <li>매 거래일 09:02 — 넘긴 보유를 어제까지 완료된 일봉으로 판단한다({@link SwingCarryMath#decide}): 2·3차 추가 매수,
 *       손절·스윙 익절·중기 추세 이탈·기간 만료 매도. 이월 당일에는 판단하지 않는다(갭 하락 아침에 바로 더 사지 않게).</li>
 *   <li>추가 매수 체결이 오면 차수를 하나 올린다(같은 날 부분 체결은 한 번). 3차면 중기 단계.</li>
 * </ul>
 * 수량·평단은 전략 장부({@link StrategyBook})가 원본이다. 장부에 보유가 없어지면 다음 판단에서 상태를 지운다.
 */
@Component
public class SwingCarryManager {

    private static final Logger log = LoggerFactory.getLogger(SwingCarryManager.class);
    static final String JOB = "CARRY";
    /** 09:02 판단이 이 시각 뒤에 돌면(절전 복귀로 늦게 깨어남) 그날은 건너뛴다. */
    static final LocalTime EVALUATE_DEADLINE = LocalTime.of(15, 0);

    private final SwingCarryProperties properties;
    private final StrategyCarryRepository repository;
    private final StrategyBook book;
    private final LiveMarket market;
    private final SignalOutbox outbox;
    private final StrategyGuard guard;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    private final Map<String, StrategyCarryEntity> carried = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    public SwingCarryManager(SwingCarryProperties properties, StrategyCarryRepository repository, StrategyBook book,
                             LiveMarket market, SignalOutbox outbox, StrategyGuard guard,
                             ApplicationEventPublisher publisher, Clock clock) {
        this.properties = properties;
        this.repository = repository;
        this.book = book;
        this.market = market;
        this.outbox = outbox;
        this.guard = guard;
        this.publisher = publisher;
        this.clock = clock;
    }

    /** 기동 — 이월 상태를 읽고 한 줄 남긴다. */
    @EventListener(ApplicationReadyEvent.class)
    public void loadOnStartup() {
        ensureLoaded();
        if (properties.enabled()) {
            log.info("{}: 단타 → 스윙·중기 이월 켜짐 — {} · 3분할(2차 종가>SMA{}, 3차 종가>SMA{}·SMA{}>SMA{}) · 손절 −{}% · 스윙 익절 +{}%"
                            + " · 스윙 최대 {}거래일 · 중기 {} · 이월 중 {}종목", JOB, properties.strategies(),
                    properties.trendSmaDays(), properties.midSmaDays(), properties.trendSmaDays(), properties.midSmaDays(),
                    pct(properties.hardStop()), pct(properties.swingTakeProfit()), properties.swingMaxDays(),
                    properties.midMaxDays() == 0 ? "무기한(추세 이탈까지)" : properties.midMaxDays() + "거래일", carried.size());
        }
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (this) {
            if (loaded) {
                return;
            }
            repository.findAll().forEach(e -> carried.put(key(e.getStrategyId(), e.getSymbol()), e));
            loaded = true;
            registerSymbols();
        }
    }

    /** 이 전략이 이월 대상인가(설정). 단타 전략이 청산 시각에 팔지 넘길지 정할 때 본다. */
    public boolean carries(String strategyId) {
        return properties.carries(strategyId);
    }

    /** 왕복 비용 — 단타 청산 시각의 "비용 후 수익" 판단용. */
    public double roundTripCost() {
        return properties.roundTripCost();
    }

    /** 넘겨받은 보유인가 — 장부에 보유가 없으면 아니다(이미 팔렸다). 단타 전략은 이월 보유를 직접 사고팔지 않는다. */
    public boolean isCarried(String strategyId, StockCode symbol) {
        ensureLoaded();
        return carried.containsKey(key(strategyId, symbol.value())) && book.holds(norm(strategyId), symbol);
    }

    /** 이 전략의 이월 보유 종목. */
    public Set<StockCode> carriedSymbols(String strategyId) {
        ensureLoaded();
        String prefix = norm(strategyId) + "|";
        return carried.keySet().stream().filter(k -> k.startsWith(prefix))
                .map(k -> new StockCode(k.substring(prefix.length())))
                .filter(code -> book.holds(norm(strategyId), code))
                .collect(Collectors.toSet());
    }

    /**
     * 단타 보유를 스윙으로 넘긴다(1차 보유, 스윙 단계, 이월일 = 오늘). 이미 넘긴 보유면 그대로 둔다.
     *
     * @return 새로 넘겼으면 true
     */
    public synchronized boolean carry(String strategyId, StockCode symbol, BigDecimal price, BigDecimal avgPrice,
                                      String horizon, String reason) {
        ensureLoaded();
        String k = key(strategyId, symbol.value());
        if (carried.containsKey(k)) {
            return false;
        }
        StrategyCarryEntity entity = repository.save(
                new StrategyCarryEntity(norm(strategyId), symbol.value(), guard.today(), clock.instant()));
        carried.put(k, entity);
        registerSymbols();
        Map<String, String> metrics = new LinkedHashMap<>();
        metrics.put("price", price == null ? "N/A" : price.toPlainString());
        metrics.put("avgPrice", avgPrice == null ? "N/A" : avgPrice.toPlainString());
        metrics.put("roundTripCost", String.valueOf(properties.roundTripCost()));
        log.info("{}: 스윙 이월 — {} {} · {}", norm(strategyId), StockNames.label(symbol), reason,
                "가격 %s / 평단 %s".formatted(metrics.get("price"), metrics.get("avgPrice")));
        outbox.decision(norm(strategyId), horizon, symbol.value(), "CARRY", "스윙 이월 — " + reason, metrics);
        return true;
    }

    /** 추가 매수 체결 — 이월 보유의 매수 체결이면 차수를 올린다. */
    @EventListener
    public void onFill(Fill fill) {
        if (fill.side() != Side.BUY) {
            return;
        }
        String strategyId = StrategyBook.strategyIdOf(fill.orderIdempotencyKey());
        if (strategyId == null || !properties.carries(strategyId)) {
            return;
        }
        ensureLoaded();
        StrategyCarryEntity entity = carried.get(key(strategyId, fill.symbol().value()));
        if (entity == null) {
            return;
        }
        synchronized (this) {
            if (entity.added(guard.today(), clock.instant())) {
                carried.put(key(strategyId, fill.symbol().value()), repository.save(entity));
                log.info("{}: {}차 분할 매수 체결 — {} (단계 {})", strategyId, entity.getTranches(),
                        StockNames.label(fill.symbol()), entity.getStage());
            }
        }
    }

    /** 매 거래일 09:02 — 이월 보유 판단(추가 매수·청산). */
    @Scheduled(cron = "0 2 9 * * MON-FRI", zone = "Asia/Seoul")
    public void evaluate() {
        if (!guard.active(properties.enabled()) || guard.tooLate(JOB + " 09:02 이월 판단", EVALUATE_DEADLINE)) {
            return;
        }
        String line = evaluateToday();
        if (line != null) {
            log.info(line);
            publisher.publishEvent(new StrategyRunCompleted(JOB, guard.today(), line, 0, clock.instant()));
        }
    }

    /** 오늘 판단을 하고 한 줄 요약을 돌려준다(이월 보유가 없으면 null). */
    String evaluateToday() {
        ensureLoaded();
        LocalDate today = guard.today();
        List<String> parts = new ArrayList<>();
        int adds = 0;
        int sells = 0;
        for (StrategyCarryEntity entity : List.copyOf(carried.values())) {
            StockCode code = new StockCode(entity.getSymbol());
            Optional<PositionBook.Position> position = book.position(entity.getStrategyId(), code);
            if (position.isEmpty() || position.get().quantity() <= 0) {
                remove(entity, "보유 없음(팔림) — 이월 끝");
                continue;
            }
            if (!entity.getCarriedOn().isBefore(today)) {
                parts.add("%s %s 이월 당일 — 판단 안 함".formatted(entity.getStrategyId(), StockNames.label(code)));
                continue;
            }
            List<Candle> bars = market.completedBars(entity.getSymbol());
            long heldDays = bars.stream().filter(b -> b.date().isAfter(entity.getCarriedOn())).count();
            SwingCarryMath.Decision decision = SwingCarryMath.decide(entity.getStage(), entity.getTranches(),
                    position.get().avgPrice(), bars.stream().map(Candle::close).toList(), heldDays, properties.rules());
            String horizon = entity.getStage() == SwingCarryMath.Stage.MID ? "MID" : "SWING";
            Map<String, String> metrics = new LinkedHashMap<>();
            metrics.put("stage", entity.getStage().name());
            metrics.put("tranches", String.valueOf(entity.getTranches()));
            metrics.put("carriedOn", entity.getCarriedOn().toString());
            metrics.put("quantity", String.valueOf(position.get().quantity()));
            switch (decision.action()) {
                case SELL -> {
                    sells++;
                    BigDecimal bid = market.bestBid(entity.getSymbol());
                    metrics.put("bestBid", bid == null ? "N/A" : bid.toPlainString());
                    outbox.sell(entity.getStrategyId(), horizon, entity.getSymbol(),
                            SignalOutbox.times(bid, 1 - properties.sellSlack()), bid, decision.reason(), metrics);
                }
                case ADD -> {
                    adds++;
                    BigDecimal ask = market.bestAsk(entity.getSymbol());
                    metrics.put("bestAsk", ask == null ? "N/A" : ask.toPlainString());
                    outbox.buyTranche(entity.getStrategyId(), horizon, entity.getSymbol(),
                            SignalOutbox.times(ask, 1 + properties.buySlack()), ask, decision.tranche(), decision.reason(),
                            metrics);
                }
                case HOLD -> outbox.decision(entity.getStrategyId(), horizon, entity.getSymbol(), "HOLD", decision.reason(),
                        metrics);
            }
            parts.add("%s %s %s".formatted(entity.getStrategyId(), StockNames.label(code), decision.reason()));
        }
        if (parts.isEmpty()) {
            return null;
        }
        return "%s %s 이월 판단 %d종목 — 추가 매수 %d · 매도 %d — %s".formatted(JOB, today, parts.size(), adds, sells,
                String.join(" · ", parts));
    }

    private synchronized void remove(StrategyCarryEntity entity, String reason) {
        carried.remove(key(entity.getStrategyId(), entity.getSymbol()));
        repository.delete(entity);
        registerSymbols();
        log.info("{}: {} {}", entity.getStrategyId(), StockNames.label(entity.getSymbol()), reason);
    }

    /** 이월 종목을 시세 미리 받기 대상(08:45 일봉)에 올린다. */
    private void registerSymbols() {
        market.register(JOB, carried.values().stream().map(StrategyCarryEntity::getSymbol).toList());
    }

    private static String key(String strategyId, String symbol) {
        return norm(strategyId) + "|" + symbol;
    }

    private static String norm(String strategyId) {
        return strategyId == null ? "" : strategyId.toUpperCase(Locale.ROOT);
    }

    private static String pct(double v) {
        return BigDecimal.valueOf(v).movePointRight(2).stripTrailingZeros().toPlainString();
    }
}
