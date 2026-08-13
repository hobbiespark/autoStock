package com.autostock.backtest;

import com.autostock.common.event.Candle;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Walk-forward 최적화 러너 — 파라미터를 "미래를 훔쳐보지 않고" 고르는 방법.
 *
 * <h2>왜 인샘플(train) 성적이 아니라 OOS(test) 성적만 믿는가?</h2>
 * 파라미터(예: 변동성 돌파의 k값)를 어떤 구간의 데이터에 맞춰 고르면, 그 구간에 대해서는
 * 최적화됐기 때문에 성과가 좋아 보이는 게 당연하다 — 문제는 "당연히 좋아 보이는 것"과
 * "진짜 미래에도 통할 것"을 구분할 수 없다는 점이다(과최적화, PLAN 2절). 그래서 이 러너는
 * 데이터를 [train 구간, test 구간] 쌍으로 나눠, <b>train에서만 파라미터를 고르고</b>,
 * <b>test(train 구간을 만드는 데 전혀 쓰이지 않은, "한 번도 보지 않은" 구간)에서만 성과를
 * 잰다.</b> 그 뒤 창(window)을 test 구간 길이만큼 앞으로 밀어 같은 과정을 반복하고, 각
 * 창에서 나온 test 구간의 일별 수익률을 순서대로 이어붙인다. <b>최종 성과 판단은 이렇게
 * 이어붙인 OOS 수익률로만 한다</b> — train 구간 성과는 파라미터 선택에만 쓰이고 최종
 * {@link WalkForwardResult#oosResult()}에는 전혀 반영되지 않는다.
 *
 * <h2>DSR 연동 — 시도 횟수와 시도 간 분산을 실제 값으로 넘긴다</h2>
 * 각 창마다 파라미터 후보를 여러 개 시도해서 그중 최고를 고르므로, 전체 walk-forward
 * 과정에서 실제로 "시도"한 횟수는 N = (후보 수) × (창 수) 다. 이 N과, 그 N번의 train
 * 시도에서 실제로 관측된 샤프비율들의 분산을 {@link PerformanceCalculator#calculate}의
 * DSR 계산에 그대로 넘긴다 — "이 정도 OOS 성과가 단순히 많이 시도해서 우연히 나온 것일
 * 가능성"까지 반영해야 진짜 신뢰수준을 알 수 있기 때문이다.
 */
public final class WalkForwardRunner {

    /** 기본 훈련 구간 길이(거래일) — 국내 주식시장 관행값 연 거래일수. */
    private static final int DEFAULT_TRAIN_SIZE = 252;

    /** 기본 테스트 구간 길이(거래일) — 대략 분기(3개월). */
    private static final int DEFAULT_TEST_SIZE = 63;

    /** train 구간 백테스트에 쓰는 손절 비율 — VolatilityBreakoutStrategy 기본값과 동일. */
    private static final Double STOP_LOSS_PCT = -0.03;

    private final BacktestRunner backtestRunner;
    private final PerformanceCalculator performanceCalculator;

    public WalkForwardRunner(CostModel costModel) {
        this.backtestRunner = new BacktestRunner(costModel);
        this.performanceCalculator = new PerformanceCalculator();
    }

    /** trainSize=252, testSize=63(기본값)으로 실행하는 편의 메서드. */
    public WalkForwardResult run(List<Candle> candles, List<Double> kCandidates, BigDecimal initialCapital) {
        return run(candles, kCandidates, DEFAULT_TRAIN_SIZE, DEFAULT_TEST_SIZE, initialCapital);
    }

    /**
     * @param candles        시간순 정렬된 캔들 전체(여러 창에 걸쳐 재사용됨)
     * @param kCandidates    train 구간에서 비교할 변동성 계수 k 후보 목록
     * @param trainSize      각 창의 훈련 구간 길이(거래일 수)
     * @param testSize       각 창의 테스트(OOS) 구간 길이(거래일 수) — 창 전진 폭이기도 하다
     * @param initialCapital 각 백테스트(train/test 공통)에 쓰는 초기 자본
     */
    public WalkForwardResult run(List<Candle> candles, List<Double> kCandidates,
                                  int trainSize, int testSize, BigDecimal initialCapital) {
        if (kCandidates.isEmpty()) {
            throw new IllegalArgumentException("파라미터 후보(k) 목록이 비어 있음");
        }

        List<Double> selectedParams = new ArrayList<>();
        List<Double> oosDailyReturns = new ArrayList<>();
        List<Double> trainSharpes = new ArrayList<>(); // DSR trialsVariance 계산용 — 모든 (후보 × 창)의 train 샤프
        int oosTradeCount = 0;
        int windowCount = 0;

        int start = 0;
        // 창 하나가 [train 구간][test 구간]을 온전히 담을 수 있는 동안 계속 전진한다.
        while (start + trainSize + testSize <= candles.size()) {
            List<Candle> trainCandles = candles.subList(start, start + trainSize);
            List<Candle> testCandles = candles.subList(start + trainSize, start + trainSize + testSize);

            // ── 1) train 구간에서만 후보 k들을 비교해 샤프비율이 가장 높은 것을 고른다 ──
            double bestSharpe = Double.NEGATIVE_INFINITY;
            double bestK = kCandidates.get(0);
            for (double k : kCandidates) {
                BacktestResult trainResult = backtestRunner.run(trainCandles, newStrategy(k), initialCapital);
                trainSharpes.add(trainResult.sharpe());
                if (trainResult.sharpe() > bestSharpe) {
                    bestSharpe = trainResult.sharpe();
                    bestK = k;
                }
            }
            selectedParams.add(bestK);

            // ── 2) 고른 k를, train과 전혀 겹치지 않는 test 구간에 "그대로" 적용해 OOS 성과를 잰다 ──
            BacktestResult testResult = backtestRunner.run(testCandles, newStrategy(bestK), initialCapital);
            oosDailyReturns.addAll(testResult.dailyReturns());
            oosTradeCount += testResult.tradeCount();

            windowCount++;
            start += testSize; // 창을 test 구간 길이만큼 앞으로 민다
        }

        int trials = kCandidates.size() * windowCount;
        double trialsVariance = populationVariance(trainSharpes);
        BigDecimal oosFinalEquity = compound(initialCapital, oosDailyReturns);

        BacktestResult oosResult = performanceCalculator.calculate(
                oosDailyReturns, initialCapital, oosFinalEquity, oosTradeCount, trials, trialsVariance);

        return new WalkForwardResult(List.copyOf(selectedParams), oosResult, trials);
    }

    /** 창마다 새 전략 인스턴스를 만든다 — VolatilityBreakoutStrategy는 전일 캔들을 내부에 기억하므로
     * train/test/창이 바뀔 때마다 상태를 공유하면 안 된다(이전 구간의 마지막 캔들이 엉뚱하게
     * "전일"로 쓰이는 사고를 막기 위함). */
    private BacktestStrategy newStrategy(double k) {
        return new VolatilityBreakoutStrategy(k, STOP_LOSS_PCT);
    }

    /** OOS 일별 수익률을 이어붙여 최종 평가자산을 복리로 재구성한다(성과지표 표시용 totalReturn 계산에 필요). */
    private BigDecimal compound(BigDecimal initialCapital, List<Double> dailyReturns) {
        double multiplier = 1.0;
        for (double r : dailyReturns) {
            multiplier *= (1 + r);
        }
        return initialCapital.multiply(BigDecimal.valueOf(multiplier));
    }

    /** 모분산(분모 n) — PerformanceCalculator의 다른 통계량과 같은 관례를 맞춘다. */
    private double populationVariance(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        double mean = 0.0;
        for (double v : values) {
            mean += v;
        }
        mean /= values.size();

        double sumSq = 0.0;
        for (double v : values) {
            double d = v - mean;
            sumSq += d * d;
        }
        return sumSq / values.size();
    }
}
