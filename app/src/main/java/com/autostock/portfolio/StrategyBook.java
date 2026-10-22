package com.autostock.portfolio;

import com.autostock.common.event.Fill;
import com.autostock.common.event.Side;
import com.autostock.common.util.ClientOrderId;
import com.autostock.common.util.MarketConstants;
import com.autostock.common.util.StockCode;
import com.autostock.common.util.StockNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 전략별 장부 — "어느 전략이 어느 종목을 몇 주, 평균 얼마에 들고 있나"(2026-10-16, aiDoc/multi-strategy-paper-2026-10-16.md 2절).
 *
 * <p>모의투자에서 여러 전략(C3·단타·종가→익일·차트 5종)이 한 계좌로 함께 매매한다. 계좌 장부({@link PositionBook})는
 * 종목 단위라 같은 종목을 두 전략이 따로 들면 구분하지 못한다. 이 장부는 체결({@link Fill})의 주문번호
 * ({@link ClientOrderId} — "20261019-VB-DAY-005930-BUY-003")에 든 전략 ID로 (전략, 종목)별 보유를 센다.
 * RiskGate는 슬리브가 있는 전략의 매수 중복·동시 보유·매도 수량을 이 장부로 판단한다.
 *
 * <p>영속: 보유는 {@code strategy_positions}, 체결은 {@code strategy_fills}(V20). 재기동하면 보유를 표에서 읽는다
 * (기동 직후 한 번, 실패하면 다음 조회 때 다시). 저장이 실패해도 메모리 장부는 갱신한다 — 매매 판단은 메모리가 우선이고,
 * 저장 실패는 ERROR로 남긴다(재기동 전에 확인할 일).
 *
 * <p>계좌 장부와의 관계: 계좌 수량 = 전략별 수량의 합(+ 전략 밖 수동 주문)이어야 한다. 대시보드 수동 매도는 계좌 수량 전체를
 * 팔기 때문에 이 장부와 어긋날 수 있다 — 모의 실측 중에는 수동 매도를 피한다(문서 5절).
 */
@Component
public class StrategyBook {

    private static final Logger log = LoggerFactory.getLogger(StrategyBook.class);

    /** 한 (전략, 종목)의 보유. {@code avgPrice}가 null이면 평단 모름. */
    private record Holding(long quantity, BigDecimal avgPrice, Instant openedAt) {
    }

    /**
     * 청산 중인 (전략, 종목)의 매도 누계(2026-10-22) — 부분 체결이 수십 건이라(10/22 09:00 ON-CLOSE 3종목 매도 체결 48건)
     * 체결마다 INFO를 남기면 로그가 묻힌다. 체결마다는 DEBUG, 청산이 끝나면 INFO 한 줄. 메모리에만 둔다(재기동하면 그 뒤 체결부터).
     */
    private static final class ExitTally {
        long quantity;
        BigDecimal proceeds = BigDecimal.ZERO;
        BigDecimal realized = BigDecimal.ZERO;
        int fills;
    }

    private final Map<String, ExitTally> exits = new HashMap<>();

    /**
     * 하루 전략별 요약 — 일일 리포트 한 줄.
     *
     * @param realizedPnl 매도 실현손익 합(추정 비용 차감). 평단을 몰라 못 센 매도는 빠진다
     * @param wins        그날 매도 실현손익 합이 0보다 큰 종목 수 — 2026-10-22부터 종목 단위(예전엔 부분 체결마다 세서
     *                    10/22 ON-CLOSE 3종목 청산이 "손실 48"로 보였다)
     * @param losses      그날 매도 실현손익 합이 0 이하인 종목 수
     */
    public record DaySummary(String strategyId, int buyFills, int sellFills, BigDecimal realizedPnl, int wins,
                             int losses, int openPositions) {
    }

    private final Map<String, Map<StockCode, Holding>> books = new HashMap<>();
    private final StrategyPositionRepository positionRepository;
    private final StrategyFillRepository fillRepository;
    private final Clock clock;
    private final BigDecimal feeRate;
    private final BigDecimal sellTaxRate;
    private boolean loaded;

    public StrategyBook(StrategyPositionRepository positionRepository, StrategyFillRepository fillRepository,
                        Clock clock,
                        @Value("${risk.fee-rate:0.00015}") double feeRate,
                        @Value("${risk.sell-tax-rate:0.0020}") double sellTaxRate) {
        this.positionRepository = positionRepository;
        this.fillRepository = fillRepository;
        this.clock = clock;
        this.feeRate = BigDecimal.valueOf(feeRate);
        this.sellTaxRate = BigDecimal.valueOf(sellTaxRate);
    }

