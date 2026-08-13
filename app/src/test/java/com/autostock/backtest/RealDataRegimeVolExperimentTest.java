package com.autostock.backtest;

import com.autostock.common.event.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * 시계열 모멘텀(C) 전략 계열의 MDD 절감 실험 — 시장 국면 필터(SMA200)와 변동성 타게팅을
 * 얹은 4개 변형을 5종목 슬리브 포트폴리오에서 비교한다.
 *
 * <h2>배경</h2>
 * {@link RealDataPortfolioGateTest}에서 C(시계열모멘텀)는 OOS +466%, MDD 29.2%,
 * 교정DSR 0.831로 게이트①(MDD&lt;15%, DSR&gt;0.95)에 미달했다. 이 테스트는 C에 (1) 시장
 * 국면 필터, (2) 변동성 타게팅을 각각/동시에 적용해 MDD가 실제로 줄어드는지, 그리고
 * 게이트①을 통과할 수 있는지를 확인한다.
 *
 * <h2>4개 변형</h2>
 * <ul>
 *   <li>C0: 기본 시계열 모멘텀(순수) — {@link RealDataPortfolioGateTest}의 "C.시계열모멘텀"과
 *       완전히 동일한 파이프라인·파라미터로 실행하므로, 두 테스트의 콘솔 출력 수치가
 *       일치해야 한다(교차검증용 기준선).</li>
 *   <li>C1: C0 + {@link RegimeFilteredStrategy}(KODEX200 SMA200 국면 필터)</li>
 *   <li>C2: C0 + {@link VolatilityTargetingStrategy}(목표 연변동성 20%)</li>
 *   <li>C3: C0 + 국면 필터 + 변동성 타게팅(국면 필터가 먼저 "할지 말지"를 정하고, 그 위에서
 *       변동성 타게팅이 "얼마나"를 정하는 순서로 데코레이터를 중첩한다)</li>
 * </ul>
 *
 * <h2>국면 맵 — 왜 전체 KODEX200 이력에서 한 번만 계산하는가</h2>
 * {@link MarketRegime#compute}는 날짜 d의 판정에 오직 d 이전 데이터만 쓰므로(룩어헤드 없음),
 * KODEX200 전체 캔들에서 한 번 계산해둔 맵을 모든 슬리브·모든 walk-forward 창이 공유해도
 * 문제가 없다 — 어떤 시점에 "미리 계산해뒀는지"가 아니라 "그 날짜의 값이 그 날짜 이전
 * 데이터만으로 결정되는지"가 룩어헤드 여부를 가른다.
 *
 * <p>데이터가 없거나 5종목 중 하나라도 부족하면 조용히 스킵한다({@link RealDataPortfolioGateTest}와
 * 동일 이유). 성과 수치는 assert하지 않고 산출 가능성만 확인하며, 실제 판단은 콘솔 표를
 * 사람이 읽고 내린다.
 */
class RealDataRegimeVolExperimentTest {

    private static final Map<String, String> SYMBOLS = new LinkedHashMap<>();
    static {
        SYMBOLS.put("005930", "삼성전자");
        SYMBOLS.put("000660", "SK하이닉스");
        SYMBOLS.put("035420", "NAVER");
        SYMBOLS.put("035720", "카카오");
        SYMBOLS.put("069500", "KODEX200");
    }

    private static final String BASELINE_SYMBOL = "069500"; // KODEX200 — buy&hold 기준선이자 국면 필터의 지수

    private static final List<Integer> N_CANDIDATES = List.of(60, 120, 200);
    private static final double TARGET_VOL = 0.20; // 연 20%

    private static final int TRAIN_SIZE = 252;
    private static final int TEST_SIZE = 63;

    /** N 후보 최댓값(200) + 판단주기(21) = 221봉 워밍업 — RealDataPortfolioGateTest와 동일 근거. */
    private static final int MOMENTUM_WARMUP = 200 + 21;

    /** 슬리브당 200만원 × 5종목 = 총 1,000만원. */
    private static final BigDecimal SLEEVE_CAPITAL = new BigDecimal("2000000");

    private static final double GATE_MIN_DSR = 0.95;
    private static final double GATE_MAX_MDD = 0.15;

    private static final int TRADING_DAYS_PER_YEAR = 252;

    private final CandleCsvLoader loader = new CandleCsvLoader();
    private final PerformanceCalculator performanceCalculator = new PerformanceCalculator();
    private final PortfolioBacktestRunner portfolioRunner = new PortfolioBacktestRunner();

    /** 변형 정의 — N(Integer) 파라미터를 받아 이번 변형의 전략 인스턴스를 만드는 팩토리. */
    private record VariantDef(String label, Function<Integer, BacktestStrategy> factory) {
    }

    private List<VariantDef> variantDefs(Map<LocalDate, Boolean> regimeByDate) {
        return List.of(
                new VariantDef("C0.기본모멘텀",
                        n -> new TimeSeriesMomentumStrategy(n)),
                new VariantDef("C1.+국면필터",
                        n -> new RegimeFilteredStrategy(new TimeSeriesMomentumStrategy(n), regimeByDate)),
                new VariantDef("C2.+변동성타게팅",
                        n -> new VolatilityTargetingStrategy(new TimeSeriesMomentumStrategy(n), TARGET_VOL)),
                new VariantDef("C3.+국면필터+변동성타게팅",
                        n -> new VolatilityTargetingStrategy(
                                new RegimeFilteredStrategy(new TimeSeriesMomentumStrategy(n), regimeByDate), TARGET_VOL))
        );
    }

    @Test
    void 시계열모멘텀_국면필터_변동성타게팅_네변형_MDD절감_비교() {
        Path dataDir = resolveDataDir();
        assumeTrue(dataDir != null,
                "data/ 디렉터리를 찾지 못해 국면필터/변동성타게팅 실험을 스킵함 — "
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

        BigDecimal totalCapital = SLEEVE_CAPITAL.multiply(BigDecimal.valueOf(candlesBySymbol.size()));

        // ── 국면 맵 — KODEX200 전체 이력에서 한 번만 계산해 모든 변형/슬리브가 공유 ──
        List<Candle> kodexCandles = candlesBySymbol.get(BASELINE_SYMBOL);
        Map<LocalDate, Boolean> regimeByDate = MarketRegime.compute(kodexCandles);

        List<VariantDef> variants = variantDefs(regimeByDate);

        // ── 1) 변형별 포트폴리오 실행 ──
        List<PortfolioBacktestRunner.PortfolioResult> results = new ArrayList<>();
        for (VariantDef variant : variants) {
            PortfolioBacktestRunner.SleeveRunner sleeveRunner = sleeveRunnerFor(variant);
            PortfolioBacktestRunner.PortfolioResult result =
                    portfolioRunner.run(candlesBySymbol, sleeveRunner, totalCapital, TRAIN_SIZE, TEST_SIZE);

            assertFalse(result.dailyReturns().isEmpty(), variant.label() + ": 포트폴리오 OOS 일별수익률이 비어 있음");
            assertFalse(Double.isNaN(result.totalReturn()), variant.label() + ": totalReturn이 NaN");
            assertFalse(Double.isNaN(result.cagr()), variant.label() + ": cagr이 NaN");
            assertFalse(Double.isNaN(result.mdd()), variant.label() + ": mdd가 NaN");
            assertFalse(Double.isNaN(result.sharpe()), variant.label() + ": sharpe가 NaN");
            assertTrue(result.sleeveResults().size() == candlesBySymbol.size(),
                    variant.label() + ": 슬리브 결과 수가 종목 수와 달라야 함");

            results.add(result);
        }

        // ── 2) 변형 간 비교용 (비연율화) 일별 샤프비율 배열 — N=4 ──
        double[] variantSharpesDaily = new double[variants.size()];
        for (int i = 0; i < results.size(); i++) {
            variantSharpesDaily[i] = results.get(i).sharpe() / Math.sqrt(TRADING_DAYS_PER_YEAR);
        }

        // ── 3) 변형별 교정 DSR + 게이트① 판정 ──
        double[] calibratedDsr = new double[variants.size()];
        boolean[] gatePass = new boolean[variants.size()];
        for (int i = 0; i < results.size(); i++) {
            PortfolioBacktestRunner.PortfolioResult result = results.get(i);
            double dsr = performanceCalculator.deflatedSharpeAcrossFamilies(result.dailyReturns(), variantSharpesDaily);
            assertFalse(Double.isNaN(dsr), variants.get(i).label() + ": 교정 DSR이 NaN");
            calibratedDsr[i] = dsr;
            gatePass[i] = result.totalReturn() > 0 && dsr > GATE_MIN_DSR && result.mdd() < GATE_MAX_MDD;
        }

        // ── 4) KODEX200 buy&hold 기준선(동일 OOS 기간) ──
        int baselineWindowCount = windowCount(kodexCandles.size(), TRAIN_SIZE, TEST_SIZE);
        double baselineBuyHold = buyHoldReturn(kodexCandles, TRAIN_SIZE, baselineWindowCount, TEST_SIZE);

        // ── 5) 국면 필터 OFF일수 비율 — KODEX200의 OOS 기간 동안 실제로 얼마나 개입했는지 확인 ──
        double offRatio = offDayRatioInOosPeriod(kodexCandles, regimeByDate, baselineWindowCount);

        printReport(variants, results, calibratedDsr, gatePass, baselineBuyHold, offRatio);
    }

    private PortfolioBacktestRunner.SleeveRunner sleeveRunnerFor(VariantDef variant) {
        WalkForwardRunner walkForwardRunner = new WalkForwardRunner(CostModel.defaults());
        return (candles, sleeveCapital) -> walkForwardRunner.run(
                candles, N_CANDIDATES, variant.factory(), TRAIN_SIZE, TEST_SIZE, MOMENTUM_WARMUP, sleeveCapital);
    }

    /** KODEX200의 OOS(walk-forward test) 구간 동안 국면이 OFF였던 날의 비율. */
    private double offDayRatioInOosPeriod(List<Candle> candles, Map<LocalDate, Boolean> regimeByDate, int windowCount) {
        int oosStart = TRAIN_SIZE;
        int oosEndExclusive = TRAIN_SIZE + windowCount * TEST_SIZE;
        if (oosEndExclusive <= oosStart || oosEndExclusive > candles.size()) {
            return 0.0;
        }
        int offCount = 0;
        int total = oosEndExclusive - oosStart;
        for (Candle c : candles.subList(oosStart, oosEndExclusive)) {
            if (!regimeByDate.getOrDefault(c.date(), true)) {
                offCount++;
            }
        }
        return total == 0 ? 0.0 : offCount / (double) total;
    }

    private void printReport(List<VariantDef> variants, List<PortfolioBacktestRunner.PortfolioResult> results,
                              double[] calibratedDsr, boolean[] gatePass, double baselineBuyHold, double offRatio) {
        System.out.println();
        System.out.println("=== C(시계열모멘텀) MDD 절감 실험 — 국면필터(SMA200)/변동성타게팅 4변형 비교 (교정 DSR, N=4) ===");
        System.out.println("(trainSize=" + TRAIN_SIZE + ", testSize=" + TEST_SIZE + ", 슬리브당 " + SLEEVE_CAPITAL
                + "원 × " + SYMBOLS.size() + "종목 = 총 " + SLEEVE_CAPITAL.multiply(BigDecimal.valueOf(SYMBOLS.size()))
                + "원, 비용모델=CostModel.defaults(), N후보=" + N_CANDIDATES + ", 목표변동성=" + (TARGET_VOL * 100) + "%)");

        System.out.println();
        System.out.println("--- [표 1] 변형별 포트폴리오 OOS 성과 및 게이트① 판정 ---");
        System.out.printf("%-24s | %10s | %10s | %8s | %8s | %8s | %6s%n",
                "변형", "OOS수익률", "CAGR", "MDD", "Sharpe", "교정DSR", "게이트①");
        System.out.println("-".repeat(100));
        for (int i = 0; i < variants.size(); i++) {
            PortfolioBacktestRunner.PortfolioResult r = results.get(i);
            System.out.printf("%-24s | %9.2f%% | %9.2f%% | %7.2f%% | %8.3f | %8.3f | %s%n",
                    variants.get(i).label(), r.totalReturn() * 100, r.cagr() * 100, r.mdd() * 100,
                    r.sharpe(), calibratedDsr[i], gatePass[i] ? "PASS" : "FAIL");
        }

        System.out.println();
        System.out.println("--- [표 2] 변형별 종목 슬리브 MDD 요약 ---");
        System.out.printf("%-24s | %-14s | %10s | %8s%n", "변형", "종목", "슬리브수익률", "슬리브MDD");
        System.out.println("-".repeat(80));
        for (int i = 0; i < variants.size(); i++) {
            PortfolioBacktestRunner.PortfolioResult r = results.get(i);
            for (PortfolioBacktestRunner.SleeveResult sleeve : r.sleeveResults()) {
                BacktestResult oos = sleeve.walkForwardResult().oosResult();
                String name = SYMBOLS.getOrDefault(sleeve.symbol(), sleeve.symbol());
                System.out.printf("%-24s | %-14s | %9.2f%% | %7.2f%%%n",
                        variants.get(i).label(), sleeve.symbol() + "(" + name + ")",
                        oos.totalReturn() * 100, oos.mdd() * 100);
            }
        }

        System.out.println();
        System.out.println("--- [기준선] KODEX200(069500) buy&hold, 동일 OOS 기간 ---");
        System.out.printf("  단순보유 총수익률: %.2f%%%n", baselineBuyHold * 100);

        System.out.println();
        System.out.println("--- [국면 필터 진단] KODEX200 OOS 기간 중 OFF(국면필터 개입)일수 비율 ---");
        System.out.printf("  OFF 비율: %.2f%%%n", offRatio * 100);

        System.out.println();
        System.out.println("--- [최종 판정] 게이트① = OOS수익>0 AND 교정DSR>0.95 AND MDD<15% ---");
        for (int i = 0; i < variants.size(); i++) {
            PortfolioBacktestRunner.PortfolioResult r = results.get(i);
            System.out.printf("  %-24s 게이트① = %s  (OOS수익>0: %s, 교정DSR>0.95: %s(%.3f), MDD<15%%: %s(%.2f%%))%n",
                    variants.get(i).label(), gatePass[i] ? "PASS" : "FAIL",
                    r.totalReturn() > 0 ? "O" : "X",
                    calibratedDsr[i] > GATE_MIN_DSR ? "O" : "X", calibratedDsr[i],
                    r.mdd() < GATE_MAX_MDD ? "O" : "X", r.mdd() * 100);
        }
    }

    /** RealDataPortfolioGateTest와 동일한 data/ 디렉터리 탐색 규칙. */
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

    /** RealDataPortfolioGateTest와 동일한 buy&hold 기준선 계산. */
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
