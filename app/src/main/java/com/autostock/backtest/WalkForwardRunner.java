package com.autostock.backtest;

import com.autostock.common.event.Candle;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

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
 *
 * <h2>전략 종류에 상관없이 재사용 — 일반화된 run(파라미터, 전략 팩토리)</h2>
 * "전략 재설계 실험"(무필터 돌파 vs 필터 돌파 vs 시계열 모멘텀)에서 세 전략을 공정하게
 * 비교하려면 창 분할·파라미터 선택·DSR 계산 같은 파이프라인 자체는 완전히 동일해야 한다.
 * 그런데 전략마다 튜닝 파라미터의 타입이 다르다(변동성 돌파류는 k:Double, 시계열
 * 모멘텀은 N:Integer). 그래서 파라미터 후보 목록과 함께 "파라미터 하나 → 그 값으로
 * 초기화된 전략 인스턴스"를 만드는 팩토리 함수({@link Function})를 받는
 * {@link #run(List, List, Function, int, int, BigDecimal)}를 두고, 기존
 * k 전용 편의 메서드들은 내부적으로 이 일반화된 메서드에 위임한다 — 기존 호출부(및
 * 테스트)는 전혀 바꿀 필요가 없다. N봉 롤백처럼 지표 워밍업이 필요한 전략(시계열
 * 모멘텀 등)을 위해서는 워밍업 캔들 수까지 받는
 * {@link #run(List, List, Function, int, int, int, BigDecimal)}를 별도로 둔다.
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

    /** trainSize=252, testSize=63(기본값)으로 VolatilityBreakoutStrategy를 실행하는 편의 메서드. */
    public WalkForwardResult<Double> run(List<Candle> candles, List<Double> kCandidates, BigDecimal initialCapital) {
        return run(candles, kCandidates, DEFAULT_TRAIN_SIZE, DEFAULT_TEST_SIZE, initialCapital);
    }

    /**
     * VolatilityBreakoutStrategy 전용 편의 메서드 — 내부적으로 일반화된
     * {@link #run(List, List, Function, int, int, BigDecimal)}에 위임한다.
     *
     * @param candles        시간순 정렬된 캔들 전체(여러 창에 걸쳐 재사용됨)
     * @param kCandidates    train 구간에서 비교할 변동성 계수 k 후보 목록
     * @param trainSize      각 창의 훈련 구간 길이(거래일 수)
     * @param testSize       각 창의 테스트(OOS) 구간 길이(거래일 수) — 창 전진 폭이기도 하다
     * @param initialCapital 각 백테스트(train/test 공통)에 쓰는 초기 자본
     */
    public WalkForwardResult<Double> run(List<Candle> candles, List<Double> kCandidates,
                                          int trainSize, int testSize, BigDecimal initialCapital) {
        return run(candles, kCandidates, k -> new VolatilityBreakoutStrategy(k, STOP_LOSS_PCT),
                trainSize, testSize, initialCapital);
    }

    /**
     * 일반화된 walk-forward 실행(워밍업 없음) — 어떤 전략이든 "파라미터 후보 목록 + 파라미터를
     * 전략 인스턴스로 바꾸는 팩토리 함수"만 주면 창 분할/파라미터 선택/DSR 연동 로직을 그대로
     * 재사용할 수 있다. 내부적으로 warmupCandles=0인 {@link #run(List, List, Function, int, int, int, BigDecimal)}에
     * 위임한다.
     *
     * @param candles          시간순 정렬된 캔들 전체(여러 창에 걸쳐 재사용됨)
     * @param paramCandidates  train 구간에서 비교할 파라미터 후보 목록
     * @param strategyFactory  파라미터 하나를 받아 그 값으로 초기화된 "새" 전략 인스턴스를 만드는 함수.
     *                         창마다(그리고 후보마다) 새 인스턴스를 만들어야 한다 — 전략이 내부에
     *                         전일 캔들 등 상태를 기억하는 경우가 많아, 창이 바뀔 때 이전 구간의
     *                         상태가 새 구간으로 새어 들어가면 안 되기 때문이다.
     * @param trainSize        각 창의 훈련 구간 길이(거래일 수)
     * @param testSize         각 창의 테스트(OOS) 구간 길이(거래일 수) — 창 전진 폭이기도 하다
     * @param initialCapital   각 백테스트(train/test 공통)에 쓰는 초기 자본
     */
    public <P> WalkForwardResult<P> run(List<Candle> candles, List<P> paramCandidates,
                                         Function<P, BacktestStrategy> strategyFactory,
                                         int trainSize, int testSize, BigDecimal initialCapital) {
        return run(candles, paramCandidates, strategyFactory, trainSize, testSize, 0, initialCapital);
    }

    /**
     * 일반화된 walk-forward 실행(워밍업 포함) — N봉 롤백처럼 지표가 확정되기까지 여러 봉이
     * 필요한 전략(예: {@code TimeSeriesMomentumStrategy})은 testSize가 짧으면 test 구간
     * 단독으로는 지표를 채우기도 전에 구간이 끝나버려 단 한 번도 매매하지 못할 수 있다.
     * {@code warmupCandles}를 지정하면 train/test 구간 시작 이전의 "이미 지나간 진짜 과거
     * 캔들"을 그만큼 더 붙여 전략에게 넘긴다 — 워밍업 구간의 매매/지표 상태는 실제로
     * 반영되지만, 성과 지표(dailyReturns, tradeCount)에는 warmupCandles 이후 구간만 집계된다
     * ({@link BacktestRunner#run(List, BacktestStrategy, BigDecimal, int, int, double)} 참고).
     * 룩어헤드가 아닌 이유도 그 메서드 Javadoc에 설명돼 있다 — 워밍업 데이터는 항상 test
     * 구간보다 앞선, 이미 확정된 데이터이기 때문이다.
     *
     * @param warmupCandles 각 train/test 구간 시작 앞에 붙일 워밍업 캔들 수. 전체 캔들 목록의
     *                      맨 앞(더 붙일 과거 데이터가 없는 경우)에서는 있는 만큼만 붙는다.
     */
    public <P> WalkForwardResult<P> run(List<Candle> candles, List<P> paramCandidates,
                                         Function<P, BacktestStrategy> strategyFactory,
                                         int trainSize, int testSize, int warmupCandles, BigDecimal initialCapital) {
        if (paramCandidates.isEmpty()) {
            throw new IllegalArgumentException("파라미터 후보 목록이 비어 있음");
        }

        List<P> selectedParams = new ArrayList<>();
        List<Double> oosDailyReturns = new ArrayList<>();
        List<Double> trainSharpes = new ArrayList<>(); // DSR trialsVariance 계산용 — 모든 (후보 × 창)의 train 샤프
        int oosTradeCount = 0;
        int windowCount = 0;

        int start = 0;
        // 창 하나가 [train 구간][test 구간]을 온전히 담을 수 있는 동안 계속 전진한다.
        while (start + trainSize + testSize <= candles.size()) {
            int trainEnd = start + trainSize;
            int testStart = trainEnd;
            int testEnd = testStart + testSize;

            // 워밍업은 각 구간 "시작 이전"의 진짜 과거 데이터에서 가져온다. 전체 목록의 맨 앞이라
            // 더 당길 데이터가 없으면(예: 첫 창의 train 구간) 있는 만큼만 당겨쓴다.
            int trainWarmupStart = Math.max(0, start - warmupCandles);
            int trainWarmupActual = start - trainWarmupStart;
            List<Candle> trainCandles = candles.subList(trainWarmupStart, trainEnd);

            int testWarmupStart = Math.max(0, testStart - warmupCandles);
            int testWarmupActual = testStart - testWarmupStart;
            List<Candle> testCandles = candles.subList(testWarmupStart, testEnd);

            // ── 1) train 구간에서만 후보들을 비교해 샤프비율이 가장 높은 것을 고른다 ──
            double bestSharpe = Double.NEGATIVE_INFINITY;
            P bestParam = paramCandidates.get(0);
            for (P param : paramCandidates) {
                BacktestResult trainResult = backtestRunner.run(
                        trainCandles, strategyFactory.apply(param), initialCapital, trainWarmupActual, 1, 0.0);
                trainSharpes.add(trainResult.sharpe());
                if (trainResult.sharpe() > bestSharpe) {
                    bestSharpe = trainResult.sharpe();
                    bestParam = param;
                }
            }
            selectedParams.add(bestParam);

            // ── 2) 고른 파라미터를, train과 전혀 겹치지 않는 test 구간에 "그대로" 적용해 OOS 성과를 잰다 ──
            BacktestResult testResult = backtestRunner.run(
                    testCandles, strategyFactory.apply(bestParam), initialCapital, testWarmupActual, 1, 0.0);
            oosDailyReturns.addAll(testResult.dailyReturns());
            oosTradeCount += testResult.tradeCount();

            windowCount++;
            start += testSize; // 창을 test 구간 길이만큼 앞으로 민다
        }

        int trials = paramCandidates.size() * windowCount;
        double trialsVariance = populationVariance(trainSharpes);
        BigDecimal oosFinalEquity = compound(initialCapital, oosDailyReturns);

        BacktestResult oosResult = performanceCalculator.calculate(
                oosDailyReturns, initialCapital, oosFinalEquity, oosTradeCount, trials, trialsVariance);

        return new WalkForwardResult<>(List.copyOf(selectedParams), oosResult, trials);
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
