package com.autostock.backtest;

import com.autostock.common.event.Candle;
import com.autostock.strategy.VolTargetMath;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * F1(Stationary Bootstrap)·F2(Hansen SPA) 검정 인프라를 기존 10계열(A,B,C0~C4,D0~D2)에
 * 적용해 <b>병기(side-by-side reference) 수치</b>를 산출하는 실험 테스트(트랙 F, ADR-13).
 *
 * <h2>중요 — 게이트 기준 변경 아님</h2>
 * 이 테스트가 출력하는 부트스트랩 MDD 분포·SPA p-value는 <b>공식 게이트 판정에 쓰이지
 * 않는다</b>. ADR-12(게이트 v2 제안: SPA 병행 + 부트스트랩 MDD 판정)는 아직 사용자 승인
 * 대기 상태이고, ADR-13은 "측정 도구 추가"만 확정했다 — 새 수치는 참고용으로 병기할 뿐이며,
 * 공식 게이트는 여전히 기존 기준(walk-forward OOS 수익&gt;0, DSR(N)&gt;0.95, MDD 점추정&lt;15%)이다.
 * 그래서 이 테스트는 판정 assert를 전혀 하지 않는다 — 계산이 NaN 없이 정상 산출됐는지만
 * 확인하고, 실제 해석은 사람이 콘솔 표를 읽고 내린다({@link GateDsrRobustnessTest},
 * {@link RealDataDualMomentumExperimentTest}와 같은 관례).
 *
 * <h2>재사용 — 10계열 일별수익률 산출 로직</h2>
 * A,B,C0~C4는 {@link GateDsrRobustnessTest}/{@link RealDataDualMomentumExperimentTest}의
 * familyDefs/파이프라인을(동일 파라미터로) 복제해 재실행하고, D0~D2는
 * {@link RealDataDualMomentumExperimentTest}의 듀얼모멘텀 시뮬레이션을 복제해 재실행한다 —
 * 이 저장소의 실험 테스트들이 공통으로 따르는 "로직은 원본 그대로 복제, 새 튜닝 없음" 관례를
 * 그대로 따른다(전략·파라미터·비용모델 변경 없음).
 *
 * <h2>날짜 정렬 — 계열 간 평가 구간 차이(캐비엇)</h2>
 * A,B,C0~C4는 5종목(삼성전자·SK하이닉스·NAVER·카카오·KODEX200, C4는 인버스 추가) walk-forward
 * OOS를 이어붙인 시계열이고, D0~D2는 4개 ETF(KODEX200·미국S&amp;P500선물(H)·국내국고채10년·
 * 금선물(H))의 단일·전체이력 OOS 구간이다 — 두 유니버스의 데이터 범위 자체가 다르다. 이 테스트는
 * SPA 검정을 위해 <b>벤치마크(KODEX200 buy&amp;hold)와 모든 후보의 날짜 교집합</b>으로 정렬해서만
 * 비교하며, 그 교집합 구간(시작일~종료일·일수)을 출력에 항상 명시한다.
 *
 * <h2>부트스트랩 리샘플 횟수 — 실험 전용 B=1,000</h2>
 * {@link StationaryBootstrap}/{@link SpaTest} 클래스 기본값(B=2,000)은 그대로 유지하지만,
 * 이 실험 테스트는 10계열 × 여러 부트스트랩 호출이 누적되면 느려지므로 <b>이 테스트 안에서만</b>
 * B=1,000으로 낮춰 호출한다(L=21, seed=42는 기본값 그대로). 클래스 기본값 자체는 건드리지 않는다.
 *
 * <p>데이터가 없으면 조용히 스킵한다(다른 Real* 테스트와 동일 이유 — data/는 git에 커밋하지 않음).
 */
class GateBootstrapSpaExperimentTest {

    // ───────────────────────────── 실험 전용 부트스트랩/SPA 설정 ─────────────────────────────

    /** 클래스 기본값(2,000)에서 낮춘 실험 전용 리샘플 횟수 — 클래스 기본값 자체는 미변경. */
    private static final int EXPERIMENT_RESAMPLES = 1000;
    private static final int EXPERIMENT_MEAN_BLOCK_LENGTH = StationaryBootstrap.DEFAULT_MEAN_BLOCK_LENGTH; // 21
    private static final long EXPERIMENT_SEED = StationaryBootstrap.DEFAULT_SEED; // 42

    // ───────────────────────────── A,B,C0~C4 — 5종목+인버스 유니버스 ─────────────────────────────

