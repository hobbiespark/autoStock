package com.autostock.backtest;

import com.autostock.common.event.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * "전략 재설계 실험" — 무필터 돌파(A안) 대비 거래 빈도를 줄인 두 대안(필터 돌파 B안,
 * 시계열 모멘텀 C안)을 같은 walk-forward 파이프라인, 같은 실데이터, 같은 비용모델로
 * 공정 비교하는 검증 테스트.
 *
 * <h2>배경</h2>
 * 무필터 변동성 돌파는 {@link RealDataWalkForwardTest}에서 확인되듯 연 250~300회의
 * 왕복 매매가 발생하고, 왕복당 ~0.35%의 비용 드래그를 감당하지 못해 게이트①(OOS 수익 &gt; 0,
 * DSR &gt; 0.95, MDD &lt; 15%)에서 거의 전 종목 FAIL한다. 이 테스트는 "거래 빈도를 줄이면
 * 실제로 게이트를 통과할 수 있는가"를 실데이터로 검증한다.
 *
 * <h2>비교 대상 3안</h2>
 * <ul>
 *   <li>A) 무필터 돌파 — {@link VolatilityBreakoutStrategy}, k 후보 5개, 손절 -3%.</li>
 *   <li>B) 필터 돌파 — {@link FilteredBreakoutStrategy}(추세 필터 + 전일 캔들 필터 모두 켬),
 *       k 후보 5개, 손절 -3%.</li>
 *   <li>C) 시계열 모멘텀 — {@link TimeSeriesMomentumStrategy}, N(lookback) 후보 3개, 손절 없음.</li>
 * </ul>
 * 세 전략 모두 {@link WalkForwardRunner}의 일반화된 run 메서드(파라미터 후보 + 전략 팩토리)로
 * 실행하므로 창 분할, 파라미터 선택(train 샤프 최고), DSR trial 계산 로직이 완전히 동일하다 —
 * "같은 파이프라인에서 공정 비교"의 핵심 전제다.
 *
 * <h2>표 1 — 전략별 OOS 성과 및 게이트① 판정</h2>
 * 기본 비용모델({@link CostModel#defaults()})로 5종목 × 3전략 = 15행을 출력한다.
 *
 * <h2>표 2 — 비용 민감도(강건성)</h2>
 * 수수료·거래세·슬리피지를 모두 0.5배/1배/2배로 스케일한 비용모델로 같은 walk-forward를
 * 다시 돌려, "비용이 2배가 되면 OOS 수익 부호가 뒤집히는가"를 전략별 강건성 지표로 낸다.
 * 거래 횟수가 적은 전략일수록 비용에 덜 민감해야 한다는 것이 이 실험의 가설이다.
 *
 * <p>데이터가 없으면 {@code assumeTrue}로 조용히 스킵한다(RealDataWalkForwardTest와 동일 이유).
 * 실데이터는 스크립트를 다시 돌릴 때마다 범위가 바뀌므로 성과 수치 자체는 assert하지 않고
 * "정상적으로 산출됐는가"(NaN 아님)만 확인한다 — 실제 판단은 표를 사람이 읽고 내린다.
 */
class RealDataStrategyComparisonTest {

    private static final Map<String, String> SYMBOLS = new LinkedHashMap<>();
    static {
        SYMBOLS.put("005930", "삼성전자");
        SYMBOLS.put("000660", "SK하이닉스");
        SYMBOLS.put("035420", "NAVER");
        SYMBOLS.put("035720", "카카오");
        SYMBOLS.put("069500", "KODEX200");
    }

    private static final List<Double> K_CANDIDATES = List.of(0.3, 0.4, 0.5, 0.6, 0.7);
    private static final List<Integer> N_CANDIDATES = List.of(60, 120, 200);
    private static final Double STOP_LOSS_PCT = -0.03; // A/B 전용, C는 손절 미사용

    private static final int TRAIN_SIZE = 252;
    private static final int TEST_SIZE = 63;
    private static final BigDecimal INITIAL_CAPITAL = new BigDecimal("10000000");

    private static final double GATE_MIN_DSR = 0.95;
    private static final double GATE_MAX_MDD = 0.15;

    private static final List<Double> COST_MULTIPLIERS = List.of(0.5, 1.0, 2.0);

    private final CandleCsvLoader loader = new CandleCsvLoader();

    /**
     * 파라미터 후보 목록 + 전략 팩토리를 한데 묶은 전략 정의 — WalkForwardRunner의 일반화된
     * run(paramCandidates, strategyFactory, ..., warmupCandles, ...)에 그대로 넘길 수 있다.
     *
     * @param warmupCandles 이 전략이 지표를 채우는 데 필요한 최소 워밍업 캔들 수. A/B는
     *                      실질적으로 워밍업이 필요 없어(A는 전일 1봉, B는 SMA20 정도로
     *                      testSize=63 안에서 충분히 감당됨) 0을 쓴다. C(시계열 모멘텀)는
     *                      N 후보 최댓값(200)조차 testSize=63보다 훨씬 크기 때문에 워밍업 없이는
     *                      test 구간에서 단 한 번도 판단 기회를 얻지 못한다(21봉마다 판단하는데
     *                      200봉의 역사가 쌓이려면 그 자체로 200봉 이상이 필요) — 그래서
     *                      "N 후보 최댓값 + 판단주기(21)"만큼 워밍업을 줘서, test 구간 진입
     *                      시점에 이미 지표가 완성되어 있고 test 구간 안에서도 여러 번 판단할
     *                      기회를 갖도록 한다. 워밍업 데이터는 test 구간보다 앞선 실제 과거
     *                      데이터이므로 룩어헤드가 아니다(WalkForwardRunner/BacktestRunner 참고).
     */
    private record StrategyDef<P>(String label, List<P> candidates, Function<P, BacktestStrategy> factory,
                                   int warmupCandles) {
    }

    private List<StrategyDef<?>> strategyDefs() {
        int momentumWarmup = N_CANDIDATES.stream().mapToInt(Integer::intValue).max().orElseThrow() + 21;
        return List.of(
                new StrategyDef<>("A.무필터돌파", K_CANDIDATES,
                        (Function<Double, BacktestStrategy>) k -> new VolatilityBreakoutStrategy(k, STOP_LOSS_PCT), 0),
                new StrategyDef<>("B.필터돌파", K_CANDIDATES,
                        (Function<Double, BacktestStrategy>) k -> new FilteredBreakoutStrategy(k, STOP_LOSS_PCT, true, true), 0),
                new StrategyDef<>("C.시계열모멘텀", N_CANDIDATES,
                        (Function<Integer, BacktestStrategy>) n -> new TimeSeriesMomentumStrategy(n), momentumWarmup)
        );
    }

    @Test
    void 세_전략을_같은_파이프라인으로_공정_비교() {
        Path dataDir = resolveDataDir();
        assumeTrue(dataDir != null,
                "data/ 디렉터리를 찾지 못해 전략 비교 실험을 스킵함 — "
                        + "먼저 `python3 scripts/fetch_yahoo_daily.py` 로 data/ 를 채운 뒤 다시 실행할 것");

        List<StrategyDef<?>> strategies = strategyDefs();

        StringBuilder table1 = new StringBuilder();
        table1.append(String.format("%-14s | %-12s | %10s | %10s | %8s | %8s | %8s | %6s | %6s%n",
                "종목", "전략", "OOS수익률", "CAGR", "MDD", "Sharpe", "DSR", "거래수", "게이트①"));
        table1.append("-".repeat(120)).append(System.lineSeparator());

        StringBuilder table2 = new StringBuilder();
        table2.append(String.format("%-14s | %-12s | %10s | %10s | %10s%n",
                "종목", "전략", "0.5x수익률", "1x수익률", "2x수익률"));
        table2.append("-".repeat(80)).append(System.lineSeparator());

        // 전략별 "비용 2배에서 부호가 바뀐 종목 수" 집계
        Map<String, Integer> flipCount = new LinkedHashMap<>();
        Map<String, Integer> evaluatedCount = new LinkedHashMap<>();
        for (StrategyDef<?> def : strategies) {
            flipCount.put(def.label(), 0);
            evaluatedCount.put(def.label(), 0);
        }

        int verifiedSymbols = 0;

        for (Map.Entry<String, String> entry : SYMBOLS.entrySet()) {
            String code = entry.getKey();
            String name = entry.getValue();
            Path csv = dataDir.resolve(code + ".csv");
            if (!Files.isRegularFile(csv)) {
                System.out.println("[skip] " + code + "(" + name + ") CSV 없음: " + csv);
                continue;
            }

            List<Candle> candles = loader.load(csv);
            if (candles.size() < TRAIN_SIZE + TEST_SIZE) {
                System.out.println("[skip] " + code + "(" + name + ") 캔들 수가 부족함(최소 "
                        + (TRAIN_SIZE + TEST_SIZE) + "개 필요, 실제 " + candles.size() + "개)");
                continue;
            }

            String label = code + "(" + name + ")";

            // ── buy&hold 기준선(전략과 무관, OOS 기간 전체 공통) ──
            int windowCount = windowCount(candles.size(), TRAIN_SIZE, TEST_SIZE);
            double buyHoldReturn = buyHoldReturn(candles, TRAIN_SIZE, windowCount, TEST_SIZE);

            // ── 표 1: 기본 비용모델로 3전략 실행 ──
            WalkForwardRunner defaultRunner = new WalkForwardRunner(CostModel.defaults());
            for (StrategyDef<?> def : strategies) {
                WalkForwardResult<?> result = runWalkForward(defaultRunner, candles, def);
                BacktestResult oos = result.oosResult();

                assertFalse(oos.dailyReturns().isEmpty(), label + "/" + def.label() + ": OOS 일별 수익률이 비어 있음");
                assertFalse(Double.isNaN(oos.totalReturn()), label + "/" + def.label() + ": totalReturn이 NaN");
                assertFalse(Double.isNaN(oos.cagr()), label + "/" + def.label() + ": cagr이 NaN");
                assertFalse(Double.isNaN(oos.mdd()), label + "/" + def.label() + ": mdd가 NaN");
                assertFalse(Double.isNaN(oos.sharpe()), label + "/" + def.label() + ": sharpe가 NaN");
                assertFalse(Double.isNaN(oos.dsrConfidence()), label + "/" + def.label() + ": dsrConfidence가 NaN");
                assertTrue(result.trials() > 0, label + "/" + def.label() + ": trial 수는 0보다 커야 함");

                boolean gatePass = oos.totalReturn() > 0
                        && oos.dsrConfidence() > GATE_MIN_DSR
                        && oos.mdd() < GATE_MAX_MDD;

                table1.append(String.format("%-14s | %-12s | %9.2f%% | %9.2f%% | %7.2f%% | %8.3f | %8.3f | %6d | %s%n",
                        label, def.label(), oos.totalReturn() * 100, oos.cagr() * 100, oos.mdd() * 100,
                        oos.sharpe(), oos.dsrConfidence(), oos.tradeCount(), gatePass ? "PASS" : "FAIL"));
            }
            table1.append(String.format("%-14s   (참고) 단순보유(buy&hold) 총수익률: %9.2f%%%n", label, buyHoldReturn * 100));

            // ── 표 2: 비용 배수별 OOS 수익률 ──
            for (StrategyDef<?> def : strategies) {
                Map<Double, Double> returnByMultiplier = new LinkedHashMap<>();
                for (double multiplier : COST_MULTIPLIERS) {
                    WalkForwardRunner scaledRunner = new WalkForwardRunner(scaledCostModel(multiplier));
                    WalkForwardResult<?> result = runWalkForward(scaledRunner, candles, def);
                    double totalReturn = result.oosResult().totalReturn();
                    assertFalse(Double.isNaN(totalReturn),
                            label + "/" + def.label() + "/" + multiplier + "x: totalReturn이 NaN");
                    returnByMultiplier.put(multiplier, totalReturn);
                }
                table2.append(String.format("%-14s | %-12s | %9.2f%% | %9.2f%% | %9.2f%%%n",
                        label, def.label(),
                        returnByMultiplier.get(0.5) * 100,
                        returnByMultiplier.get(1.0) * 100,
                        returnByMultiplier.get(2.0) * 100));

                boolean signFlipped = Math.signum(returnByMultiplier.get(1.0)) != Math.signum(returnByMultiplier.get(2.0));
                evaluatedCount.merge(def.label(), 1, Integer::sum);
                if (signFlipped) {
                    flipCount.merge(def.label(), 1, Integer::sum);
                }
            }

            verifiedSymbols++;
        }

        assumeTrue(verifiedSymbols > 0,
                "data/ 디렉터리는 있으나 유효한 종목 CSV가 하나도 없어 전략 비교 실험을 스킵함");

        System.out.println();
        System.out.println("=== [표 1] 전략 재설계 실험 — 5종목 x 3전략 OOS walk-forward 비교 ===");
        System.out.println("(trainSize=" + TRAIN_SIZE + ", testSize=" + TEST_SIZE + ", 초기자본=" + INITIAL_CAPITAL
                + ", A/B 손절=-3%, k후보=" + K_CANDIDATES + ", N후보=" + N_CANDIDATES + ")");
        System.out.println();
        System.out.print(table1);

        System.out.println();
        System.out.println("=== [표 2] 비용 민감도(강건성) — 수수료/거래세/슬리피지 전부 배수 적용 ===");
        System.out.println();
        System.out.print(table2);
        System.out.println();
        System.out.println("=== 전략별 강건성 지표: 비용 2배에서 OOS 수익 부호가 바뀐 종목 수 ===");
        for (StrategyDef<?> def : strategies) {
            System.out.printf("  %-12s : %d / %d 종목에서 부호 바뀜%n",
                    def.label(), flipCount.get(def.label()), evaluatedCount.get(def.label()));
        }
    }

    /** 와일드카드로 담긴 StrategyDef&lt;?&gt;를 캡처 변환으로 풀어 일반화된 run을 호출하는 헬퍼. */
    private <P> WalkForwardResult<P> runWalkForward(WalkForwardRunner runner, List<Candle> candles, StrategyDef<P> def) {
        return runner.run(candles, def.candidates(), def.factory(), TRAIN_SIZE, TEST_SIZE, def.warmupCandles(), INITIAL_CAPITAL);
    }

    /** 기본 비용모델의 네 항목(수수료·수수료·거래세·슬리피지)을 모두 같은 배수로 스케일한다. */
    private CostModel scaledCostModel(double multiplier) {
        CostModel base = CostModel.defaults();
        return new CostModel(
                base.buyFeePct() * multiplier,
                base.sellFeePct() * multiplier,
                base.sellTaxPct() * multiplier,
                base.slippagePct() * multiplier
        );
    }

    /** RealDataWalkForwardTest와 동일한 data/ 디렉터리 탐색 규칙. */
    private Path resolveDataDir() {
        String override = System.getProperty("autostock.data.dir");
        if (override != null) {
            Path path = Paths.get(override);
            return Files.isDirectory(path) ? path : null;
        }
        for (String candidate : List.of("../data", "data")) {
            Path path = Paths.get(candidate);
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        return null;
    }

    /** WalkForwardRunner와 동일한 창 개수 계산 규칙. */
    private int windowCount(int totalCandles, int trainSize, int testSize) {
        int count = 0;
        int start = 0;
        while (start + trainSize + testSize <= totalCandles) {
            count++;
            start += testSize;
        }
        return count;
    }

    /** RealDataWalkForwardTest와 동일한 buy&hold 기준선 계산(OOS 구간과 정확히 같은 기간). */
    private double buyHoldReturn(List<Candle> candles, int trainSize, int windowCount, int testSize) {
        int oosStart = trainSize;
        int oosEndExclusive = trainSize + windowCount * testSize;
        if (oosEndExclusive <= oosStart || oosEndExclusive > candles.size()) {
            return 0.0;
        }
        BigDecimal entryPrice = candles.get(oosStart).open();
        BigDecimal exitPrice = candles.get(oosEndExclusive - 1).close();
        if (entryPrice.signum() == 0) {
            return 0.0;
        }
        return exitPrice.subtract(entryPrice).divide(entryPrice, 12, RoundingMode.HALF_UP).doubleValue();
    }
}
