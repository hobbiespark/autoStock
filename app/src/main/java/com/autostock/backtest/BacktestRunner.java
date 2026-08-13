package com.autostock.backtest;

import com.autostock.common.event.Fill;
import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.Side;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 단일 종목 캔들 목록을 순서대로 재생하며 전략을 실행하는 백테스트 루프.
 *
 * <p>스프링 컨텍스트가 필요 없는 순수 자바 클래스다 — 백테스트는 짧은 주기로
 * 반복 실행(파라미터 스윕, walk-forward 등)되므로 빈 컨텍스트를 매번 띄우는 비용을
 * 피하고 싶기 때문이다. 라이브 매매와 공유하는 부분은 이벤트 스키마({@link OrderRequest},
 * {@link Fill})와 전략/리스크 "로직"이지, 스프링 배선 자체가 아니다.
 *
 * <h2>룩어헤드(lookahead) 방지 — 왜 "당일 종가 신호 → 익일 시가 체결"인가?</h2>
 * 전략은 그날 캔들이 "확정된 뒤"(장 마감 후) 판단을 내린다고 가정한다. 그런데 만약
 * 그 판단을 같은 날 종가로 즉시 체결시켜 버리면, 실제로는 아직 오지 않은 미래의
 * 가격(그날 종가)으로 주문이 체결된 셈이 된다 — 라이브에서는 있을 수 없는 일이다
 * (장 마감 후 신호가 나왔는데 마감 전 가격에 살 수는 없다). 그래서 이 러너는
 * "N일차 캔들로 내린 판단 → N+1일차 시가에 체결"로 하루를 반드시 지연시킨다.
 * 이 지연을 빼먹는 것이 백테스트에서 가장 흔하고 치명적인 실수다(비현실적으로
 * 좋은 성과가 나온다).
 *
 * <h2>포지션 모델</h2>
 * 전량 매수(가용 현금을 모두 투입) / 전량 청산(보유 수량을 모두 매도) 단순 모델이다.
 * TODO(Phase 4): risk 모듈의 {@code PositionSizer}(고정비율 사이징)를 그대로 재사용해
 * "전량"이 아니라 실제 운영과 동일한 사이징 규칙을 적용한다 — 지금은 코어 로직(비용모델,
 * 룩어헤드 방지, 성과지표) 검증이 우선이라 사이징은 의도적으로 단순화했다.
 */
public final class BacktestRunner {

    private final CostModel costModel;
    private final BacktestExecutionHandler executionHandler;
    private final PerformanceCalculator performanceCalculator;

    public BacktestRunner(CostModel costModel) {
        this.costModel = costModel;
        this.executionHandler = new BacktestExecutionHandler(costModel);
        this.performanceCalculator = new PerformanceCalculator();
    }

    /** 다중검정(DSR) 보정이 필요 없는 단일 시도 백테스트용 편의 메서드. */
    public BacktestResult run(List<Candle> candles, BacktestStrategy strategy, BigDecimal initialCapital) {
        return run(candles, strategy, initialCapital, 1, 0.0);
    }