    private static final Map<String, String> FAMILY_SYMBOLS = new LinkedHashMap<>();
    static {
        FAMILY_SYMBOLS.put("005930", "삼성전자");
        FAMILY_SYMBOLS.put("000660", "SK하이닉스");
        FAMILY_SYMBOLS.put("035420", "NAVER");
        FAMILY_SYMBOLS.put("035720", "카카오");
        FAMILY_SYMBOLS.put("069500", "KODEX200");
    }
    private static final String FAMILY_BASELINE_SYMBOL = "069500";
    private static final String INVERSE_SYMBOL = "114800";

    private static final List<Double> K_CANDIDATES = List.of(0.3, 0.4, 0.5, 0.6, 0.7);
    private static final List<Integer> N_CANDIDATES = List.of(60, 120, 200);
    private static final Double STOP_LOSS_PCT = -0.03;
    private static final double C_TARGET_VOL = 0.20;
    private static final int FAMILY_TRAIN_SIZE = 252;
    private static final int FAMILY_TEST_SIZE = 63;
    private static final int MOMENTUM_WARMUP = 200 + 21;
    private static final BigDecimal SLEEVE_CAPITAL = new BigDecimal("2000000");

    // ───────────────────────────── D0~D2 — 4개 ETF 유니버스(듀얼 모멘텀) ─────────────────────────────

    private static final Map<String, String> D_SYMBOLS = new LinkedHashMap<>();
    static {
        D_SYMBOLS.put("069500", "KODEX200");
        D_SYMBOLS.put("143850", "미국S&P500선물(H)");
        D_SYMBOLS.put("148070", "국내 국고채10년");
        D_SYMBOLS.put("132030", "금선물(H)");
    }
    private static final String CASH = "CASH";
    private static final int MOMENTUM_LOOKBACK = 252;
    private static final int SMA_LOOKBACK = 210;
    private static final int REBALANCE_INTERVAL = 21;
    private static final double D2_TARGET_VOL = 0.10;
    private static final int D_WARMUP = MOMENTUM_LOOKBACK + 1;
    private static final CostModel ETF_COST_MODEL = new CostModel(0.00015, 0.00015, 0.0, 0.0005);

    private static final int TRADING_DAYS_PER_YEAR = 252;

    private final CandleCsvLoader loader = new CandleCsvLoader();
    private final PerformanceCalculator performanceCalculator = new PerformanceCalculator();
    private final PortfolioBacktestRunner portfolioRunner = new PortfolioBacktestRunner();

