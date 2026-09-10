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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * C4 가설(국면 롱/숏, PLAN ADR-8) — C3(VolTarget(RegimeFilter(TSM)))와 동일하되, 069500
 * 슬리브에서 국면필터가 OFF일 때 현금 대기 대신 KODEX 인버스(114800)를 전액 보유하는
 * 단일 trial 실험. 파라미터 스윕은 하지 않는다(trial 1건만 추가) — ADR-11에서 C3는
 * "동결"로 명시했으므로 이 실험은 C3 자체를 건드리지 않고 069500 슬리브의 실행 단계에만
 * 인버스 전환 로직({@link InverseSwitchWalkForwardRunner})을 얹는다.
 *
 * <h2>C3와 C4의 유일한 차이</h2>
 * <ul>
 *   <li>069500 슬리브: C3는 국면 OFF일 때 현금 대기, C4는 KODEX 인버스(114800) 전액 보유
 *       (ADR-8 안전규칙② — 인버스 보유 상한 20거래일 초과 시 현금 복귀, 국면 ON 복귀 시
 *       즉시 인버스 청산 후 원 전략 복귀). 신호·동결 파라미터는 완전히 동일(N후보 {60,120,200},
 *       목표변동성 20%, trainSize=252, testSize=63, 동일 CostModel).</li>
 *   <li>개별 종목 4개 슬리브(005930/000660/035420/035720): C3와 완전히 동일(OFF→현금) —
 *       {@link RealDataRegimeVolExperimentTest}의 C3와 똑같은 파이프라인으로 그대로 재실행한다.</li>
 * </ul>
 *
 * <h2>DSR 두 가지</h2>
 * <ol>
 *   <li>N=2(C3, C4만 놓고 비교) — 참고치. 이 실험 하나만 봤을 때의 상대 비교.</li>
 *   <li>N=7(A, B, C0, C1, C2, C3, C4 전체 계열) — {@link GateDsrRobustnessTest}의 N=6 계열에
 *       C4를 더한 보수적 확정 기준. 게이트①(OOS수익&gt;0, DSR&gt;0.95, MDD&lt;15%) 최종 판정은
 *       반드시 이 N=7 기준으로 내린다.</li>
 * </ol>
 *
 * <p>114800.csv는 실제 시장 가격(호가) 데이터이므로 인버스 ETF의 총보수·추적오차가 이미
 * 가격에 내재돼 있다 — 별도 조정을 하지 않는다({@link InverseSwitchWalkForwardRunner} 클래스
 * 설명 참고). 매매 비용은 다른 슬리브와 동일한 {@link CostModel#defaults()}만 적용한다.
 *
 * <p>데이터가 없으면(5종목 + 114800) 조용히 스킵한다(다른 Real* 테스트와 동일 이유). 성과
 * 수치는 assert하지 않고 산출 가능성 및 게이트 계산 무결성만 확인하며, 실제 판단은 콘솔
 * 표를 사람이 읽고 내린다 — 게이트 통과 전 strategy/라이브 코드 탑재는 하지 않는다.
 */
class RealDataC4InverseExperimentTest {

    private static final Map<String, String> SYMBOLS = new LinkedHashMap<>();
    static {
        SYMBOLS.put("005930", "삼성전자");
        SYMBOLS.put("000660", "SK하이닉스");
        SYMBOLS.put("035420", "NAVER");
        SYMBOLS.put("035720", "카카오");
        SYMBOLS.put("069500", "KODEX200");
    }

    private static final String BASELINE_SYMBOL = "069500"; // KODEX200 — 국면 필터 지수
    private static final String INVERSE_SYMBOL = "114800";
    private static final String INVERSE_NAME = "KODEX인버스";

    private static final List<Double> K_CANDIDATES = List.of(0.3, 0.4, 0.5, 0.6, 0.7); // A/B 전용
    private static final List<Integer> N_CANDIDATES = List.of(60, 120, 200);
    private static final Double STOP_LOSS_PCT = -0.03; // A/B 전용
    private static final double TARGET_VOL = 0.20; // 연 20%

    private static final int TRAIN_SIZE = 252;
    private static final int TEST_SIZE = 63;

    /** N 후보 최댓값(200) + 판단주기(21) = 221봉 워밍업 — 기존 C 계열과 동일 근거. */
    private static final int MOMENTUM_WARMUP = 200 + 21;

    /** 슬리브당 200만원 × 5종목 = 총 1,000만원. */
    private static final BigDecimal SLEEVE_CAPITAL = new BigDecimal("2000000");

    private static final double GATE_MIN_DSR = 0.95;
    private static final double GATE_MAX_MDD = 0.15;

    private static final int TRADING_DAYS_PER_YEAR = 252;

    private final CandleCsvLoader loader = new CandleCsvLoader();
    private final PerformanceCalculator performanceCalculator = new PerformanceCalculator();
    private final PortfolioBacktestRunner portfolioRunner = new PortfolioBacktestRunner();

    @Test
    void C4_국면롱숏_인버스전환_C3대비_비교_및_N7_보수적DSR게이트() {
        Path dataDir = resolveDataDir();
        assumeTrue(dataDir != null,
                "data/ 디렉터리를 찾지 못해 C4 인버스 실험을 스킵함 — "
                        + "먼저 `python3 scripts/fetch_yahoo_daily.py` 로 data/ 를 채운 뒤 다시 실행할 것");

        Map<String, List<Candle>> candlesBySymbol = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : SYMBOLS.entrySet()) {
            String code = entry.getKey();
            Path csv = dataDir.resolve(code + ".csv");
            if (!Files.isRegularFile(csv)) {
                assumeTrue(false, "종목 " + code + "(" + entry.getValue() + ") CSV 없음 — 5종목 모두 필요하므로 전체 스킵함: " + csv);
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

        Path inverseCsv = dataDir.resolve(INVERSE_SYMBOL + ".csv");
        if (!Files.isRegularFile(inverseCsv)) {
            assumeTrue(false, "인버스 종목 " + INVERSE_SYMBOL + "(" + INVERSE_NAME + ") CSV 없음 — C4는 이 데이터가 "
                    + "필수이므로 전체 스킵함: " + inverseCsv);
            return;
        }
        List<Candle> inverseCandles = loader.load(inverseCsv);
        if (inverseCandles.isEmpty()) {
            assumeTrue(false, "인버스 종목 " + INVERSE_SYMBOL + " 캔들이 비어 있음 — 전체 스킵함");
            return;
        }

        BigDecimal totalCapital = SLEEVE_CAPITAL.multiply(BigDecimal.valueOf(candlesBySymbol.size()));

        // ── 국면 맵 — KODEX200 전체 이력에서 한 번만 계산해 모든 계열이 공유 ──
        List<Candle> kodexCandles = candlesBySymbol.get(BASELINE_SYMBOL);
        Map<LocalDate, Boolean> regimeByDate = MarketRegime.compute(kodexCandles);

        // ── 1) C3 포트폴리오 — RealDataRegimeVolExperimentTest의 C3와 완전히 동일한 파이프라인 ──
        PortfolioBacktestRunner.SleeveRunner c3SleeveRunner = sleeveRunnerC3(regimeByDate);
        PortfolioBacktestRunner.PortfolioResult c3Result =
                portfolioRunner.run(candlesBySymbol, c3SleeveRunner, totalCapital, TRAIN_SIZE, TEST_SIZE);
        assertPortfolioResultValid(c3Result, "C3", candlesBySymbol.size());

        // ── 2) C4 포트폴리오 — 069500 슬리브만 InverseSwitchWalkForwardRunner로 대체 ──
        AtomicReference<InverseSwitchWalkForwardRunner.InverseSwitchResult> c4InverseStats = new AtomicReference<>();
        PortfolioBacktestRunner.SleeveRunner c4SleeveRunner =
                sleeveRunnerC4(regimeByDate, inverseCandles, c4InverseStats);
        PortfolioBacktestRunner.PortfolioResult c4Result =
                portfolioRunner.run(candlesBySymbol, c4SleeveRunner, totalCapital, TRAIN_SIZE, TEST_SIZE);
        assertPortfolioResultValid(c4Result, "C4", candlesBySymbol.size());

        InverseSwitchWalkForwardRunner.InverseSwitchResult inverseStats = c4InverseStats.get();
        assertTrue(inverseStats != null, "C4 069500 슬리브의 인버스 전환 통계가 기록되지 않음");

        // ── 3) DSR ① — C3/C4 2계열 참고치 ──
        double[] sharpesDailyN2 = {
                c3Result.sharpe() / Math.sqrt(TRADING_DAYS_PER_YEAR),
                c4Result.sharpe() / Math.sqrt(TRADING_DAYS_PER_YEAR)
        };
        double c4DsrN2 = performanceCalculator.deflatedSharpeAcrossFamilies(c4Result.dailyReturns(), sharpesDailyN2);
        assertFalse(Double.isNaN(c4DsrN2), "C4 DSR(N=2)이 NaN");

        // ── 4) DSR ② — 보수적 기준 전 계열 N=7(A,B,C0,C1,C2,C3,C4) ──
        List<FamilyRun> abc0c1c2 = runAbC0C1C2Families(candlesBySymbol, regimeByDate, totalCapital);
        List<PortfolioBacktestRunner.PortfolioResult> n7Results = new ArrayList<>();
        List<String> n7Labels = new ArrayList<>();
        for (FamilyRun f : abc0c1c2) {
            n7Results.add(f.result());
            n7Labels.add(f.label());
        }
        n7Results.add(c3Result);
        n7Labels.add("C3.+국면필터+변동성타게팅");
        n7Results.add(c4Result);
        n7Labels.add("C4.+인버스국면롱숏(ADR-8)");
        assertTrue(n7Results.size() == 7, "N=7 전 계열이어야 함(A,B,C0,C1,C2,C3,C4)");

        double[] sharpesDailyN7 = new double[7];
        for (int i = 0; i < 7; i++) {
            sharpesDailyN7[i] = n7Results.get(i).sharpe() / Math.sqrt(TRADING_DAYS_PER_YEAR);
        }
        int c4IndexN7 = 6;
        double c4DsrN7 = performanceCalculator.deflatedSharpeAcrossFamilies(c4Result.dailyReturns(), sharpesDailyN7);
        assertFalse(Double.isNaN(c4DsrN7), "C4 DSR(N=7)이 NaN");

        boolean oosPositive = c4Result.totalReturn() > 0;
        boolean dsrPass = c4DsrN7 > GATE_MIN_DSR;
        boolean mddPass = c4Result.mdd() < GATE_MAX_MDD;
        boolean gate1Pass = oosPositive && dsrPass && mddPass;

        // ── 5) 069500 슬리브 단독 비교 — C3(OFF=현금) vs C4(OFF=인버스) 효과 분리 ──
        PortfolioBacktestRunner.SleeveResult c3KodexSleeve = findSleeve(c3Result, BASELINE_SYMBOL);
        PortfolioBacktestRunner.SleeveResult c4KodexSleeve = findSleeve(c4Result, BASELINE_SYMBOL);

        printReport(c3Result, c4Result, c3KodexSleeve, c4KodexSleeve, inverseStats,
                c4DsrN2, n7Labels, n7Results, sharpesDailyN7, c4DsrN7, c4IndexN7,
                oosPositive, dsrPass, mddPass, gate1Pass);
    }

    // ───────────────────────────── 슬리브 러너 구성 ─────────────────────────────

    /** RealDataRegimeVolExperimentTest.variantDefs()의 C3와 완전히 동일한 팩토리. */
    private PortfolioBacktestRunner.SleeveRunner sleeveRunnerC3(Map<LocalDate, Boolean> regimeByDate) {
        WalkForwardRunner walkForwardRunner = new WalkForwardRunner(CostModel.defaults());
        Function<Integer, BacktestStrategy> factory = n -> new VolatilityTargetingStrategy(
                new RegimeFilteredStrategy(new TimeSeriesMomentumStrategy(n), regimeByDate), TARGET_VOL);
        return (candles, sleeveCapital) -> walkForwardRunner.run(
                candles, N_CANDIDATES, factory, TRAIN_SIZE, TEST_SIZE, MOMENTUM_WARMUP, sleeveCapital);
    }

    /**
     * C4 — 069500 슬리브만 {@link InverseSwitchWalkForwardRunner}로 대체하고, 나머지 4종목은
     * C3와 완전히 동일한 표준 WalkForwardRunner를 그대로 쓴다. {@link PortfolioBacktestRunner}는
     * 종목별로 같은 SleeveRunner 하나를 호출하므로, 이 람다 안에서 candles의 첫 캔들 심볼을
     * 보고 069500인지 아닌지로 분기한다(Candle에 심볼이 실려 있으므로 별도 매핑이 필요 없다).
     */
    private PortfolioBacktestRunner.SleeveRunner sleeveRunnerC4(
            Map<LocalDate, Boolean> regimeByDate, List<Candle> inverseCandles,
            AtomicReference<InverseSwitchWalkForwardRunner.InverseSwitchResult> statsHolder) {
        WalkForwardRunner walkForwardRunner = new WalkForwardRunner(CostModel.defaults());
        Function<Integer, BacktestStrategy> c3Factory = n -> new VolatilityTargetingStrategy(
                new RegimeFilteredStrategy(new TimeSeriesMomentumStrategy(n), regimeByDate), TARGET_VOL);
        InverseSwitchWalkForwardRunner inverseRunner = new InverseSwitchWalkForwardRunner(CostModel.defaults());

        return (candles, sleeveCapital) -> {
            String symbol = candles.isEmpty() ? "" : candles.get(0).symbol();
            if (BASELINE_SYMBOL.equals(symbol)) {
                InverseSwitchWalkForwardRunner.InverseSwitchResult result = inverseRunner.run(
                        candles, inverseCandles, N_CANDIDATES, regimeByDate, TARGET_VOL,
                        TRAIN_SIZE, TEST_SIZE, MOMENTUM_WARMUP, sleeveCapital);
                statsHolder.set(result);
                return result.walkForwardResult();
            }
            return walkForwardRunner.run(candles, N_CANDIDATES, c3Factory, TRAIN_SIZE, TEST_SIZE, MOMENTUM_WARMUP, sleeveCapital);
        };
    }

    private record FamilyRun(String label, PortfolioBacktestRunner.PortfolioResult result) {
    }

    /** GateDsrRobustnessTest의 A/B/C0/C1/C2 팩토리·파이프라인을 그대로 복제해 재실행한다. */
    private List<FamilyRun> runAbC0C1C2Families(Map<String, List<Candle>> candlesBySymbol,
                                                 Map<LocalDate, Boolean> regimeByDate, BigDecimal totalCapital) {
        record Def(String label, List<?> candidates, Function<Object, BacktestStrategy> factory, int warmup) {
        }
        List<Def> defs = new ArrayList<>();
        defs.add(new Def("A.무필터돌파", K_CANDIDATES,
                p -> new VolatilityBreakoutStrategy((Double) p, STOP_LOSS_PCT), 0));
        defs.add(new Def("B.필터돌파", K_CANDIDATES,
                p -> new FilteredBreakoutStrategy((Double) p, STOP_LOSS_PCT, true, true), 0));
        defs.add(new Def("C0.기본모멘텀", N_CANDIDATES,
                p -> new TimeSeriesMomentumStrategy((Integer) p), MOMENTUM_WARMUP));
        defs.add(new Def("C1.+국면필터", N_CANDIDATES,
                p -> new RegimeFilteredStrategy(new TimeSeriesMomentumStrategy((Integer) p), regimeByDate), MOMENTUM_WARMUP));
        defs.add(new Def("C2.+변동성타게팅", N_CANDIDATES,
                p -> new VolatilityTargetingStrategy(new TimeSeriesMomentumStrategy((Integer) p), TARGET_VOL), MOMENTUM_WARMUP));

        List<FamilyRun> runs = new ArrayList<>();
        for (Def def : defs) {
            WalkForwardRunner walkForwardRunner = new WalkForwardRunner(CostModel.defaults());
            @SuppressWarnings("unchecked")
            List<Object> candidates = (List<Object>) def.candidates();
            PortfolioBacktestRunner.SleeveRunner sleeveRunner = (candles, sleeveCapital) -> walkForwardRunner.run(
                    candles, candidates, def.factory(), TRAIN_SIZE, TEST_SIZE, def.warmup(), sleeveCapital);
            PortfolioBacktestRunner.PortfolioResult result =
                    portfolioRunner.run(candlesBySymbol, sleeveRunner, totalCapital, TRAIN_SIZE, TEST_SIZE);
            assertPortfolioResultValid(result, def.label(), candlesBySymbol.size());
            runs.add(new FamilyRun(def.label(), result));
        }
        return runs;
    }

    private void assertPortfolioResultValid(PortfolioBacktestRunner.PortfolioResult result, String label, int expectedSleeveCount) {
        assertFalse(result.dailyReturns().isEmpty(), label + ": 포트폴리오 OOS 일별수익률이 비어 있음");
        assertFalse(Double.isNaN(result.totalReturn()), label + ": totalReturn이 NaN");
        assertFalse(Double.isNaN(result.cagr()), label + ": cagr이 NaN");
        assertFalse(Double.isNaN(result.mdd()), label + ": mdd가 NaN");
        assertFalse(Double.isNaN(result.sharpe()), label + ": sharpe가 NaN");
        assertTrue(result.sleeveResults().size() == expectedSleeveCount, label + ": 슬리브 결과 수가 종목 수와 달라야 함");
    }

    private PortfolioBacktestRunner.SleeveResult findSleeve(PortfolioBacktestRunner.PortfolioResult result, String symbol) {
        for (PortfolioBacktestRunner.SleeveResult sleeve : result.sleeveResults()) {
            if (sleeve.symbol().equals(symbol)) {
                return sleeve;
            }
        }
        throw new IllegalStateException("슬리브 '" + symbol + "'를 찾지 못함");
    }

    // ───────────────────────────── 리포트 출력 ─────────────────────────────

    private void printReport(PortfolioBacktestRunner.PortfolioResult c3Result, PortfolioBacktestRunner.PortfolioResult c4Result,
                              PortfolioBacktestRunner.SleeveResult c3KodexSleeve, PortfolioBacktestRunner.SleeveResult c4KodexSleeve,
                              InverseSwitchWalkForwardRunner.InverseSwitchResult inverseStats,
                              double c4DsrN2, List<String> n7Labels, List<PortfolioBacktestRunner.PortfolioResult> n7Results,
                              double[] sharpesDailyN7, double c4DsrN7, int c4IndexN7,
                              boolean oosPositive, boolean dsrPass, boolean mddPass, boolean gate1Pass) {
        System.out.println();
        System.out.println("=== C4(국면 롱/숏, ADR-8) 실험 — C3 대비 069500 슬리브 인버스(114800) 전환 효과 ===");
        System.out.println("(trainSize=" + TRAIN_SIZE + ", testSize=" + TEST_SIZE + ", 슬리브당 " + SLEEVE_CAPITAL
                + "원 × " + SYMBOLS.size() + "종목, 비용모델=CostModel.defaults(), N후보=" + N_CANDIDATES
                + ", 목표변동성=" + (TARGET_VOL * 100) + "%, 인버스보유상한=20거래일)");

        System.out.println();
        System.out.println("--- [표 1] C3 vs C4 포트폴리오 OOS 성과 ---");
        System.out.printf("%-10s | %10s | %10s | %8s | %10s | %10s%n",
                "변형", "OOS수익률", "CAGR", "MDD", "Sharpe(연율)", "Sharpe(일별)");
        System.out.println("-".repeat(80));
        printPortfolioRow("C3(OFF=현금)", c3Result);
        printPortfolioRow("C4(OFF=인버스)", c4Result);

        System.out.println();
        System.out.println("--- [표 2] 069500 슬리브 단독 비교 — C3(OFF=현금) vs C4(OFF=인버스) 효과 분리 ---");
        System.out.printf("%-16s | %10s | %8s%n", "슬리브", "슬리브수익률", "슬리브MDD");
        System.out.println("-".repeat(50));
        BacktestResult c3KodexOos = c3KodexSleeve.walkForwardResult().oosResult();
        BacktestResult c4KodexOos = c4KodexSleeve.walkForwardResult().oosResult();
        System.out.printf("%-16s | %9.2f%% | %7.2f%%%n", "069500(C3)", c3KodexOos.totalReturn() * 100, c3KodexOos.mdd() * 100);
        System.out.printf("%-16s | %9.2f%% | %7.2f%%%n", "069500(C4)", c4KodexOos.totalReturn() * 100, c4KodexOos.mdd() * 100);
        System.out.printf("  차이(C4-C3): 수익률 %+.2f%%p, MDD %+.2f%%p%n",
                (c4KodexOos.totalReturn() - c3KodexOos.totalReturn()) * 100,
                (c4KodexOos.mdd() - c3KodexOos.mdd()) * 100);

        System.out.println();
        System.out.println("--- [표 3] 인버스(114800) 보유 통계 (C4, 069500 슬리브, OOS 구간만 집계) ---");
        System.out.printf("  인버스 보유일수 합계 : %d거래일%n", inverseStats.inverseHoldDaysTotal());
        System.out.printf("  인버스 신규 진입 횟수 : %d회%n", inverseStats.inverseEntryCount());
        System.out.printf("  20거래일 상한 초과 강제청산(현금복귀) 횟수 : %d회%n", inverseStats.inverseCapEvents());

        System.out.println();
        System.out.println("--- [DSR ①] C3/C4 2계열 참고치(N=2) ---");
        System.out.printf("  C4 DSR(N=2, C3·C4만 비교) : %.3f%n", c4DsrN2);

        System.out.println();
        System.out.println("--- [DSR ②] 보수적 기준 — 전 계열 N=7(A,B,C0,C1,C2,C3,C4) ---");
        System.out.printf("%-24s | %10s | %10s | %8s | %10s | %10s%n",
                "전략계열", "OOS수익률", "CAGR", "MDD", "Sharpe(연율)", "Sharpe(일별)");
        System.out.println("-".repeat(95));
        for (int i = 0; i < n7Labels.size(); i++) {
            PortfolioBacktestRunner.PortfolioResult r = n7Results.get(i);
            System.out.printf("%-24s | %9.2f%% | %9.2f%% | %7.2f%% | %10.3f | %10.5f%n",
                    n7Labels.get(i), r.totalReturn() * 100, r.cagr() * 100, r.mdd() * 100, r.sharpe(), sharpesDailyN7[i]);
        }
        System.out.printf("%n  C4 DSR(N=7, 전 계열 기준) : %.3f%n", c4DsrN7);

        System.out.println();
        System.out.println("--- [게이트① 최종 판정 — C4, N=7 기준] OOS수익>0 AND DSR(N=7)>0.95 AND MDD<15% ---");
        System.out.printf("  OOS수익률: %.2f%% -> %s%n", n7Results.get(c4IndexN7).totalReturn() * 100, oosPositive ? "O" : "X");
        System.out.printf("  DSR(N=7) : %.3f -> %s%n", c4DsrN7, dsrPass ? "O" : "X");
        System.out.printf("  MDD      : %.2f%% -> %s%n", n7Results.get(c4IndexN7).mdd() * 100, mddPass ? "O" : "X");
        System.out.printf("  게이트① 최종 판정(C4, N=7) = %s%n", gate1Pass ? "PASS" : "FAIL");
    }

    private void printPortfolioRow(String label, PortfolioBacktestRunner.PortfolioResult r) {
        double sharpeDaily = r.sharpe() / Math.sqrt(TRADING_DAYS_PER_YEAR);
        System.out.printf("%-10s | %9.2f%% | %9.2f%% | %7.2f%% | %10.3f | %10.5f%n",
                label, r.totalReturn() * 100, r.cagr() * 100, r.mdd() * 100, r.sharpe(), sharpeDaily);
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
