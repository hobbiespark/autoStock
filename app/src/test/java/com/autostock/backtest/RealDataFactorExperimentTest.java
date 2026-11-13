package com.autostock.backtest;

import com.autostock.common.event.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 트랙 F — 재무 팩터 3종(X3 F-Score×모멘텀, F1 마법공식, F2 GP/A), 게이트 ① v3(ADR-15, autoStock D-30).
 * 사전 선언 trial +3(누적 27). 규칙은 docs/research/adr15_preregistration_20261114.md 4장에서 실행 전에 고정했다.
 *
 * <p>엔진·유니버스·비용·기간·가드는 X1b·X2b({@link RealDataCrossSectionalExperimentTest})와 같다. 재무는
 * {@code data/fscore/fs2_{2015..2025}.csv}(scripts/fetch_fscore_financials.py v2)만 읽는다. 전 연도가 없으면 스킵한다.
 * OOS는 X1b와 달리 2016-04-01부터다(2014 회계연도를 DART가 주지 않음).
 */
class RealDataFactorExperimentTest {

    private static final String CALENDAR_SYMBOL = "005930";
    private static final String BENCHMARK_SYMBOL = "069500";
    /** 2016-04-01 — OpenDART 전체 재무제표는 2015 사업연도부터라 1~3월 형성(2014 회계연도)이 불가(사전 선언 4장 실행 전 수정). */
    private static final LocalDate OOS_START = LocalDate.of(2016, 4, 1);
    private static final LocalDate OOS_END = LocalDate.of(2026, 8, 31);
    private static final int UNIVERSE_SIZE = 200;
    private static final int LIQUIDITY_WINDOW = 60;
    private static final BigDecimal CAPITAL = new BigDecimal("100000000");
    private static final int MIN_ROWS = 300;
    private static final double MID_MDD_BUDGET = 0.25;
    /** 누적 trial: 기존 21 + 트랙 Y 3 + 트랙 F 3. */
    private static final int TRIALS = 27;
    private static final int FIRST_FY = 2015;
    private static final int LAST_FY = 2025;
    private static final int RESAMPLES = 1000;
    private static final int BLOCK_LENGTH = StationaryBootstrap.DEFAULT_MEAN_BLOCK_LENGTH;
    private static final long SEED = StationaryBootstrap.DEFAULT_SEED;