    @Test
    void 부트스트랩MDD_SPA검정_10계열_병기수치() {
        Path dataDir = resolveDataDir();
        assumeTrue(dataDir != null,
                "data/ 디렉터리를 찾지 못해 F1/F2 병기 실험을 스킵함 — "
                        + "먼저 `python3 scripts/fetch_yahoo_daily.py` 로 data/ 를 채운 뒤 다시 실행할 것");

        Set<String> requiredCodes = new LinkedHashSet<>();
        requiredCodes.addAll(FAMILY_SYMBOLS.keySet());
        requiredCodes.add(INVERSE_SYMBOL);
        requiredCodes.addAll(D_SYMBOLS.keySet());
        for (String code : requiredCodes) {
            assumeTrue(Files.isRegularFile(dataDir.resolve(code + ".csv")),
                    "종목 " + code + " CSV 없음 — 10계열 재현에 필요한 전 종목이 있어야 함: " + dataDir.resolve(code + ".csv"));
        }

        // ── 1) A,B,C0~C4 (family) 재현 ──
        Map<String, List<Candle>> familyCandles = new LinkedHashMap<>();
        for (String code : FAMILY_SYMBOLS.keySet()) {
            familyCandles.put(code, loader.load(dataDir.resolve(code + ".csv")));
        }
        List<Candle> inverseCandles = loader.load(dataDir.resolve(INVERSE_SYMBOL + ".csv"));
        Map<String, PortfolioBacktestRunner.PortfolioResult> familyResults = runFamilySeries(familyCandles, inverseCandles);

        // ── 2) D0~D2 재현 ──
        Map<String, List<Candle>> dCandles = new LinkedHashMap<>();
        for (String code : D_SYMBOLS.keySet()) {
            dCandles.put(code, loader.load(dataDir.resolve(code + ".csv")));
        }
        List<LocalDate> dCommonDates = commonDates(dCandles);
        assumeTrue(dCommonDates.size() >= D_WARMUP + REBALANCE_INTERVAL,
                "D계열 공통 거래일 수가 부족해 F1/F2 병기 실험을 스킵함(실제 " + dCommonDates.size() + "일)");
        Map<String, List<BigDecimal>> dClosesByAsset = alignCloses(dCandles, dCommonDates);
        int dN = dCommonDates.size();
        Map<String, SimResult> dResults = new LinkedHashMap<>();
        dResults.put("D0.GEM듀얼모멘텀", simulate(Variant.D0, dClosesByAsset, dCommonDates, dN, ETF_COST_MODEL));
        dResults.put("D1.+SMA210추세필터", simulate(Variant.D1, dClosesByAsset, dCommonDates, dN, ETF_COST_MODEL));
        dResults.put("D2.+변동성타게팅10%", simulate(Variant.D2, dClosesByAsset, dCommonDates, dN, ETF_COST_MODEL));

        // ── 3) 계산 무결성 확인(판정 assert 아님) ──
        for (Map.Entry<String, PortfolioBacktestRunner.PortfolioResult> e : familyResults.entrySet()) {
            PortfolioBacktestRunner.PortfolioResult r = e.getValue();
            assertFalse(r.dailyReturns().isEmpty(), e.getKey() + ": 일별수익률이 비어 있음");
            assertFalse(Double.isNaN(r.mdd()), e.getKey() + ": mdd가 NaN");
            assertFalse(Double.isNaN(r.totalReturn()), e.getKey() + ": totalReturn이 NaN");
            assertEquals(r.dailyReturns().size(), r.dates().size(), e.getKey() + ": 수익률-날짜 길이 불일치");
        }
        for (Map.Entry<String, SimResult> e : dResults.entrySet()) {
            SimResult r = e.getValue();
            assertFalse(r.dailyReturns().isEmpty(), e.getKey() + ": 일별수익률이 비어 있음");
            assertFalse(Double.isNaN(r.mdd()), e.getKey() + ": mdd가 NaN");
            assertEquals(r.dailyReturns().size(), r.dates().size(), e.getKey() + ": 수익률-날짜 길이 불일치");
        }

        // ── 4) 벤치마크(KODEX200 buy&hold) 일별수익률 — 날짜 인덱스 맵 ──
        List<Candle> kodexFullHistory = familyCandles.get(FAMILY_BASELINE_SYMBOL);
        Map<LocalDate, Double> benchmarkMap = buildBenchmarkDailyReturnMap(kodexFullHistory);

        // ── 5) 계열별 날짜→수익률 맵 ──
        Map<String, Map<LocalDate, Double>> allSeriesMaps = new LinkedHashMap<>();
        for (Map.Entry<String, PortfolioBacktestRunner.PortfolioResult> e : familyResults.entrySet()) {
            allSeriesMaps.put(e.getKey(), toDateReturnMap(e.getValue().dates(), e.getValue().dailyReturns()));
        }
        for (Map.Entry<String, SimResult> e : dResults.entrySet()) {
            allSeriesMaps.put(e.getKey(), toDateReturnMap(e.getValue().dates(), e.getValue().dailyReturns()));
        }

        // ── 6) 선택풀 정의 — 수익 양수 8계열 vs 전체 10계열 ──
        List<String> allLabels = new ArrayList<>(allSeriesMaps.keySet());
        assertTrue(allLabels.size() == 10, "10계열이어야 함(A,B,C0~C4,D0~D2), 실제 " + allLabels.size());

        Map<String, Double> totalReturnByLabel = new LinkedHashMap<>();
        for (Map.Entry<String, PortfolioBacktestRunner.PortfolioResult> e : familyResults.entrySet()) {
            totalReturnByLabel.put(e.getKey(), e.getValue().totalReturn());
        }
        for (Map.Entry<String, SimResult> e : dResults.entrySet()) {
            totalReturnByLabel.put(e.getKey(), e.getValue().totalReturn());
        }
        List<String> positivePool = new ArrayList<>();
        for (String label : allLabels) {
            if (totalReturnByLabel.get(label) > 0) {
                positivePool.add(label);
            }
        }

        // ── 7) [표 1] C0·C3·D1·D2 실측 MDD vs 부트스트랩 MDD 분포 ──
        Map<String, MddRow> table1 = new LinkedHashMap<>();
        for (String label : List.of("C0.기본모멘텀", "C3.+국면필터+변동성타게팅", "D1.+SMA210추세필터", "D2.+변동성타게팅10%")) {
            List<Double> dailyReturns = extractDailyReturns(familyResults, dResults, label);
            double pointMdd = extractPointMdd(familyResults, dResults, label);
            double[] arr = toArray(dailyReturns);
            StationaryBootstrap bootstrap = new StationaryBootstrap(arr, EXPERIMENT_MEAN_BLOCK_LENGTH, EXPERIMENT_RESAMPLES, EXPERIMENT_SEED);
            StationaryBootstrap.MddSummary summary = bootstrap.mddDistribution();
            assertFalse(Double.isNaN(summary.median()), label + ": 부트스트랩 MDD median이 NaN");
            assertFalse(Double.isNaN(summary.p95()), label + ": 부트스트랩 MDD p95가 NaN");
            table1.put(label, new MddRow(pointMdd, summary));
        }

        // ── 8) [표 2] SPA — 벤치마크 대비, 선택풀(a) N=8 / 전체풀(b) N=10 ──
        SpaOutcome spaN8 = runSpa(benchmarkMap, allSeriesMaps, positivePool);
        SpaOutcome spaN10 = runSpa(benchmarkMap, allSeriesMaps, allLabels);

        assertFalse(Double.isNaN(spaN8.result().pValueSpaConsistent()), "SPA(N=8) p-value가 NaN");
        assertFalse(Double.isNaN(spaN10.result().pValueSpaConsistent()), "SPA(N=10) p-value가 NaN");
        for (SpaTest.CandidateStat cs : spaN8.result().candidateStats()) {
            assertFalse(Double.isNaN(cs.tStat()), "SPA(N=8) " + cs.label() + " t-stat이 NaN");
        }
        for (SpaTest.CandidateStat cs : spaN10.result().candidateStats()) {
            assertFalse(Double.isNaN(cs.tStat()), "SPA(N=10) " + cs.label() + " t-stat이 NaN");
        }

        printReport(table1, spaN8, spaN10, positivePool, allLabels, totalReturnByLabel);
    }