    /** 기동 직후 보유를 표에서 읽는다(실패하면 다음 조회 때 다시). */
    @EventListener(ApplicationReadyEvent.class)
    public void loadOnStartup() {
        synchronized (this) {
            ensureLoaded();
        }
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        try {
            List<StrategyPositionEntity> rows = positionRepository.findAll();
            Map<String, Map<StockCode, Holding>> restored = new HashMap<>();
            for (StrategyPositionEntity row : rows) {
                restored.computeIfAbsent(row.getStrategyId(), k -> new HashMap<>())
                        .put(new StockCode(row.getSymbol()),
                                new Holding(row.getQuantity(), row.getAvgPrice(), row.getOpenedAt()));
            }
            // 이 JVM에서 이미 체결로 만든 보유가 있으면(표 읽기 전 체결) 그쪽이 최신이다
            restored.forEach((strategy, book) -> book.forEach((symbol, holding) ->
                    books.computeIfAbsent(strategy, k -> new HashMap<>()).putIfAbsent(symbol, holding)));
            loaded = true;
            if (!rows.isEmpty()) {
                log.info("전략별 장부 복원 — {}", describe(books));
            }
        } catch (RuntimeException e) {
            log.warn("전략별 장부 복원 실패 — 다음 조회 때 다시 읽는다: {}", e.toString());
        }
    }

    /**
     * 체결 반영. 주문번호에서 전략 ID를 읽지 못하면(외부 주문 등) 무시한다.
     */
    @EventListener
    public synchronized void onFill(Fill fill) {
        ensureLoaded();
        String strategyId = strategyIdOf(fill.orderIdempotencyKey());
        if (strategyId == null) {
            log.debug("전략 ID 없는 체결 — 전략별 장부에 넣지 않음: {}", fill.orderIdempotencyKey());
            return;
        }
        StockCode symbol = fill.symbol();
        long qty = fill.filledQuantity().value();
        BigDecimal price = fill.fillPrice().value();
        Instant at = fill.timestamp() == null ? clock.instant() : fill.timestamp();
        Map<StockCode, Holding> book = books.computeIfAbsent(strategyId, k -> new HashMap<>());
        Holding current = book.get(symbol);
        BigDecimal avgBefore = current == null ? null : current.avgPrice();
        BigDecimal realized = null;
        Holding next;
        if (fill.side() == Side.BUY) {
            if (current == null) {
                next = new Holding(qty, price, at);
            } else if (current.avgPrice() == null) {
                next = new Holding(current.quantity() + qty, null, current.openedAt());
            } else {
                long newQty = current.quantity() + qty;
                BigDecimal cost = current.avgPrice().multiply(BigDecimal.valueOf(current.quantity()))
                        .add(price.multiply(BigDecimal.valueOf(qty)));
                next = new Holding(newQty, cost.divide(BigDecimal.valueOf(newQty), 4, RoundingMode.HALF_UP),
                        current.openedAt());
            }
            avgBefore = null;
        } else {
            if (current == null) {
                log.warn("전략별 장부: {} {} 매도 체결 {}주 — 이 전략 보유 없음(수동 주문·장부 어긋남). 기록만 남긴다",
                        strategyId, StockNames.label(symbol), qty);
                next = null;
            } else {
                long sold = Math.min(qty, current.quantity());
                if (sold < qty) {
                    log.warn("전략별 장부: {} {} 매도 체결 {}주 > 장부 {}주 — 장부 수량까지만 줄인다",
                            strategyId, StockNames.label(symbol), qty, current.quantity());
                }
                realized = realizedPnl(current.avgPrice(), price, sold);
                long remain = current.quantity() - sold;
                next = remain > 0 ? new Holding(remain, current.avgPrice(), current.openedAt()) : null;
            }
        }
        if (next == null) {
            book.remove(symbol);
        } else {
            book.put(symbol, next);
        }
        persist(strategyId, symbol, next, new StrategyFillEntity(strategyId, symbol.value(), fill.side().name(), qty,
                price, fill.orderIdempotencyKey(), avgBefore, realized, at));
        if (fill.side() == Side.SELL && realized != null) {
            log.debug("전략별 장부: {} {} 매도 {}주 @ {} — 평단 {}, 실현손익 {}원(추정 비용 차감){}", strategyId,
                    StockNames.label(symbol), qty, price.toPlainString(), avgBefore.toPlainString(),
                    realized.toPlainString(), next == null ? ", 청산" : ", 남은 " + next.quantity() + "주");
            String key = strategyId + "|" + symbol.value();
            ExitTally tally = exits.computeIfAbsent(key, k -> new ExitTally());
            tally.quantity += qty;
            tally.proceeds = tally.proceeds.add(price.multiply(BigDecimal.valueOf(qty)));
            tally.realized = tally.realized.add(realized);
            tally.fills++;
            if (next == null) {
                exits.remove(key);
                BigDecimal avgSell = tally.proceeds.divide(BigDecimal.valueOf(tally.quantity), 0, RoundingMode.HALF_UP);
                BigDecimal pct = avgSell.subtract(avgBefore).multiply(BigDecimal.valueOf(100))
                        .divide(avgBefore, 2, RoundingMode.HALF_UP);
                log.info("전략별 장부: {} {} 청산 {}주 — 평단 {}원 → 평균 매도가 {}원({}%), 매도 금액 {}원, 실현손익 {}원"
                                + "(추정 비용 차감, 체결 {}건)",
                        strategyId, StockNames.label(symbol), tally.quantity,
                        "%,d".formatted(avgBefore.setScale(0, RoundingMode.HALF_UP).longValue()),
                        "%,d".formatted(avgSell.longValue()), (pct.signum() > 0 ? "+" : "") + pct.toPlainString(),
                        "%,d".formatted(tally.proceeds.setScale(0, RoundingMode.HALF_UP).longValue()),
                        "%,d".formatted(tally.realized.setScale(0, RoundingMode.HALF_UP).longValue()), tally.fills);
            }
        } else if (fill.side() == Side.BUY && current == null) {
            exits.remove(strategyId + "|" + symbol.value());   // 새 보유 — 지난 청산 누계가 남아 있으면 버린다
        }
    }

