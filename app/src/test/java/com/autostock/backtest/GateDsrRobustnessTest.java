package com.autostock.backtest;

import com.autostock.common.event.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 게이트① 잠정 통과(C3, {@link RealDataRegimeVolExperimentTest}에서 N=4 교정DSR 0.992)의
 * "보수적 확정" 검증.
 *
 * <h2>왜 N=4가 아니라 N=6인가</h2>
 * {@link PerformanceCalculator#deflatedSharpeAcrossFamilies}의 N은 "OOS 성적을 놓고
 * 비교한 전략 계열의 수"여야 한다({@code deflatedSharpeAcrossFamilies}의 Javadoc 참고).
 * {@link RealDataRegimeVolExperimentTest}는 C0~C3 4개 변형만 놓고 비교했지만, 이 연구
 * 전체를 보면 {@link RealDataPortfolioGateTest}에서 이미 A(무필터돌파)/B(필터돌파)와도
 * OOS 성적을 나란히 비교했다 — 즉 실제로 "비교한 계열"은 A, B, C0, C1, C2, C3 총 6개다.
 * N을 4로 좁혀 잡으면 그만큼 비교 대상에서 제외된 계열이 있다는 뜻이고, 이는 다중검정
 * 편향을 과소 보정해 DSR을 실제보다 낙관적으로 부풀릴 위험이 있다. 이 테스트는 N=6
 * 기준으로 C3의 DSR을 재계산해 0.95 문턱을 여전히 넘는지 "보수적으로" 재확인한다.
 *
 * <h2>실행 로직 — 기존 두 테스트를 그대로 재사용</h2>
 * A/B는 {@link RealDataPortfolioGateTest}의 familyDefs/파이프라인을, C0~C3는
 * {@link RealDataRegimeVolExperimentTest}의 variantDefs/파이프라인을 복제해 <b>완전히
 * 동일한 파라미터</b>(trainSize=252, testSize=63, A/B 손절=-3%, k후보 5개, N후보 3개,
 * C 워밍업 221봉, 슬리브당 200만원×5종목)로 그대로 재실행한다 — 로직 변경 없이 두 기존
 * 테스트가 각각 만들어낸 수치가 이 테스트에서도 그대로 재현되는지가 중요한 교차검증
 * 포인트다(콘솔 출력의 "중간 검증" 절에서 확인).
 *
 * <p>데이터가 없거나 5종목 중 하나라도 부족하면 조용히 스킵한다(다른 Real* 테스트와 동일
 * 이유 — data/는 git에 커밋하지 않음). 성과 수치 자체는 assert하지 않고 "정상 산출됐는가"만
 * 확인하며, 실제 판단은 콘솔 표를 사람이 읽고 내린다.
 */
class GateDsrRobustnessTest {

    private static final Map<String, String> SYMBOLS = new LinkedHashMap<>();
    static {
        SYMBOLS.put("005930", "삼성전자");
        SYMBOLS.put("000660", "SK하이닉스");
        SYMBOLS.put("035420", "NAVER");
        SYMBOLS.put("035720", "카카오");
        SYMBOLS.put("069500", "KODEX200");
    }

    private static final String BASELINE_SYMBOL = "069500"; // KODEX200 — 국면 필터 지수이자 buy&hold 기준선

    private static final List<Double> K_CANDIDATES = List.of(0.3, 0.4, 0.5, 0.6, 0.7);
    private static final List<Integer> N_CANDIDATES = List.of(60, 120, 200);
    private static final Double STOP_LOSS_PCT = -0.03; // A/B 전용
    private static final double TARGET_VOL = 0.20; // C2/C3 변동성 타게팅 — 연 20%

    private static final int TRAIN_SIZE = 252;
    private static final int TEST_SIZE = 63;

    /** N 후보 최댓값(200) + 판단주기(21) = 221봉 워밍업 — C0~C3 공통. */
    private static final int MOMENTUM_WARMUP = 200 + 21;

    /** 슬리브당 200만원 × 5종목 = 총 1,000만원. */
    private static final BigDecimal SLEEVE_CAPITAL = new BigDecimal("2000000");

    private static final double GATE_MIN_DSR = 0.95;
    private static final double GATE_MAX_MDD = 0.15;

    private static final int TRADING_DAYS_PER_YEAR = 252;

    /** RealDataPortfolioGateTest 콘솔 출력과 교차검증할 기존 재현 대상 수치(%). */
    private static final double EXPECTED_A_OOS_PCT = -58.62;
    private static final double EXPECTED_B_OOS_PCT = -16.53;
    private static final double EXPECTED_C0_OOS_PCT = 466.05;
    /** RealDataRegimeVolExperimentTest 콘솔 출력과 교차검증할 C3의 N=4 교정DSR. */
    private static final double EXPECTED_C3_DSR_N4 = 0.992;

    private final CandleCsvLoader loader = new CandleCsvLoader();
    private final PerformanceCalculator performanceCalculator = new PerformanceCalculator();
    private final PortfolioBacktestRunner portfolioRunner = new PortfolioBacktestRunner();

    /** 계열/변형 하나를 나타내는 공통 정의 — factory는 항상 BacktestStrategy를 만드는 함수. */
    private record FamilyDef<P>(String label, List<P> candidates, Function<P, BacktestStrategy> factory,
                                 int warmupCandles) {
    }

    /** A/B — RealDataPortfolioGateTest.familyDefs()와 완전히 동일. */
    private List<FamilyDef<Double>> abFamilyDefs() {
        return List.of(
                new FamilyDef<>("A.무필터돌파", K_CANDIDATES,
                        (Function<Double, BacktestStrategy>) k -> new VolatilityBreakoutStrategy(k, STOP_LOSS_PCT), 0),
                new FamilyDef<>("B.필터돌파", K_CANDIDATES,
                        (Function<Double, BacktestStrategy>) k -> new FilteredBreakoutStrategy(k, STOP_LOSS_PCT, true, true), 0)
        );
    }

    /** C0~C3 — RealDataRegimeVolExperimentTest.variantDefs()와 완전히 동일. */
    private List<FamilyDef<Integer>> cFamilyDefs(Map<LocalDate, Boolean> regimeByDate) {
        return List.of(
                new FamilyDef<>("C0.기본모멘텀", N_CANDIDATES,
                        (Function<Integer, BacktestStrategy>) n -> new TimeSeriesMomentumStrategy(n), MOMENTUM_WARMUP),
                new FamilyDef<>("C1.+국면필터", N_CANDIDATES,
                        (Function<Integer, BacktestStrategy>) n -> new RegimeFilteredStrategy(new TimeSeriesMomentumStrategy(n), regimeByDate), MOMENTUM_WARMUP),
                new FamilyDef<>("C2.+변동성타게팅", N_CANDIDATES,
                        (Function<Integer, BacktestStrategy>) n -> new VolatilityTargetingStrategy(new TimeSeriesMomentumStrategy(n), TARGET_VOL), MOMENTUM_WARMUP),
                new FamilyDef<>("C3.+국면필터+변동성타게팅", N_CANDIDATES,
                        (Function<Integer, BacktestStrategy>) n -> new VolatilityTargetingStrategy(
                                new RegimeFilteredStrategy(new TimeSeriesMomentumStrategy(n), regimeByDate), TARGET_VOL), MOMENTUM_WARMUP)
        );
    }

    @Test
    void 전체6계열_기준_C3_DSR_N6_보수적재확인() {
        Path dataDir = resolveDataDir();
        assumeTrue(dataDir != null,
                "data/ 디렉터리를 찾지 못해 게이트① DSR 강건성 검증을 스킵함 — "
                        + "먼저 `python3 scripts/fetch_yahoo_daily.py` 로 data/ 를 채운 뒤 다시 실행할 것");

        Map<String, List<Candle>> candlesBySymbol = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : SYMBOLS.entrySet()) {
            String code = entry.getKey();
            Path csv = dataDir.resolve(code + ".csv");
            if (!Files.isRegularFile(csv)) {
                assumeTrue(false, "종목 " + code + "(" + entry.getValue() + ") CSV 없음 — 6계열 재검증에는 "
                        + "5종목 모두 필요하므로 전체 스킵함: " + csv);
                return;
            }
            List<Candle> candles = loader.load(csv);
            if (candles.size() < TRAIN_SIZE + TEST_SIZE) {
                assumeTrue(false, "종목 " + code + "(" + entry.getValue() + ") 캔들 수 부족(최소 "
                        + (TRAIN_SIZE + TEST_SIZE) + "개 필요, 실제 " + candles.size() + "개) — 전체 스킵함");
                return;
            }
            candlesBySymbol.put(code, candles);
        }

        BigDecimal totalCapital = SLEEVE_CAPITAL.multiply(BigDecimal.valueOf(candlesBySymbol.size()));

        // ── 국면 맵 — KODEX200 전체 이력에서 한 번만 계산해 C1/C3가 공유 ──
        List<Candle> kodexCandles = candlesBySymbol.get(BASELINE_SYMBOL);
        Map<LocalDate, Boolean> regimeByDate = MarketRegime.compute(kodexCandles);

        // ── 1) 6계열 전부 정의 (A, B, C0, C1, C2, C3 순서) ──
        List<FamilyDef<?>> families = new ArrayList<>();
        families.addAll(abFamilyDefs());
        families.addAll(cFamilyDefs(regimeByDate));
        assertTrue(families.size() == 6, "6계열이어야 함(A,B,C0,C1,C2,C3)");

        // ── 2) 계열별 포트폴리오 실행 ──
        List<PortfolioBacktestRunner.PortfolioResult> results = new ArrayList<>();
        for (FamilyDef<?> family : families) {
            PortfolioBacktestRunner.SleeveRunner sleeveRunner = sleeveRunnerFor(family);
            PortfolioBacktestRunner.PortfolioResult result =
                    portfolioRunner.run(candlesBySymbol, sleeveRunner, totalCapital, TRAIN_SIZE, TEST_SIZE);

            assertFalse(result.dailyReturns().isEmpty(), family.label() + ": 포트폴리오 OOS 일별수익률이 비어 있음");
            assertFalse(Double.isNaN(result.totalReturn()), family.label() + ": totalReturn이 NaN");
            assertFalse(Double.isNaN(result.cagr()), family.label() + ": cagr이 NaN");
            assertFalse(Double.isNaN(result.mdd()), family.label() + ": mdd가 NaN");
            assertFalse(Double.isNaN(result.sharpe()), family.label() + ": sharpe가 NaN");
            assertTrue(result.sleeveResults().size() == candlesBySymbol.size(),
                    family.label() + ": 슬리브 결과 수가 종목 수와 달라야 함");

            results.add(result);
        }

        // ── 3) 6계열 전체의 (비연율화) 일별 샤프비율 배열 — N=6 ──
        double[] sharpesDailyN6 = new double[families.size()];
        for (int i = 0; i < results.size(); i++) {
            sharpesDailyN6[i] = results.get(i).sharpe() / Math.sqrt(TRADING_DAYS_PER_YEAR);
        }

        // C0~C3만의 (비연율화) 일별 샤프비율 배열 — N=4 (RealDataRegimeVolExperimentTest 재현용)
        double[] sharpesDailyN4 = new double[4];
        for (int i = 0; i < 4; i++) {
            sharpesDailyN4[i] = sharpesDailyN6[2 + i]; // families 순서상 C0=index2..C3=index5
        }

        int c3Index = families.size() - 1; // C3는 마지막
        int c0Index = 2; // C0는 A,B 다음

        PortfolioBacktestRunner.PortfolioResult c3Result = results.get(c3Index);
        PortfolioBacktestRunner.PortfolioResult c0Result = results.get(c0Index);

        // ── 4) C3의 DSR 3가지 ──
        double c3DsrN4 = performanceCalculator.deflatedSharpeAcrossFamilies(c3Result.dailyReturns(), sharpesDailyN4);
        double c3DsrN6 = performanceCalculator.deflatedSharpeAcrossFamilies(c3Result.dailyReturns(), sharpesDailyN6);
        double c0DsrN6 = performanceCalculator.deflatedSharpeAcrossFamilies(c0Result.dailyReturns(), sharpesDailyN6);

        assertFalse(Double.isNaN(c3DsrN4), "C3 DSR(N=4)이 NaN");
        assertFalse(Double.isNaN(c3DsrN6), "C3 DSR(N=6)이 NaN");
        assertFalse(Double.isNaN(c0DsrN6), "C0 DSR(N=6)이 NaN");

        // ── 5) 게이트① 최종 판정(N=6 기준, C3) ──
        boolean oosPositive = c3Result.totalReturn() > 0;
        boolean dsrPass = c3DsrN6 > GATE_MIN_DSR;
        boolean mddPass = c3Result.mdd() < GATE_MAX_MDD;
        boolean gate1Pass = oosPositive && dsrPass && mddPass;

        printReport(families, results, sharpesDailyN6, c3DsrN4, c3DsrN6, c0DsrN6,
                c3Result, oosPositive, dsrPass, mddPass, gate1Pass);
    }

    private <P> PortfolioBacktestRunner.SleeveRunner sleeveRunnerFor(FamilyDef<P> family) {
        WalkForwardRunner walkForwardRunner = new WalkForwardRunner(CostModel.defaults());
        return (candles, sleeveCapital) -> walkForwardRunner.run(
                candles, family.candidates(), family.factory(), TRAIN_SIZE, TEST_SIZE, family.warmupCandles(), sleeveCapital);
    }

    private void printReport(List<FamilyDef<?>> families, List<PortfolioBacktestRunner.PortfolioResult> results,
                              double[] sharpesDailyN6, double c3DsrN4, double c3DsrN6, double c0DsrN6,
                              PortfolioBacktestRunner.PortfolioResult c3Result,
                              boolean oosPositive, boolean dsrPass, boolean mddPass, boolean gate1Pass) {
        System.out.println();
        System.out.println("=== 게이트① C3 DSR 보수적 확정 검증 — 전체 6계열(A,B,C0,C1,C2,C3) 기준 N=6 재계산 ===");
        System.out.println("(trainSize=" + TRAIN_SIZE + ", testSize=" + TEST_SIZE + ", 슬리브당 " + SLEEVE_CAPITAL
                + "원 × " + SYMBOLS.size() + "종목 = 총 " + SLEEVE_CAPITAL.multiply(BigDecimal.valueOf(SYMBOLS.size()))
                + "원, 비용모델=CostModel.defaults(), A/B 손절=-3%, k후보=" + K_CANDIDATES + ", N후보=" + N_CANDIDATES
                + ", C 워밍업=" + MOMENTUM_WARMUP + "봉, 목표변동성=" + (TARGET_VOL * 100) + "%)");

        System.out.println();
        System.out.println("--- [표 1] 6계열 포트폴리오 OOS 성과 (일별 비연율화 샤프 포함) ---");
        System.out.printf("%-24s | %10s | %10s | %8s | %8s | %10s%n",
                "전략계열", "OOS수익률", "CAGR", "MDD", "Sharpe(연율)", "Sharpe(일별)");
        System.out.println("-".repeat(90));
        for (int i = 0; i < families.size(); i++) {
            PortfolioBacktestRunner.PortfolioResult r = results.get(i);
            System.out.printf("%-24s | %9.2f%% | %9.2f%% | %7.2f%% | %10.3f | %10.5f%n",
                    families.get(i).label(), r.totalReturn() * 100, r.cagr() * 100, r.mdd() * 100,
                    r.sharpe(), sharpesDailyN6[i]);
        }

        System.out.println();
        System.out.println("--- [표 2] C3의 DSR 3가지 비교 ---");
        System.out.printf("  N=4(C0~C3만, 기존 RealDataRegimeVolExperimentTest 재현) : %.3f  (기존 재현값: %.3f)%n",
                c3DsrN4, EXPECTED_C3_DSR_N4);
        System.out.printf("  N=6(전체 6계열 A,B,C0,C1,C2,C3)                          : %.3f%n", c3DsrN6);
        System.out.printf("  [참고] C0의 DSR(N=6, 전체 6계열 기준)                     : %.3f%n", c0DsrN6);

        System.out.println();
        System.out.println("--- [중간 검증] A/B/C0 포트폴리오 수치가 기존 테스트 출력과 일치하는지(assert 아님, 출력만) ---");
        double aOosPct = results.get(0).totalReturn() * 100;
        double bOosPct = results.get(1).totalReturn() * 100;
        double c0OosPct = results.get(2).totalReturn() * 100;
        System.out.printf("  A.무필터돌파 OOS수익률: %.2f%% (기존 RealDataPortfolioGateTest 출력: %.2f%%) -> %s%n",
                aOosPct, EXPECTED_A_OOS_PCT, approxEquals(aOosPct, EXPECTED_A_OOS_PCT) ? "일치" : "불일치");
        System.out.printf("  B.필터돌파   OOS수익률: %.2f%% (기존 RealDataPortfolioGateTest 출력: %.2f%%) -> %s%n",
                bOosPct, EXPECTED_B_OOS_PCT, approxEquals(bOosPct, EXPECTED_B_OOS_PCT) ? "일치" : "불일치");
        System.out.printf("  C0.기본모멘텀 OOS수익률: %.2f%% (기존 RealDataRegimeVolExperimentTest 출력: %.2f%%) -> %s%n",
                c0OosPct, EXPECTED_C0_OOS_PCT, approxEquals(c0OosPct, EXPECTED_C0_OOS_PCT) ? "일치" : "불일치");

        System.out.println();
        System.out.println("--- [게이트① 최종 판정 — C3, N=6 기준] OOS수익>0 AND DSR(N=6)>0.95 AND MDD<15% ---");
        System.out.printf("  OOS수익률: %.2f%% -> %s%n", c3Result.totalReturn() * 100, oosPositive ? "O" : "X");
        System.out.printf("  DSR(N=6) : %.3f -> %s%n", c3DsrN6, dsrPass ? "O" : "X");
        System.out.printf("  MDD      : %.2f%% -> %s%n", c3Result.mdd() * 100, mddPass ? "O" : "X");
        System.out.printf("  게이트① 최종 판정(C3, N=6) = %s%n", gate1Pass ? "PASS" : "FAIL");
    }

    /** 부동소수/누적 반올림 차이를 감안한 근사 비교(±0.05%p). */
    private boolean approxEquals(double actualPct, double expectedPct) {
        return Math.abs(actualPct - expectedPct) < 0.05;
    }

    /** RealDataPortfolioGateTest/RealDataRegimeVolExperimentTest와 동일한 data/ 디렉터리 탐색 규칙. */
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
}