    // ───────────────────────────── 표 1 보조 ─────────────────────────────

    private record MddRow(double pointMdd, StationaryBootstrap.MddSummary bootstrap) {
    }

    private List<Double> extractDailyReturns(Map<String, PortfolioBacktestRunner.PortfolioResult> familyResults,
                                              Map<String, SimResult> dResults, String label) {
        if (familyResults.containsKey(label)) {
            return familyResults.get(label).dailyReturns();
        }
        return dResults.get(label).dailyReturns();
    }

    private double extractPointMdd(Map<String, PortfolioBacktestRunner.PortfolioResult> familyResults,
                                    Map<String, SimResult> dResults, String label) {
        if (familyResults.containsKey(label)) {
            return familyResults.get(label).mdd();
        }
        return dResults.get(label).mdd();
    }

    private double[] toArray(List<Double> list) {
        double[] arr = new double[list.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    // ───────────────────────────── 표 2 보조 — SPA ─────────────────────────────

    private record SpaOutcome(List<LocalDate> commonDates, SpaTest.SpaResult result) {
    }

    private SpaOutcome runSpa(Map<LocalDate, Double> benchmarkMap, Map<String, Map<LocalDate, Double>> allSeriesMaps,
                               List<String> pool) {
        List<Map<LocalDate, Double>> maps = new ArrayList<>();
        maps.add(benchmarkMap);
        for (String label : pool) {
            maps.add(allSeriesMaps.get(label));
        }
        List<LocalDate> commonDates = intersectDates(maps);

        double[] benchArr = new double[commonDates.size()];
        for (int i = 0; i < commonDates.size(); i++) {
            benchArr[i] = benchmarkMap.get(commonDates.get(i));
        }
        Map<String, double[]> candidates = new LinkedHashMap<>();
        for (String label : pool) {
            Map<LocalDate, Double> m = allSeriesMaps.get(label);
            double[] arr = new double[commonDates.size()];
            for (int i = 0; i < commonDates.size(); i++) {
                arr[i] = m.get(commonDates.get(i));
            }
            candidates.put(label, arr);
        }

        SpaTest spa = new SpaTest(benchArr, candidates, EXPERIMENT_MEAN_BLOCK_LENGTH, EXPERIMENT_RESAMPLES, EXPERIMENT_SEED);
        return new SpaOutcome(commonDates, spa.run());
    }

    private Map<LocalDate, Double> toDateReturnMap(List<LocalDate> dates, List<Double> returns) {
        Map<LocalDate, Double> map = new LinkedHashMap<>();
        for (int i = 0; i < dates.size(); i++) {
            map.put(dates.get(i), returns.get(i));
        }
        return map;
    }

    /** 069500 전체 이력의 일별(전일대비) buy&hold 수익률 — 날짜(둘째 날부터)→수익률 맵. */
    private Map<LocalDate, Double> buildBenchmarkDailyReturnMap(List<Candle> kodexCandles) {
        Map<LocalDate, Double> map = new LinkedHashMap<>();
        for (int i = 1; i < kodexCandles.size(); i++) {
            BigDecimal prev = kodexCandles.get(i - 1).close();
            BigDecimal curr = kodexCandles.get(i).close();
            double r = prev.signum() == 0 ? 0.0 : curr.subtract(prev).divide(prev, 12, RoundingMode.HALF_UP).doubleValue();
            map.put(kodexCandles.get(i).date(), r);
        }
        return map;
    }

    /** 여러 (날짜→수익률) 맵의 날짜 교집합(오름차순 정렬). */
    private List<LocalDate> intersectDates(List<Map<LocalDate, Double>> maps) {
        TreeSet<LocalDate> common = null;
        for (Map<LocalDate, Double> m : maps) {
            if (common == null) {
                common = new TreeSet<>(m.keySet());
            } else {
                common.retainAll(m.keySet());
            }
        }
        return common == null ? List.of() : new ArrayList<>(common);
    }

    // ───────────────────────────── A,B,C0~C4 재현(GateDsrRobustnessTest/RealDataDualMomentumExperimentTest와 동일 파이프라인) ─────────────────────────────

    private Map<String, PortfolioBacktestRunner.PortfolioResult> runFamilySeries(
            Map<String, List<Candle>> candlesBySymbol, List<Candle> inverseCandles) {
        BigDecimal totalCapital = SLEEVE_CAPITAL.multiply(BigDecimal.valueOf(candlesBySymbol.size()));
        List<Candle> kodexCandles = candlesBySymbol.get(FAMILY_BASELINE_SYMBOL);
        Map<LocalDate, Boolean> regimeByDate = MarketRegime.compute(kodexCandles);

        Map<String, PortfolioBacktestRunner.PortfolioResult> results = new LinkedHashMap<>();

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
                p -> new VolatilityTargetingStrategy(new TimeSeriesMomentumStrategy((Integer) p), C_TARGET_VOL), MOMENTUM_WARMUP));

        for (Def def : defs) {
            WalkForwardRunner walkForwardRunner = new WalkForwardRunner(CostModel.defaults());
            @SuppressWarnings("unchecked")
            List<Object> candidates = (List<Object>) def.candidates();
            PortfolioBacktestRunner.SleeveRunner sleeveRunner = (candles, sleeveCapital) -> walkForwardRunner.run(
                    candles, candidates, def.factory(), FAMILY_TRAIN_SIZE, FAMILY_TEST_SIZE, def.warmup(), sleeveCapital);
            PortfolioBacktestRunner.PortfolioResult result =
                    portfolioRunner.run(candlesBySymbol, sleeveRunner, totalCapital, FAMILY_TRAIN_SIZE, FAMILY_TEST_SIZE);
            results.put(def.label(), result);
        }

        // C3
        WalkForwardRunner c3WalkForwardRunner = new WalkForwardRunner(CostModel.defaults());
        Function<Integer, BacktestStrategy> c3Factory = n -> new VolatilityTargetingStrategy(
                new RegimeFilteredStrategy(new TimeSeriesMomentumStrategy(n), regimeByDate), C_TARGET_VOL);
        PortfolioBacktestRunner.SleeveRunner c3SleeveRunner = (candles, sleeveCapital) -> c3WalkForwardRunner.run(
                candles, N_CANDIDATES, c3Factory, FAMILY_TRAIN_SIZE, FAMILY_TEST_SIZE, MOMENTUM_WARMUP, sleeveCapital);
        PortfolioBacktestRunner.PortfolioResult c3Result =
                portfolioRunner.run(candlesBySymbol, c3SleeveRunner, totalCapital, FAMILY_TRAIN_SIZE, FAMILY_TEST_SIZE);
        results.put("C3.+국면필터+변동성타게팅", c3Result);

        // C4 — 069500 슬리브만 인버스 전환
        InverseSwitchWalkForwardRunner inverseRunner = new InverseSwitchWalkForwardRunner(CostModel.defaults());
        WalkForwardRunner c4StdRunner = new WalkForwardRunner(CostModel.defaults());
        PortfolioBacktestRunner.SleeveRunner c4SleeveRunner = (candles, sleeveCapital) -> {
            String symbol = candles.isEmpty() ? "" : candles.get(0).symbol();
            if (FAMILY_BASELINE_SYMBOL.equals(symbol)) {
                return inverseRunner.run(candles, inverseCandles, N_CANDIDATES, regimeByDate, C_TARGET_VOL,
                        FAMILY_TRAIN_SIZE, FAMILY_TEST_SIZE, MOMENTUM_WARMUP, sleeveCapital).walkForwardResult();
            }
            return c4StdRunner.run(candles, N_CANDIDATES, c3Factory, FAMILY_TRAIN_SIZE, FAMILY_TEST_SIZE, MOMENTUM_WARMUP, sleeveCapital);
        };
        PortfolioBacktestRunner.PortfolioResult c4Result =
                portfolioRunner.run(candlesBySymbol, c4SleeveRunner, totalCapital, FAMILY_TRAIN_SIZE, FAMILY_TEST_SIZE);
        results.put("C4.+인버스국면롱숏(ADR-8)", c4Result);

        return results;
    }