    @Test
    void 트랙F_재무팩터_3종_게이트v3_판정수치() {
        Path dataDir = RealDataCrossSectionalExperimentTest.resolveDataDir();
        assumeTrue(dataDir != null, "data/ 디렉터리 없음 — 스킵");
        Path universeDir = dataDir.resolve("universe").resolve("prices");
        Path capDir = dataDir.resolve("universe").resolve("marketcap");
        Path fsDir = dataDir.resolve("fscore");
        assumeTrue(Files.isDirectory(universeDir) && Files.isDirectory(capDir) && Files.isDirectory(fsDir), "가격·시총·재무 폴더 없음");
        assumeTrue(Files.isRegularFile(dataDir.resolve(BENCHMARK_SYMBOL + ".csv")), "벤치마크 069500.csv 없음");

        FundamentalsTable fundamentals = FundamentalsTable.fromCsvDirectory(fsDir);
        Map<Integer, Integer> coverage = fundamentals.rowCountByYear();
        for (int fy = FIRST_FY; fy <= LAST_FY; fy++) {
            assumeTrue(coverage.getOrDefault(fy, 0) >= 300,
                    "fs2_" + fy + ".csv 부족(" + coverage.getOrDefault(fy, 0) + "행) — 재무 수집 완료 후 실행");
        }

        NavigableMap<LocalDate, Map<String, Double>> caps = RealDataCrossSectionalExperimentTest.marketCapSnapshots(capDir);
        CandleCsvLoader loader = new CandleCsvLoader();
        List<LocalDate> calendar = loader.load(universeDir.resolve(CALENDAR_SYMBOL + ".csv")).stream().map(Candle::date).toList();
        List<Candle> benchmarkCandles = loader.load(dataDir.resolve(BENCHMARK_SYMBOL + ".csv"));
        Set<String> excluded = RealDataCrossSectionalExperimentTest.nonOperatingCompanies(dataDir.resolve("corp_map_all.csv"));
        CrossSectionalBacktestRunner.PriceTable table =
                CrossSectionalBacktestRunner.PriceTable.fromCsvDirectory(universeDir, calendar, MIN_ROWS, excluded);
        CrossSectionalBacktestRunner.Config config = new CrossSectionalBacktestRunner.Config(
                OOS_START, OOS_END, UNIVERSE_SIZE, LIQUIDITY_WINDOW, CAPITAL, CostModel.defaults(), TRIALS, caps, 0.40);

        List<CrossSectionalStrategy> strategies = List.of(
                FactorStrategies.fScoreMomentum(fundamentals, 7, 20),
                FactorStrategies.magicFormula(fundamentals, 20),
                FactorStrategies.grossProfitability(fundamentals, 20));
        CrossSectionalBacktestRunner runner = new CrossSectionalBacktestRunner();
        Map<String, CrossSectionalBacktestRunner.Result> results = new LinkedHashMap<>();
        for (CrossSectionalStrategy s : strategies) {
            results.put(s.label(), runner.run(table, s, config));
        }

        // ── 공통 날짜 정렬 ──
        Map<LocalDate, Double> benchmark = RealDataCrossSectionalExperimentTest.dailyReturns(benchmarkCandles);
        TreeSet<LocalDate> common = new TreeSet<>(benchmark.keySet());
        for (CrossSectionalBacktestRunner.Result r : results.values()) {
            assertFalse(r.dailyReturns().isEmpty(), "수익률 비어 있음");
            common.retainAll(new TreeSet<>(r.dates()));
        }
        List<LocalDate> dates = new ArrayList<>(common);
        double[] bench = new double[dates.size()];
        for (int i = 0; i < bench.length; i++) {
            bench[i] = benchmark.get(dates.get(i));
        }
        Map<String, double[]> pool = new LinkedHashMap<>();
        for (Map.Entry<String, CrossSectionalBacktestRunner.Result> e : results.entrySet()) {
            Map<LocalDate, Double> byDate = new LinkedHashMap<>();
            for (int i = 0; i < e.getValue().dates().size(); i++) {
                byDate.put(e.getValue().dates().get(i), e.getValue().dailyReturns().get(i));
            }
            double[] arr = new double[dates.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = byDate.get(dates.get(i));
            }
            pool.put(e.getKey(), arr);
        }
        SpaTest.SpaResult v3 = SpaTest.volatilityMatched(bench, pool, BLOCK_LENGTH, RESAMPLES, SEED).run();
        SpaTest.SpaResult v2 = new SpaTest(bench, pool, BLOCK_LENGTH, RESAMPLES, SEED).run();
        assertFalse(Double.isNaN(v3.pValueSpaConsistent()), "SPA v3 p NaN");

        PerformanceCalculator calc = new PerformanceCalculator();
        List<Double> benchList = new ArrayList<>();
        for (double r : bench) {
            benchList.add(r);
        }
        double benchTotal = 1.0;
        for (double r : bench) {
            benchTotal *= 1 + r;
        }

        System.out.println();
        System.out.println("=== 트랙 F — 재무 팩터 3종 게이트 ① v3 판정 수치 (사전 선언 trial +3, 누적 " + TRIALS + ") ===");
        System.out.println("재무 행 수(회계연도→행): " + coverage);
        System.out.printf("SPA 구간 %s ~ %s (%d거래일), 유니버스 KRX 시총 상위 %d, 비용 defaults, L=%d B=%d seed=%d, 중기 MDD 예산 %.0f%%%n",
                dates.get(0), dates.get(dates.size() - 1), dates.size(), UNIVERSE_SIZE, BLOCK_LENGTH, RESAMPLES, SEED, MID_MDD_BUDGET * 100);
        System.out.printf("기준선 KODEX200 B&H: 총수익 %+.1f%%, MDD %.1f%%%n", (benchTotal - 1) * 100, calc.mdd(benchList) * 100);
        System.out.println();
        System.out.printf("%-30s | %9s | %7s | %7s | %6s | %6s | %7s | %8s | %8s | %6s | %7s%n",
                "계열", "OOS총수익", "CAGR", "MDD", "Sharpe", "평균보유", "회전율", "부트p95", "P(>25%)", "w", "DSR");
        System.out.println("-".repeat(130));
        for (Map.Entry<String, CrossSectionalBacktestRunner.Result> e : results.entrySet()) {
            CrossSectionalBacktestRunner.Result r = e.getValue();
            BacktestResult p = r.result();
            double[] arr = pool.get(e.getKey());
            StationaryBootstrap.MddSummary mdd = new StationaryBootstrap(arr, BLOCK_LENGTH, RESAMPLES, SEED).mddDistribution(MID_MDD_BUDGET);
            System.out.printf("%-30s | %8.1f%% | %6.2f%% | %6.1f%% | %6.2f | %6.1f | %6.1f%% | %7.1f%% | %7.1f%% | %6.3f | %7.3f%n",
                    e.getKey(), p.totalReturn() * 100, p.cagr() * 100, p.mdd() * 100, p.sharpe(), r.avgHoldings(),
                    r.avgTurnover() * 100, mdd.p95() * 100, mdd.probabilityExceedsBudget() * 100,
                    VolatilityMatchedBenchmark.weight(arr, bench), p.dsrConfidence());
        }
        System.out.println();
        System.out.printf("SPA_c v3(변동성 매칭 KODEX200+현금) p = %.4f  (판정, p<0.05 필요)%n", v3.pValueSpaConsistent());
        for (SpaTest.CandidateStat cs : v3.candidateStats()) {
            System.out.printf("  %-30s | 평균초과(일) %9.5f%% | t %7.3f%n", cs.label(), cs.meanExcessReturn() * 100, cs.tStat());
        }
        System.out.printf("SPA_c v2(KODEX200 B&H) p = %.4f  (병기)%n", v2.pValueSpaConsistent());
        for (SpaTest.CandidateStat cs : v2.candidateStats()) {
            System.out.printf("  %-30s | 평균초과(일) %9.5f%% | t %7.3f%n", cs.label(), cs.meanExcessReturn() * 100, cs.tStat());
        }
        System.out.println("[캐비엇] 생존 편향(야후에 없는 상장폐지 종목) → 상한 추정치. 결과를 본 뒤의 규칙 수정은 새 trial 선언 없이는 금지.");
    }
}