    /**
     * @param candles        시간순으로 정렬된 단일 종목 캔들 목록
     * @param strategy       매수/매도 판단 함수
     * @param initialCapital 초기 자본
     * @param trials         DSR 계산용 시도 횟수 (파라미터 스윕 등에서 몇 개를 테스트했는지)
     * @param trialsVariance DSR 계산용 시도 간 샤프비율 분산
     */
    public BacktestResult run(List<Candle> candles, BacktestStrategy strategy, BigDecimal initialCapital,
                               int trials, double trialsVariance) {
        List<Double> dailyReturns = new ArrayList<>();

        BigDecimal cash = initialCapital;
        long positionQty = 0;
        BigDecimal avgPrice = BigDecimal.ZERO;
        int tradeCount = 0;
        Optional<Side> pendingDecision = Optional.empty();
        BigDecimal prevEquity = initialCapital;

        for (Candle today : candles) {
            // ── 1) 어제 종가 기준으로 내린 판단을, 오늘 "시가"에 체결한다 (룩어헤드 방지) ──
            if (pendingDecision.isPresent()) {
                Side side = pendingDecision.get();
                if (side == Side.BUY && positionQty == 0) {
                    BigDecimal execPrice = costModel.slippageAdjustedBuyPrice(today.open());
                    long qty = affordableQuantity(cash, execPrice);
                    if (qty > 0) {
                        OrderRequest order = buildOrder(today, Side.BUY, qty);
                        Fill fill = executionHandler.execute(order, today.open());
                        BigDecimal notional = fill.fillPrice().multiply(BigDecimal.valueOf(fill.filledQuantity()));
                        BigDecimal fee = costModel.buyFee(notional);
                        cash = cash.subtract(notional).subtract(fee);
                        positionQty = qty;
                        avgPrice = fill.fillPrice();
                        tradeCount++;
                    }
                } else if (side == Side.SELL && positionQty > 0) {
                    OrderRequest order = buildOrder(today, Side.SELL, positionQty);
                    Fill fill = executionHandler.execute(order, today.open());
                    BigDecimal notional = fill.fillPrice().multiply(BigDecimal.valueOf(fill.filledQuantity()));
                    BigDecimal fee = costModel.sellFee(notional);
                    cash = cash.add(notional).subtract(fee);
                    positionQty = 0;
                    avgPrice = BigDecimal.ZERO;
                    tradeCount++;
                }
                // 그 외 조합(이미 보유 중인데 또 매수 / 미보유인데 매도)은 조용히 무시 —
                // RiskGate가 라이브에서 하는 것과 동일한 정책(물타기·공매도 금지)
                pendingDecision = Optional.empty();
            }

            // ── 2) 오늘 "종가" 기준으로 평가자산을 마킹하고 일별 수익률을 기록한다 ──
            BigDecimal equityToday = cash.add(today.close().multiply(BigDecimal.valueOf(positionQty)));
            double dailyReturn = prevEquity.signum() == 0
                    ? 0.0
                    : equityToday.subtract(prevEquity).divide(prevEquity, 12, RoundingMode.HALF_UP).doubleValue();
            dailyReturns.add(dailyReturn);
            prevEquity = equityToday;

            // ── 3) 오늘 캔들이 "확정된 뒤"의 정보로 전략에게 판단을 묻는다 ──
            //     이 판단은 내일 시가에나 체결되므로(위 1번), 아직 오늘 안에서는 아무 일도 안 일어난다.
            BacktestStrategy.PortfolioState state =
                    new BacktestStrategy.PortfolioState(positionQty, avgPrice, cash);
            pendingDecision = strategy.onCandle(today, state);
        }
        // 마지막 캔들에서 나온 판단은 "다음날"이 없어 체결되지 못한 채 버려진다 —
        // 실전에서도 마감 후 신호는 다음 거래일이 와야 체결될 수 있으므로 자연스러운 동작이다.

        BigDecimal finalEquity = candles.isEmpty()
                ? initialCapital
                : cash.add(candles.get(candles.size() - 1).close().multiply(BigDecimal.valueOf(positionQty)));

        return performanceCalculator.calculate(dailyReturns, initialCapital, finalEquity, tradeCount, trials, trialsVariance);
    }

    /** 수수료까지 포함해 실제로 살 수 있는 최대 수량 — 반올림 탓에 예산을 넘기지 않도록 여유 있으면 한 주씩 줄인다. */
    private long affordableQuantity(BigDecimal cash, BigDecimal execPrice) {
        if (execPrice.signum() <= 0) {
            return 0;
        }
        long qty = cash.divide(execPrice, 0, RoundingMode.DOWN).longValue();
        while (qty > 0) {
            BigDecimal notional = execPrice.multiply(BigDecimal.valueOf(qty));
            BigDecimal totalCost = notional.add(costModel.buyFee(notional));
            if (totalCost.compareTo(cash) <= 0) {
                break;
            }
            qty--;
        }
        return qty;
    }

    private OrderRequest buildOrder(Candle candle, Side side, long qty) {
        return new OrderRequest(
                UUID.randomUUID().toString(),
                "backtest",
                candle.symbol(),
                side,
                qty,
                candle.open(),
                candle.date().atStartOfDay().toInstant(ZoneOffset.UTC)
        );
    }
}