    // ───────────────────────────── D0~D2 재현(RealDataDualMomentumExperimentTest와 동일 시뮬레이션 코어) ─────────────────────────────

    private enum Variant { D0, D1, D2 }

    /** RealDataDualMomentumExperimentTest.SimResult에 dates만 추가한 지역 레코드. */
    private record SimResult(List<Double> dailyReturns, List<LocalDate> dates, double totalReturn, double cagr,
                              double mdd, double sharpeAnnualized, int tradeCount) {
    }

    private SimResult simulate(Variant variant, Map<String, List<BigDecimal>> closesByAsset,
                                List<LocalDate> commonDates, int n, CostModel cost) {
        List<String> assets = new ArrayList<>(closesByAsset.keySet());

        List<Double> dailyReturns = new ArrayList<>();
        List<LocalDate> dates = new ArrayList<>();
        Map<String, Double> currentWeights = new LinkedHashMap<>();
        currentWeights.put(CASH, 1.0);

        int tradeCount = 0;
        double equity = 1.0;

        for (int i = D_WARMUP; i < n; i++) {
            boolean isRebalanceDay = (i - D_WARMUP) % REBALANCE_INTERVAL == 0;
            if (isRebalanceDay) {
                Map<String, Double> targetWeights = decideWeights(variant, assets, closesByAsset, i);
                double turnoverCost = turnoverCost(currentWeights, targetWeights, cost);
                if (turnoverCost > 1e-12) {
                    tradeCount++;
                }
                currentWeights = targetWeights;
                double marketReturn = dayReturn(currentWeights, closesByAsset, i);
                double combined = (1 - turnoverCost) * (1 + marketReturn) - 1;
                dailyReturns.add(combined);
                equity *= (1 + combined);
            } else {
                double marketReturn = dayReturn(currentWeights, closesByAsset, i);
                dailyReturns.add(marketReturn);
                equity *= (1 + marketReturn);
            }
            dates.add(commonDates.get(i));
        }

        BigDecimal initialCapital = BigDecimal.ONE;
        BigDecimal finalEquity = BigDecimal.valueOf(equity);
        BacktestResult r = performanceCalculator.calculate(dailyReturns, initialCapital, finalEquity, tradeCount, 1, 0.0);

        return new SimResult(dailyReturns, dates, r.totalReturn(), r.cagr(), r.mdd(), r.sharpe(), tradeCount);
    }