    /** (체결가 − 평단) × 수량 − 매수 수수료 − 매도 수수료 − 거래세. 평단을 모르면 null. */
    BigDecimal realizedPnl(BigDecimal avgPrice, BigDecimal price, long qty) {
        if (avgPrice == null || qty <= 0) {
            return null;
        }
        BigDecimal q = BigDecimal.valueOf(qty);
        BigDecimal buyNotional = avgPrice.multiply(q);
        BigDecimal sellNotional = price.multiply(q);
        BigDecimal costs = buyNotional.multiply(feeRate)
                .add(sellNotional.multiply(feeRate))
                .add(sellNotional.multiply(sellTaxRate));
        return sellNotional.subtract(buyNotional).subtract(costs).setScale(2, RoundingMode.HALF_UP);
    }

    private void persist(String strategyId, StockCode symbol, Holding next, StrategyFillEntity fillRow) {
        try {
            if (next == null) {
                positionRepository.deleteById(new StrategyPositionEntity.Key(strategyId, symbol.value()));
            } else {
                positionRepository.save(new StrategyPositionEntity(strategyId, symbol.value(), next.quantity(),
                        next.avgPrice(), next.openedAt(), clock.instant()));
            }
            fillRepository.save(fillRow);
        } catch (RuntimeException e) {
            log.error("전략별 장부 저장 실패({} {}) — 메모리 장부는 갱신했다. 재기동 전 확인 필요", strategyId,
                    StockNames.label(symbol), e);
        }
    }

