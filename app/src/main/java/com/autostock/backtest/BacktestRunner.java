package com.autostock.backtest;

import com.autostock.common.event.Candle;
import com.autostock.common.event.Fill;
import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.Side;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 단일 종목 캔들 목록을 순서대로 재생하며 전략을 실행하는 백테스트 루프.
 *
 * <p>스프링 컨텍스트가 필요 없는 순수 자바 클래스다 — 백테스트는 짧은 주기로
 * 반복 실행(파라미터 스윕, walk-forward 등)되므로 빈 컨텍스트를 매번 띄우는 비용을
 * 피하고 싶기 때문이다. 라이브 매매와 공유하는 부분은 이벤트 스키마({@link OrderRequest},
 * {@link Fill})와 전략/리스크 "로직"이지, 스프링 배선 자체가 아니다.
 *
 * <h2>타이밍 모델 — {@link TradeIntent}는 "오늘" 걸리고 "오늘" 판정된다</h2>
 * 전략은 오늘 캔들의 시가만 알고 있는 상태에서(위 {@link BacktestStrategy} 참고) 오늘
 * 걸어둘 조건부 주문({@link TradeIntent})을 정한다. 이 조건부 주문은 바로 그날의 나머지
 * 장중 움직임(고가/저가)으로 체결 여부가 갈린다 — 옛 모델처럼 "다음날"까지 미루지 않는다.
 * 이래도 룩어헤드가 아닌 이유: 결정에 쓴 정보(오늘 시가)와 체결 판정에 쓰는 정보(오늘
 * 고가/저가)가 둘 다 "오늘 하루 동안 실제로 관측 가능한" 정보이고, 시간 순서상 시가 →
 * 장중 가격변동 순으로 자연스럽게 이어지기 때문이다. 다만 "익일 시가 매도"({@link
 * TradeIntent#sellNextOpen()})는 이름 그대로 "이 판단을 내린 시점(오늘 개장 직전) 기준으로
 * 그다음에 오는 시가", 즉 오늘 시가에 체결된다 — 진입한 날의 다음 거래일 개장 직후
 * 판단이 내려지기 때문에 자연히 "익일(진입일 기준 다음날) 시가 매도"가 된다.
 *
 * <h2>체결 순서 규약 — 왜 손절(SellStop)을 매수(BuyStop)보다 먼저 판정하는가?</h2>
 * 같은 날 하나의 전략 호출이 buyStop과 (그 진입을 보호하는) sellStop을 동시에 반환하는
 * 경우가 있다(변동성 돌파 전략의 표준 패턴 — 진입가 대비 손절선을 같이 건다). 이때 그날
 * 하루 중 고가가 진입 트리거에 닿고 저가도 손절선까지 내려갔다면, "어느 쪽이 먼저
 * 일어났는지" 일봉(OHLC) 데이터만으로는 알 수 없다. 이 러너는 <b>운 좋게 손절을 피했다고
 * 낙관하지 않고, 최악의 경우(진입 직후 곧바로 손절)를 가정한다</b> — PLAN이 강조하는
 * "보수적 가정" 원칙이다. 그래서 매수가 체결된 그 즉시, 같은 날의 손절 조건도 함께
 * 검사해 필요하면 같은 날 안에 청산까지 반영한다. 이미 보유 중이던 포지션에 대해서는
 * (전날 이전에 진입해 오늘 아침 이미 포지션이 있는 경우) 애초에 매수를 시도하지 않으므로
 * 매도 계열 인텐트를 먼저(그리고 그것만) 판정한다.
 *
 * <h2>포지션 모델</h2>
 * 매수는 {@link TradeIntent#fraction()}만큼의 가용 현금을 투입(기본값 1.0이면 기존과 동일하게
 * 전액 투입) / 매도는 항상 전량 청산(보유 수량을 모두 매도)하는 모델이다.
 * 하루에 신규 진입은 최대 1건(첫 번째로 체결 조건을 만족한 buyStop)만 반영한다.
 * TODO(Phase 4 이후): risk 모듈의 {@code PositionSizer}(고정비율 사이징)를 그대로 재사용해
 * "전량"이 아니라 실제 운영과 동일한 사이징 규칙을 적용한다 — 지금은 코어 로직(비용모델,
 * 룩어헤드 방지, 성과지표) 검증이 우선이라 사이징은 의도적으로 단순화했다.
 *
 * <h2>워밍업 구간(warmupCandles)</h2>
 * {@link #run(List, BacktestStrategy, BigDecimal, int, int, double)}의 {@code warmupCandles}로
 * 지정한 앞부분 캔들은 전략에게 정상적으로 넘겨 매매까지 체결시키지만, 성과 지표에는 반영하지
 * 않는다 — N봉 롤백처럼 지표가 확정되기까지 시간이 걸리는 전략을 짧은 walk-forward test
 * 구간에도 제대로 태울 수 있게 하기 위함이다. 자세한 이유는 해당 메서드 Javadoc 참고.
 */
public final class BacktestRunner {

    private static final Logger log = LoggerFactory.getLogger(BacktestRunner.class);

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
        return run(candles, strategy, initialCapital, 0, 1, 0.0);
    }

    /**
     * @param candles        시간순으로 정렬된 단일 종목 캔들 목록
     * @param strategy       조건부 주문(TradeIntent) 판단 함수
     * @param initialCapital 초기 자본
     * @param trials         DSR 계산용 시도 횟수 (파라미터 스윕 등에서 몇 개를 테스트했는지)
     * @param trialsVariance DSR 계산용 시도 간 샤프비율 분산
     */
    public BacktestResult run(List<Candle> candles, BacktestStrategy strategy, BigDecimal initialCapital,
                               int trials, double trialsVariance) {
        return run(candles, strategy, initialCapital, 0, trials, trialsVariance);
    }

    /**
     * 워밍업 구간을 지원하는 전체 버전 — 지표가 확정되기까지 여러 봉이 필요한 전략(예:
     * 시계열 모멘텀의 N봉 롤백, 필터 돌파의 SMA20)이 walk-forward의 test(OOS) 구간처럼
     * 짧은 구간만 단독으로 받으면 지표를 채우기도 전에 구간이 끝나버려 단 한 번도
     * 매매하지 못하는 문제를 해결하기 위한 기능이다.
     *
     * <p><b>왜 룩어헤드가 아닌가</b>: {@code candles}의 앞쪽 {@code warmupCandles}개는
     * "성과 측정 대상 기간보다 앞선, 이미 지나간" 실제 과거 데이터다(호출자가 test 구간
     * 시작 이전의 진짜 캔들을 그대로 잘라 붙여서 넘긴다 — {@link WalkForwardRunner} 참고).
     * 전략은 이 구간에서도 정상적으로 {@code onCandle}을 호출받고 실제로 매매까지
     * 체결시킨다(워밍업 구간 중 이미 포지션을 잡았다면 그 포지션을 그대로 들고
     * "본 구간"으로 넘어간다 — 실전에서도 그렇게 됐을 것이므로). 다만 <b>성과 집계
     * (일별 수익률, 체결 횟수, 기준 자본)에는 워밍업 구간의 결과를 포함하지 않는다</b> —
     * 워밍업은 "지표/포지션을 실전과 같은 상태로 미리 채워두는 것"이 목적이지, 그 기간의
     * 손익까지 이 백테스트 결과에 섞어 넣으면 test 구간과 무관한 성과가 끼어드는 셈이기
     * 때문이다.
     *
     * @param warmupCandles  앞부분 몇 개 캔들을 "워밍업 전용"으로 취급할지(0이면 워밍업 없음 —
     *                       기존 동작과 완전히 동일).
     */
    public BacktestResult run(List<Candle> candles, BacktestStrategy strategy, BigDecimal initialCapital,
                               int warmupCandles, int trials, double trialsVariance) {
        // 처리 시간·처리량 계측 — 스프링/Micrometer 없이 System.nanoTime만 쓴다. 백테스트는
        // 스프링 컨텍스트 없는 순수 자바 루프로 반복 실행(파라미터 스윕 등)되는 게 핵심이라,
        // 계측기까지 주입받게 만들면 그 목적을 해친다 (PLAN ADR-5: "러너 자체 계측").
        long startNanos = System.nanoTime();
        BacktestResult result = doRun(candles, strategy, initialCapital, warmupCandles, trials, trialsVariance);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("백테스트 완료: {}봉 처리, {}ms 소요 ({}봉/ms)",
                candles.size(), elapsedMillis, elapsedMillis == 0 ? "∞" : String.format("%.2f", candles.size() / (double) elapsedMillis));
        return result;
    }

    private BacktestResult doRun(List<Candle> candles, BacktestStrategy strategy, BigDecimal initialCapital,
                               int warmupCandles, int trials, double trialsVariance) {
        List<Double> dailyReturns = new ArrayList<>();
        Ledger ledger = new Ledger(initialCapital);
        BigDecimal prevEquity = initialCapital;
        // 워밍업이 끝나는 시점의 평가자산 — "이 백테스트가 실제로 측정하는 기간"의 시작 자본이다.
        // 워밍업이 없으면(warmupCandles=0) initialCapital 그대로 유지된다(기존 동작과 동일).
        BigDecimal reportedBaseCapital = initialCapital;
        int reportedTradeCount = 0;

        for (int i = 0; i < candles.size(); i++) {
            Candle today = candles.get(i);
            boolean warmingUp = i < warmupCandles;
            int tradeCountBeforeToday = ledger.tradeCount;

            // ── 1) 오늘 개장 직전 상태를 전략에게 보여주고, 오늘 걸어둘 조건부 주문을 받는다 ──
            BacktestStrategy.PortfolioState state =
                    new BacktestStrategy.PortfolioState(ledger.positionQty, ledger.avgPrice, ledger.cash);
            List<TradeIntent> intents = strategy.onCandle(today, state);

            // ── 2) 이미 보유 중이면 매도 계열부터 판정한다(손절 우선 — 클래스 설명 참고) ──
            if (ledger.positionQty > 0) {
                TradeIntent stop = triggeredSellStop(intents, today);
                if (stop != null) {
                    applySell(ledger, today, stop.price().min(today.open()));
                } else {
                    TradeIntent scheduledExit = firstOfKind(intents, TradeIntent.Kind.SELL_NEXT_OPEN);
                    if (scheduledExit != null) {
                        applySell(ledger, today, today.open());
                    }
                }
            }

            // ── 3) 미보유 상태면 매수 스탑을 판정한다(하루 최대 1건 신규 진입) ──
            if (ledger.positionQty == 0) {
                TradeIntent buy = triggeredBuyStop(intents, today);
                if (buy != null && applyBuy(ledger, today, buy.price().max(today.open()), buy.fraction())) {
                    // ── 진입 직후, 짝지어 걸린 손절이 같은 날 조건을 만족하는지 "보수적으로" 재확인 ──
                    // 운 좋게 피했다고 가정하지 않고, 같은 날 안에 손절까지 갔다고 본다(최악 가정 원칙).
                    TradeIntent pairedStop = triggeredSellStop(intents, today);
                    if (pairedStop != null) {
                        applySell(ledger, today, pairedStop.price().min(today.open()));
                    }
                }
            }

            // ── 4) 오늘 종가 기준으로 평가자산을 마킹하고, 워밍업이 아닌 날만 일별 수익률/체결수에 반영한다 ──
            BigDecimal equityToday = ledger.cash.add(today.close().multiply(BigDecimal.valueOf(ledger.positionQty)));
            double dailyReturn = prevEquity.signum() == 0
                    ? 0.0
                    : equityToday.subtract(prevEquity).divide(prevEquity, 12, RoundingMode.HALF_UP).doubleValue();
            if (!warmingUp) {
                dailyReturns.add(dailyReturn);
                reportedTradeCount += ledger.tradeCount - tradeCountBeforeToday;
            }
            if (i == warmupCandles - 1) {
                // 워밍업의 마지막 날 — 이 시점의 평가자산이 "본 구간" 첫날 수익률의 기준점이자
                // totalReturn 계산의 기준 자본이 된다.
                reportedBaseCapital = equityToday;
            }
            prevEquity = equityToday;
        }

        BigDecimal finalEquity = candles.isEmpty()
                ? initialCapital
                : ledger.cash.add(candles.get(candles.size() - 1).close().multiply(BigDecimal.valueOf(ledger.positionQty)));

        return performanceCalculator.calculate(dailyReturns, reportedBaseCapital, finalEquity, reportedTradeCount,
                trials, trialsVariance);
    }

    /** 오늘 저가가 스탑 가격 이하로 내려가 체결 조건을 만족하는 첫 SELL_STOP 인텐트, 없으면 null. */
    private TradeIntent triggeredSellStop(List<TradeIntent> intents, Candle today) {
        for (TradeIntent intent : intents) {
            if (intent.kind() == TradeIntent.Kind.SELL_STOP && today.low().compareTo(intent.price()) <= 0) {
                return intent;
            }
        }
        return null;
    }

    /** 오늘 고가가 트리거 가격 이상으로 올라 체결 조건을 만족하는 첫 BUY_STOP 인텐트, 없으면 null. */
    private TradeIntent triggeredBuyStop(List<TradeIntent> intents, Candle today) {
        for (TradeIntent intent : intents) {
            if (intent.kind() == TradeIntent.Kind.BUY_STOP && today.high().compareTo(intent.price()) >= 0) {
                return intent;
            }
        }
        return null;
    }

    private TradeIntent firstOfKind(List<TradeIntent> intents, TradeIntent.Kind kind) {
        for (TradeIntent intent : intents) {
            if (intent.kind() == kind) {
                return intent;
            }
        }
        return null;
    }

    /** 매도를 체결하고 원장을 갱신한다. referencePrice는 이미 트리거/시가 규칙이 적용된 "체결 기준가"다. */
    private void applySell(Ledger ledger, Candle today, BigDecimal referencePrice) {
        OrderRequest order = buildOrder(today, Side.SELL, ledger.positionQty);
        Fill fill = executionHandler.execute(order, referencePrice);
        BigDecimal notional = fill.fillPrice().multiply(BigDecimal.valueOf(fill.filledQuantity()));
        ledger.cash = ledger.cash.add(notional).subtract(costModel.sellFee(notional));
        ledger.positionQty = 0;
        ledger.avgPrice = BigDecimal.ZERO;
        ledger.tradeCount++;
    }

    /**
     * 매수를 시도한다. 수수료까지 감안했을 때 1주도 살 수 없으면 아무 일도 일어나지 않는다.
     *
     * <p><b>투입 비중(fraction)</b>: 가용 현금 전액이 아니라 {@code cash × fraction}만 투입
     * 예산으로 삼는다 — 수량 = floor(가용현금×fraction / 체결가)에 해당(수수료까지 고려한
     * 정확한 수량은 {@link #affordableQuantity}가 예산 상한 안에서 한 주씩 줄여가며 구한다).
     * fraction=1.0(기존 팩토리들의 기본값)이면 기존 동작과 완전히 동일하다.
     *
     * @return 실제로 체결됐으면 true
     */
    private boolean applyBuy(Ledger ledger, Candle today, BigDecimal referencePrice, double fraction) {
        BigDecimal execPreview = costModel.slippageAdjustedBuyPrice(referencePrice);
        BigDecimal budget = ledger.cash.multiply(BigDecimal.valueOf(fraction));
        long qty = affordableQuantity(budget, execPreview);
        if (qty <= 0) {
            return false;
        }
        OrderRequest order = buildOrder(today, Side.BUY, qty);
        Fill fill = executionHandler.execute(order, referencePrice);
        BigDecimal notional = fill.fillPrice().multiply(BigDecimal.valueOf(fill.filledQuantity()));
        ledger.cash = ledger.cash.subtract(notional).subtract(costModel.buyFee(notional));
        ledger.positionQty = qty;
        ledger.avgPrice = fill.fillPrice();
        ledger.tradeCount++;
        return true;
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

    /** 백테스트 진행 중 바뀌는 상태(현금/보유수량/평단/누적체결수)를 한데 묶은 가변 원장. */
    private static final class Ledger {
        BigDecimal cash;
        long positionQty;
        BigDecimal avgPrice = BigDecimal.ZERO;
        int tradeCount;

        Ledger(BigDecimal initialCash) {
            this.cash = initialCash;
        }
    }
}
