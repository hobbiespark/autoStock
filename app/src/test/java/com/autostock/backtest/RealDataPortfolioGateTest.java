package com.autostock.backtest;

import com.autostock.common.event.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 게이트① "재판정" — 종목 단위가 아니라 5종목 슬리브 포트폴리오 단위로, 그리고
 * {@link RealDataStrategyComparisonTest}의 trial-기반 DSR이 아니라 계열 간 비교를 반영한
 * 교정 DSR({@link PerformanceCalculator#deflatedSharpeAcrossFamilies})로 게이트①을 다시 판정한다.
 *
 * <h2>왜 "재판정"인가 — 기존 두 실데이터 테스트와 무엇이 다른가</h2>
 * <ul>
 *   <li>{@link RealDataWalkForwardTest}/{@link RealDataStrategyComparisonTest}는 <b>종목 하나짜리
 *       단일 슬리브</b> 성과와, <b>train 단계 파라미터 선택 다중검정만 보정한</b> DSR
 *       (trials = 후보수×창수)로 게이트를 매겼다. {@link PerformanceCalculator#deflatedSharpeRatio}의
 *       Javadoc이 명시하듯 이건 최종 게이트 판정에 쓸 값이 아니다 — 후보수×창수가 커지면 DSR이
 *       거의 항상 0에 가깝게 나와서 방법론적으로 왜곡된 판정이 된다.</li>
 *   <li>이 테스트는 {@link PortfolioBacktestRunner}로 5종목을 슬리브(균등분할, 리밸런싱 없음)
 *       방식으로 합산한 <b>포트폴리오</b> OOS 성과를 보고, DSR도 "OOS 레벨에서 실제로 비교한
 *       전략 계열 수"(N=3: A/B/C)를 반영한 {@link PerformanceCalculator#deflatedSharpeAcrossFamilies}로
 *       계산한다 — PLAN이 요구하는 최종 게이트①(OOS 수익 &gt; 0, DSR &gt; 0.95, MDD &lt; 15%) 판정에
 *       방법론적으로 맞는 값이다.</li>
 * </ul>
 *
 * <h2>비교 대상 3개 전략 "계열"</h2>
 * {@link RealDataStrategyComparisonTest}와 동일한 3안(A 무필터돌파/B 필터돌파/C 시계열모멘텀)을
 * 같은 파이프라인({@link WalkForwardRunner}의 일반화된 run)으로 실행한다. 세 계열 각각의
 * "포트폴리오 OOS 일별 수익률"에서 계산한 (비연율화) 샤프비율 3개를 배열로 묶어
 * {@code deflatedSharpeAcrossFamilies}에 N=3으로 넘긴다 — "다른 두 계열과 비교해 이 계열을
 * 고를 확률"까지 반영한 교정 DSR이다.
 *
 * <h2>자본 배분</h2>
 * 슬리브당 200만원 × 5종목 = 총 1,000만원({@link PortfolioBacktestRunner#run}이 내부에서
 * 종목 수로 균등 분할하므로, 여기서는 총액 1,000만원만 넘긴다). trainSize=252, testSize=63,
 * 비용모델은 {@link CostModel#defaults()}. C(시계열모멘텀)는 N후보 최댓값(200)+판단주기(21) =
 * 221봉 워밍업을 준다({@link RealDataStrategyComparisonTest}와 동일 근거).
 *
 * <p>데이터가 없거나 5종목 중 하나라도 부족하면 조용히 스킵한다(다른 Real* 테스트와 동일
 * 이유 — data/는 git에 커밋하지 않음). 성과 수치 자체는 assert하지 않고 "정상 산출됐는가"만
 * 확인하며, 실제 판단은 콘솔 표를 사람이 읽고 내린다.
 */
class RealDataPortfolioGateTest {

    private static final Map<String, String> SYMBOLS = new LinkedHashMap<>();
    static {
        SYMBOLS.put("005930", "삼성전자");
        SYMBOLS.put("000660", "SK하이닉스");
        SYMBOLS.put("035420", "NAVER");
        SYMBOLS.put("035720", "카카오");
        SYMBOLS.put("069500", "KODEX200");
    }

    private static final String BASELINE_SYMBOL = "069500"; // KODEX200 — buy&hold 기준선

    private static final List<Double> K_CANDIDATES = List.of(0.3, 0.4, 0.5, 0.6, 0.7);
    private static final List<Integer> N_CANDIDATES = List.of(60, 120, 200);
    private static final Double STOP_LOSS_PCT = -0.03; // A/B 전용

    private static final int TRAIN_SIZE = 252;
    private static final int TEST_SIZE = 63;

    /** 슬리브당 200만원 × 5종목 = 총 1,000만원(PortfolioBacktestRunner가 내부에서 종목 수로 균등 분할). */
    private static final BigDecimal SLEEVE_CAPITAL = new BigDecimal("2000000");

    private static final double GATE_MIN_DSR = 0.95;
    private static final double GATE_MAX_MDD = 0.15;

    /** 연환산 거래일수 — PerformanceCalculator와 동일(비연율화 샤프를 역산하는 데 씀). */
    private static final int TRADING_DAYS_PER_YEAR = 252;

    private final CandleCsvLoader loader = new CandleCsvLoader();
    private final PerformanceCalculator performanceCalculator = new PerformanceCalculator();
    private final PortfolioBacktestRunner portfolioRunner = new PortfolioBacktestRunner();

    /** 전략 계열 정의 — RealDataStrategyComparisonTest.StrategyDef와 동일한 발상. */
    private record FamilyDef<P>(String label, List<P> candidates, Function<P, BacktestStrategy> factory,
                                 int warmupCandles) {
    }

    private List<FamilyDef<?>> familyDefs() {
        int momentumWarmup = N_CANDIDATES.stream().mapToInt(Integer::intValue).max().orElseThrow() + 21; // = 221
        return List.of(
                new FamilyDef<>("A.무필터돌파", K_CANDIDATES,
                        (Function<Double, BacktestStrategy>) k -> new VolatilityBreakoutStrategy(k, STOP_LOSS_PCT), 0),
                new FamilyDef<>("B.필터돌파", K_CANDIDATES,
                        (Function<Double, BacktestStrategy>) k -> new FilteredBreakoutStrategy(k, STOP_LOSS_PCT, true, true), 0),
                new FamilyDef<>("C.시계열모멘텀", N_CANDIDATES,
                        (Function<Integer, BacktestStrategy>) n -> new TimeSeriesMomentumStrategy(n), momentumWarmup)
        );
    }

    @Test
    void 다섯종목_슬리브_포트폴리오로_세_전략계열_교정DSR_게이트1_재판정() {
        Path dataDir = resolveDataDir();
        assumeTrue(dataDir != null,
                "data/ 디렉터리를 찾지 못해 게이트① 재판정을 스킵함 — "
                        + "먼저 `python3 scripts/fetch_yahoo_daily.py` 로 data/ 를 채운 뒤 다시 실행할 것");

        Map<String, List<Candle>> candlesBySymbol = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : SYMBOLS.entrySet()) {
            String code = entry.getKey();
            Path csv = dataDir.resolve(code + ".csv");
            if (!Files.isRegularFile(csv)) {
                assumeTrue(false, "종목 " + code + "(" + entry.getValue() + ") CSV 없음 — 5종목 포트폴리오 재판정에는 "
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

        List<FamilyDef<?>> families = familyDefs();

        // ── 1) 계열별 포트폴리오 실행 ──
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

        // ── 2) 계열 간 비교용 (비연율화) 일별 샤프비율 배열 — N=3 ──
        double[] familySharpesDaily = new double[families.size()];
        for (int i = 0; i < results.size(); i++) {
            familySharpesDaily[i] = results.get(i).sharpe() / Math.sqrt(TRADING_DAYS_PER_YEAR);
        }

        // ── 3) 계열별 교정 DSR + 게이트① 판정 ──
        double[] calibratedDsr = new double[families.size()];
        boolean[] gatePass = new boolean[families.size()];
        for (int i = 0; i < results.size(); i++) {
            PortfolioBacktestRunner.PortfolioResult result = results.get(i);
            double dsr = performanceCalculator.deflatedSharpeAcrossFamilies(result.dailyReturns(), familySharpesDaily);
            assertFalse(Double.isNaN(dsr), families.get(i).label() + ": 교정 DSR이 NaN");
            calibratedDsr[i] = dsr;
            gatePass[i] = result.totalReturn() > 0 && dsr > GATE_MIN_DSR && result.mdd() < GATE_MAX_MDD;
        }

        // ── 4) KODEX200 buy&hold 기준선(동일 기간, 전략 무관) ──
        List<Candle> baselineCandles = candlesBySymbol.get(BASELINE_SYMBOL);
        int baselineWindowCount = windowCount(baselineCandles.size(), TRAIN_SIZE, TEST_SIZE);
        double baselineBuyHold = buyHoldReturn(baselineCandles, TRAIN_SIZE, baselineWindowCount, TEST_SIZE);

        // ── 콘솔 출력 ──
        printReport(families, results, calibratedDsr, gatePass, baselineBuyHold);
    }

    /** family 하나를 WalkForwardRunner의 일반화된 run에 위임하는 SleeveRunner로 감싼다. */
    private <P> PortfolioBacktestRunner.SleeveRunner sleeveRunnerFor(FamilyDef<P> family) {
        WalkForwardRunner walkForwardRunner = new WalkForwardRunner(CostModel.defaults());
        return (candles, sleeveCapital) -> walkForwardRunner.run(
                candles, family.candidates(), family.factory(), TRAIN_SIZE, TEST_SIZE, family.warmupCandles(), sleeveCapital);
    }

    private void printReport(List<FamilyDef<?>> families, List<PortfolioBacktestRunner.PortfolioResult> results,
                              double[] calibratedDsr, boolean[] gatePass, double baselineBuyHold) {
        System.out.println();
        System.out.println("=== 게이트① 재판정 — 5종목 슬리브 포트폴리오 × 3전략계열 (교정 DSR, N=3) ===");
        System.out.println("(trainSize=" + TRAIN_SIZE + ", testSize=" + TEST_SIZE + ", 슬리브당 " + SLEEVE_CAPITAL
                + "원 × " + SYMBOLS.size() + "종목 = 총 " + SLEEVE_CAPITAL.multiply(BigDecimal.valueOf(SYMBOLS.size()))
                + "원, 비용모델=CostModel.defaults(), A/B 손절=-3%, k후보=" + K_CANDIDATES + ", N후보=" + N_CANDIDATES + ")");

        System.out.println();
        System.out.println("--- [표 1] 계열별 포트폴리오 OOS 성과 및 게이트① 판정 ---");
        System.out.printf("%-14s | %10s | %10s | %8s | %8s | %8s | %6s%n",
                "전략계열", "OOS수익률", "CAGR", "MDD", "Sharpe", "교정DSR", "게이트①");
        System.out.println("-".repeat(90));
        for (int i = 0; i < families.size(); i++) {
            PortfolioBacktestRunner.PortfolioResult r = results.get(i);
            System.out.printf("%-14s | %9.2f%% | %9.2f%% | %7.2f%% | %8.3f | %8.3f | %s%n",
                    families.get(i).label(), r.totalReturn() * 100, r.cagr() * 100, r.mdd() * 100,
                    r.sharpe(), calibratedDsr[i], gatePass[i] ? "PASS" : "FAIL");
        }

        System.out.println();
        System.out.println("--- [표 2] 계열별 종목 슬리브 요약(수익률/MDD) ---");
        System.out.printf("%-14s | %-14s | %10s | %8s%n", "전략계열", "종목", "슬리브수익률", "슬리브MDD");
        System.out.println("-".repeat(70));
        for (int i = 0; i < families.size(); i++) {
            PortfolioBacktestRunner.PortfolioResult r = results.get(i);
            for (PortfolioBacktestRunner.SleeveResult sleeve : r.sleeveResults()) {
                BacktestResult oos = sleeve.walkForwardResult().oosResult();
                String name = SYMBOLS.getOrDefault(sleeve.symbol(), sleeve.symbol());
                System.out.printf("%-14s | %-14s | %9.2f%% | %7.2f%%%n",
                        families.get(i).label(), sleeve.symbol() + "(" + name + ")",
                        oos.totalReturn() * 100, oos.mdd() * 100);
            }
        }

        System.out.println();
        System.out.println("--- [기준선] KODEX200(069500) buy&hold, 동일 OOS 기간 ---");
        System.out.printf("  단순보유 총수익률: %.2f%%%n", baselineBuyHold * 100);

        System.out.println();
        System.out.println("--- [최종 판정] 게이트① = OOS수익>0 AND 교정DSR>0.95 AND MDD<15% ---");
        for (int i = 0; i < families.size(); i++) {
            PortfolioBacktestRunner.PortfolioResult r = results.get(i);
            System.out.printf("  %-14s 게이트① = %s  (OOS수익>0: %s, 교정DSR>0.95: %s(%.3f), MDD<15%%: %s(%.2f%%))%n",
                    families.get(i).label(), gatePass[i] ? "PASS" : "FAIL",
                    r.totalReturn() > 0 ? "O" : "X",
                    calibratedDsr[i] > GATE_MIN_DSR ? "O" : "X", calibratedDsr[i],
                    r.mdd() < GATE_MAX_MDD ? "O" : "X", r.mdd() * 100);
        }
    }

    /** RealDataWalkForwardTest/RealDataStrategyComparisonTest와 동일한 data/ 디렉터리 탐색 규칙. */
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