    private Map<String, Double> decideWeights(Variant variant, List<String> assets,
                                               Map<String, List<BigDecimal>> closesByAsset, int i) {
        int asOf = i - 1;

        String best = null;
        double bestRet = Double.NEGATIVE_INFINITY;
        for (String a : assets) {
            double ret = momentumReturn(closesByAsset.get(a), asOf);
            if (ret > bestRet) {
                bestRet = ret;
                best = a;
            }
        }

        Map<String, Double> weights = new LinkedHashMap<>();
        if (bestRet <= 0.0) {
            weights.put(CASH, 1.0);
            return weights;
        }

        if (variant == Variant.D0) {
            weights.put(best, 1.0);
            return weights;
        }

        double price = closesByAsset.get(best).get(asOf).doubleValue();
        double sma210 = sma(closesByAsset.get(best), asOf, SMA_LOOKBACK);
        if (price <= sma210) {
            weights.put(CASH, 1.0);
            return weights;
        }

        if (variant == Variant.D1) {
            weights.put(best, 1.0);
            return weights;
        }

        List<BigDecimal> closesUpToAsOf = closesByAsset.get(best).subList(0, asOf + 1);
        double fraction = VolTargetMath.fraction(closesUpToAsOf, D2_TARGET_VOL);
        weights.put(best, fraction);
        if (fraction < 1.0) {
            weights.put(CASH, 1.0 - fraction);
        }
        return weights;
    }