    /** ClientOrderId에서 전략 ID — 형식이 아니면 null. */
    static String strategyIdOf(String clientOrderId) {
        if (clientOrderId == null) {
            return null;
        }
        try {
            return ClientOrderId.parse(clientOrderId).strategyId();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** @return 이 전략의 보유(없으면 빈 값) */
    public synchronized Optional<PositionBook.Position> position(String strategyId, StockCode symbol) {
        ensureLoaded();
        Map<StockCode, Holding> book = books.get(strategyId);
        Holding holding = book == null ? null : book.get(symbol);
        return holding == null ? Optional.empty()
                : Optional.of(new PositionBook.Position(holding.quantity(), holding.avgPrice()));
    }

    public boolean holds(String strategyId, StockCode symbol) {
        return position(strategyId, symbol).isPresent();
    }

    /** 이 전략이 들고 있는 종목 수. */
    public synchronized int openPositionCount(String strategyId) {
        ensureLoaded();
        Map<StockCode, Holding> book = books.get(strategyId);
        return book == null ? 0 : book.size();
    }

    /** 이 전략의 보유 스냅샷(종목코드 순). */
    public synchronized Map<StockCode, PositionBook.Position> positions(String strategyId) {
        ensureLoaded();
        Map<StockCode, Holding> book = books.getOrDefault(strategyId, Map.of());
        Map<StockCode, PositionBook.Position> out = new TreeMap<>(java.util.Comparator.comparing(StockCode::value));
        book.forEach((symbol, h) -> out.put(symbol, new PositionBook.Position(h.quantity(), h.avgPrice())));
        return out;
    }

    /** 이 종목을 들고 있는 전략과 수량(전략 ID 순). */
    public synchronized Map<String, Long> holdersOf(StockCode symbol) {
        ensureLoaded();
        Map<String, Long> out = new TreeMap<>();
        books.forEach((strategy, book) -> {
            Holding h = book.get(symbol);
            if (h != null) {
                out.put(strategy, h.quantity());
            }
        });
        return out;
    }

    /**
     * 그날(KST) 전략별 요약 — 체결 표에서 센다. 체결이 없어도 보유가 있는 전략은 넣는다.
     */
    public List<DaySummary> daySummaries(LocalDate day) {
        return summaries(day, day);
    }

    /**
     * 기간(KST, 양 끝 포함) 전략별 요약(2026-10-22 — 운용 자금 뒤 성과를 다시 재는 누적 줄). 이익·손실은 (종목, 매도한 날)마다
     * 센다 — 같은 종목을 다른 날 다시 사고팔면 따로 센다.
     */
    public List<DaySummary> summaries(LocalDate fromDay, LocalDate toDayInclusive) {
        Instant from = fromDay.atStartOfDay(MarketConstants.KST).toInstant();
        Instant to = toDayInclusive.plusDays(1).atStartOfDay(MarketConstants.KST).toInstant();
        List<StrategyFillEntity> rows = fillRepository.findByFilledAtGreaterThanEqualAndFilledAtLessThan(from, to);
        Map<String, int[]> counts = new TreeMap<>();          // buys, sells, wins, losses
        Map<String, BigDecimal> pnl = new HashMap<>();
        Map<String, Map<String, BigDecimal>> bySymbol = new HashMap<>();   // 전략 → (종목|매도일) → 실현손익 합
        for (StrategyFillEntity row : rows) {
            int[] c = counts.computeIfAbsent(row.getStrategyId(), k -> new int[4]);
            if ("BUY".equals(row.getSide())) {
                c[0]++;
            } else {
                c[1]++;
                if (row.getRealizedPnl() != null) {
                    pnl.merge(row.getStrategyId(), row.getRealizedPnl(), BigDecimal::add);
                    String exitDay = row.getFilledAt() == null ? ""
                            : LocalDate.ofInstant(row.getFilledAt(), MarketConstants.KST).toString();
                    bySymbol.computeIfAbsent(row.getStrategyId(), k -> new HashMap<>())
                            .merge(row.getSymbol() + "|" + exitDay, row.getRealizedPnl(), BigDecimal::add);
                }
            }
        }
        bySymbol.forEach((strategy, symbols) -> symbols.values().forEach(sum -> {
            int[] c = counts.get(strategy);
            if (sum.signum() > 0) {
                c[2]++;
            } else {
                c[3]++;
            }
        }));
        Map<String, Integer> open = new HashMap<>();
        synchronized (this) {
            ensureLoaded();
            books.forEach((strategy, book) -> {
                if (!book.isEmpty()) {
                    open.put(strategy, book.size());
                    counts.computeIfAbsent(strategy, k -> new int[4]);
                }
            });
        }
        List<DaySummary> out = new ArrayList<>();
        counts.forEach((strategy, c) -> out.add(new DaySummary(strategy, c[0], c[1],
                pnl.getOrDefault(strategy, BigDecimal.ZERO), c[2], c[3], open.getOrDefault(strategy, 0))));
        return out;
    }

    private static String describe(Map<String, Map<StockCode, Holding>> books) {
        Map<String, String> parts = new LinkedHashMap<>();
        new TreeMap<>(books).forEach((strategy, book) -> {
            if (!book.isEmpty()) {
                List<String> items = new ArrayList<>();
                book.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey(java.util.Comparator.comparing(StockCode::value)))
                        .forEach(e -> items.add(StockNames.label(e.getKey()) + " " + e.getValue().quantity() + "주"));
                parts.put(strategy, String.join(", ", items));
            }
        });
        if (parts.isEmpty()) {
            return "보유 없음";
        }
        List<String> lines = new ArrayList<>();
        parts.forEach((strategy, items) -> lines.add(strategy + "(" + items + ")"));
        return String.join(" · ", lines);
    }
}