    private double momentumReturn(List<BigDecimal> closes, int asOf) {
        BigDecimal now = closes.get(asOf);
        BigDecimal past = closes.get(asOf - MOMENTUM_LOOKBACK);
        if (past.signum() == 0) {
            return 0.0;
        }
        return now.subtract(past).divide(past, 12, RoundingMode.HALF_UP).doubleValue();
    }

    private double sma(List<BigDecimal> closes, int asOf, int lookback) {
        double sum = 0.0;
        for (int i = asOf - lookback + 1; i <= asOf; i++) {
            sum += closes.get(i).doubleValue();
        }
        return sum / lookback;
    }

    private double dayReturn(Map<String, Double> weights, Map<String, List<BigDecimal>> closesByAsset, int i) {
        double total = 0.0;
        for (Map.Entry<String, Double> e : weights.entrySet()) {
            String asset = e.getKey();
            if (CASH.equals(asset)) {
                continue;
            }
            List<BigDecimal> closes = closesByAsset.get(asset);
            BigDecimal prev = closes.get(i - 1);
            BigDecimal curr = closes.get(i);
            double r = prev.signum() == 0 ? 0.0 : curr.subtract(prev).divide(prev, 12, RoundingMode.HALF_UP).doubleValue();
            total += e.getValue() * r;
        }
        return total;
    }

    private double turnoverCost(Map<String, Double> oldWeights, Map<String, Double> newWeights, CostModel cost) {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(oldWeights.keySet());
        keys.addAll(newWeights.keySet());
        keys.remove(CASH);

        double buyCostFrac = cost.buyFeePct() + cost.slippagePct();
        double sellCostFrac = cost.sellFeePct() + cost.sellTaxPct() + cost.slippagePct();

        double total = 0.0;
        for (String k : keys) {
            double oldW = oldWeights.getOrDefault(k, 0.0);
            double newW = newWeights.getOrDefault(k, 0.0);
            double delta = newW - oldW;
            if (delta > 0) {
                total += delta * buyCostFrac;
            } else if (delta < 0) {
                total += (-delta) * sellCostFrac;
            }
        }
        return total;
    }

    private List<LocalDate> commonDates(Map<String, List<Candle>> candlesBySymbol) {
        TreeSet<LocalDate> common = null;
        for (List<Candle> candles : candlesBySymbol.values()) {
            Set<LocalDate> dates = new LinkedHashSet<>();
            for (Candle c : candles) {
                dates.add(c.date());
            }
            if (common == null) {
                common = new TreeSet<>(dates);
            } else {
                common.retainAll(dates);
            }
        }
        return common == null ? List.of() : new ArrayList<>(common);
    }

    private Map<String, List<BigDecimal>> alignCloses(Map<String, List<Candle>> candlesBySymbol, List<LocalDate> commonDates) {
        Map<String, List<BigDecimal>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<Candle>> entry : candlesBySymbol.entrySet()) {
            Map<LocalDate, BigDecimal> closeByDate = new LinkedHashMap<>();
            for (Candle c : entry.getValue()) {
                closeByDate.put(c.date(), c.close());
            }
            List<BigDecimal> aligned = new ArrayList<>(commonDates.size());
            for (LocalDate d : commonDates) {
                BigDecimal close = closeByDate.get(d);
                if (close == null) {
                    throw new IllegalStateException("공통 거래일 " + d + "에 " + entry.getKey() + " 종가가 없음");
                }
                aligned.add(close);
            }
            result.put(entry.getKey(), aligned);
        }
        return result;
    }

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

    // ───────────────────────────── 리포트 출력 ─────────────────────────────

    private void printReport(Map<String, MddRow> table1, SpaOutcome spaN8, SpaOutcome spaN10,
                              List<String> positivePool, List<String> allLabels,
                              Map<String, Double> totalReturnByLabel) {
        System.out.println();
        System.out.println("=== F1/F2 검증 인프라 병기 수치 — Stationary Bootstrap(MDD) · Hansen SPA_c 검정 (ADR-13) ===");
        System.out.println("[캐비엇] 병기 수치 — 공식 게이트는 현행(ADR-12 승인 전). 판정 assert 없음(계산 무결성만 확인).");
        System.out.println("[실험 설정] 부트스트랩/SPA: meanBlockLength(L)=" + EXPERIMENT_MEAN_BLOCK_LENGTH
                + ", resamples(B)=" + EXPERIMENT_RESAMPLES + "(클래스 기본값 2,000에서 실험 전용으로 낮춤), seed="
                + EXPERIMENT_SEED + " — StationaryBootstrap/SpaTest 클래스 기본값 자체는 미변경.");

        System.out.println();
        System.out.println("--- [표 1] C0·C3·D1·D2 — 실측 MDD(점추정) vs 부트스트랩 MDD 분포 ---");
        System.out.printf("%-28s | %10s | %10s | %10s | %12s%n",
                "계열", "실측MDD", "부트median", "부트p95", "P(MDD>15%)");
        System.out.println("-".repeat(85));
        for (Map.Entry<String, MddRow> e : table1.entrySet()) {
            MddRow row = e.getValue();
            System.out.printf("%-28s | %9.2f%% | %9.2f%% | %9.2f%% | %11.1f%%%n",
                    e.getKey(), row.pointMdd() * 100, row.bootstrap().median() * 100,
                    row.bootstrap().p95() * 100, row.bootstrap().probabilityExceedsBudget() * 100);
        }

        System.out.println();
        System.out.println("--- [표 2] SPA_c 검정 — 벤치마크=KODEX200 buy&hold(동일 날짜 교집합 구간) ---");
        System.out.println("[방법론 캐비엇] A,B,C0~C4(5종목 walk-forward OOS)와 D0~D2(4개 ETF 단일 OOS)는 서로 다른");
        System.out.println("유니버스/평가창이다 — 벤치마크와 모든 후보의 날짜 교집합으로만 정렬해 비교했다(아래 구간 참고).");

        printSpaTable("(a) 선택풀 N=" + positivePool.size() + " (수익 양수 계열: " + positivePool + ")", spaN8);
        printSpaTable("(b) 전체풀 N=" + allLabels.size() + " (10계열 전부)", spaN10);

        System.out.println();
        System.out.println("--- [참고] 계열별 OOS 총수익률(자체 평가구간 기준, 표 2의 교집합 구간과 다름) ---");
        for (String label : allLabels) {
            System.out.printf("  %-28s : %9.2f%%%n", label, totalReturnByLabel.get(label) * 100);
        }

        System.out.println();
        System.out.println("[캐비엇 재확인] 위 수치는 병기 수치이며, 공식 게이트①은 현행(walk-forward OOS 수익>0, "
                + "DSR(N)>0.95, MDD 점추정<15%) 그대로 유지된다 — ADR-12가 사용자 승인을 받기 전까지 이 표들은 "
                + "판정에 쓰이지 않는다.");
    }

    private void printSpaTable(String poolLabel, SpaOutcome outcome) {
        System.out.println();
        System.out.println("* " + poolLabel);
        if (outcome.commonDates().isEmpty()) {
            System.out.println("  [스킵] 날짜 교집합이 비어 있어 SPA를 계산하지 못함");
            return;
        }
        System.out.printf("  날짜 교집합 구간: %s ~ %s (%d거래일)%n",
                outcome.commonDates().get(0), outcome.commonDates().get(outcome.commonDates().size() - 1),
                outcome.commonDates().size());
        System.out.printf("  SPA_c p-value(최고 후보가 벤치마크를 이긴다는 증거) = %.4f  (검정통계량 T_n=%.4f)%n",
                outcome.result().pValueSpaConsistent(), outcome.result().testStatistic());
        System.out.printf("  %-28s | %14s | %10s%n", "후보", "평균초과수익(일)", "t-stat");
        System.out.println("  " + "-".repeat(60));
        for (SpaTest.CandidateStat cs : outcome.result().candidateStats()) {
            System.out.printf("  %-28s | %13.5f%% | %10.3f%n", cs.label(), cs.meanExcessReturn() * 100, cs.tStat());
        }
    }
}
