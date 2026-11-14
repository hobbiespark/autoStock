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
import java.util.function.IntToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 트랙 Z — 지수 타이밍 3종(Z1 변동성 관리·Z2 할로윈·Z4 월말 효과) + 시총가중 품질 필터(Z3), 게이트 ① v3.
 * 사전 선언 trial +4(누적 31). 규칙은 docs/research/trackz_preregistration_20261114.md에서 실행 전에 고정했다.
 * SPA 풀은 네 계열 한 풀이고 구간은 공통 거래일이다. Z3₀(시총가중 전체)는 진단이며 풀에 넣지 않는다.
 */
class RealDataTrackZExperimentTest {

    private static final String KODEX200 = "069500";
    private static final String CALENDAR_SYMBOL = "005930";
    private static final int WARMUP = 253;
    private static final CostModel ETF_COST = new CostModel(0.00015, 0.00015, 0.0, 0.0005);
    private static final LocalDate Z3_OOS_START = LocalDate.of(2016, 4, 1);
    private static final LocalDate Z3_OOS_END = LocalDate.of(2026, 8, 31);
    private static final int TRIALS = 31;
    private static final double MDD_BUDGET = 0.25;
    private static final int RESAMPLES = 1000;
    private static final int BLOCK_LENGTH = StationaryBootstrap.DEFAULT_MEAN_BLOCK_LENGTH;
    private static final long SEED = StationaryBootstrap.DEFAULT_SEED;

    private final PerformanceCalculator calc = new PerformanceCalculator();

    private record Series(String label, List<LocalDate> dates, List<Double> returns, int trades, double timeInMarket) {
    }

    @Test
    void 트랙Z_새가설_4종_게이트v3_판정수치() {
        Path dataDir = RealDataCrossSectionalExperimentTest.resolveDataDir();
        assumeTrue(dataDir != null, "data/ 디렉터리 없음 — 스킵");
        Path universeDir = dataDir.resolve("universe").resolve("prices");
        Path capDir = dataDir.resolve("universe").resolve("marketcap");
        Path fsDir = dataDir.resolve("fscore");
        assumeTrue(Files.isRegularFile(dataDir.resolve(KODEX200 + ".csv")), "069500.csv 없음");
        assumeTrue(Files.isDirectory(universeDir) && Files.isDirectory(capDir) && Files.isDirectory(fsDir), "가격·시총·재무 폴더 없음");
        FundamentalsTable fundamentals = FundamentalsTable.fromCsvDirectory(fsDir);
        for (int fy = 2015; fy <= 2025; fy++) {
            assumeTrue(fundamentals.rowCountByYear().getOrDefault(fy, 0) >= 300, "fs2_" + fy + " 부족");
        }

        // ── Z1·Z2·Z4: KODEX200 + 현금 ──
        CandleCsvLoader loader = new CandleCsvLoader();
        List<Candle> kodex = loader.load(dataDir.resolve(KODEX200 + ".csv"));
        List<LocalDate> kDates = kodex.stream().map(Candle::date).toList();
        double[] close = kodex.stream().mapToDouble(c -> c.close().doubleValue()).toArray();
        List<Series> timing = List.of(
                simulateTiming("Z1.변동성관리", kDates, close, i -> {
                    boolean newMonth = i == WARMUP || kDates.get(i).getMonthValue() != kDates.get(i - 1).getMonthValue();
                    return newMonth ? IndexTimingRules.volatilityManagedWeight(close, i - 1) : Double.NaN;
                }),
                simulateTiming("Z2.할로윈", kDates, close, i -> IndexTimingRules.halloweenHold(kDates.get(i)) ? 1.0 : 0.0),
                simulateTiming("Z4.월말효과", kDates, close, i -> IndexTimingRules.turnOfMonthHold(kDates, i) ? 1.0 : 0.0));

        // ── Z3·Z3₀: 횡단면 엔진 ──
        NavigableMap<LocalDate, Map<String, Double>> caps = RealDataCrossSectionalExperimentTest.marketCapSnapshots(capDir);
        List<LocalDate> calendar = loader.load(universeDir.resolve(CALENDAR_SYMBOL + ".csv")).stream().map(Candle::date).toList();
        Set<String> excluded = RealDataCrossSectionalExperimentTest.nonOperatingCompanies(dataDir.resolve("corp_map_all.csv"));
        CrossSectionalBacktestRunner.PriceTable table =
                CrossSectionalBacktestRunner.PriceTable.fromCsvDirectory(universeDir, calendar, 300, excluded);
        CrossSectionalBacktestRunner.Config config = new CrossSectionalBacktestRunner.Config(
                Z3_OOS_START, Z3_OOS_END, 200, 60, new BigDecimal("100000000"), CostModel.defaults(), TRIALS, caps, 0.40);
        CrossSectionalBacktestRunner runner = new CrossSectionalBacktestRunner();
        CrossSectionalBacktestRunner.Result z3 = runner.run(table, FactorStrategies.capWeightedQualityFilter(fundamentals, 3), config);
        CrossSectionalBacktestRunner.Result z30 = runner.run(table, FactorStrategies.capWeightedAll(), config);

        List<Series> pool = new ArrayList<>(timing);
        pool.add(new Series("Z3.시총가중품질", z3.dates(), z3.dailyReturns(), z3.trades(), Double.NaN));
        Series diag = new Series("Z3₀.시총가중전체(진단)", z30.dates(), z30.dailyReturns(), z30.trades(), Double.NaN);

        // ── 공통 거래일 정렬 ──
        Map<LocalDate, Double> bench = RealDataCrossSectionalExperimentTest.dailyReturns(kodex);
        TreeSet<LocalDate> common = new TreeSet<>(bench.keySet());
        for (Series s : pool) {
            common.retainAll(new TreeSet<>(s.dates()));
        }
        common.retainAll(new TreeSet<>(diag.dates()));
        List<LocalDate> dates = new ArrayList<>(common);
        double[] b = new double[dates.size()];
        for (int i = 0; i < b.length; i++) {
            b[i] = bench.get(dates.get(i));
        }
        Map<String, double[]> candidates = new LinkedHashMap<>();
        for (Series s : pool) {
            candidates.put(s.label(), align(s, dates));
        }
        double[] diagArr = align(diag, dates);
        SpaTest.SpaResult v3 = SpaTest.volatilityMatched(b, candidates, BLOCK_LENGTH, RESAMPLES, SEED).run();
        SpaTest.SpaResult v2 = new SpaTest(b, candidates, BLOCK_LENGTH, RESAMPLES, SEED).run();
        assertFalse(Double.isNaN(v3.pValueSpaConsistent()), "SPA v3 p NaN");

        double[] dailySharpes = candidates.values().stream()
                .mapToDouble(a -> calc.sharpe(toList(a)) / Math.sqrt(252)).toArray();
        double sharpeVar = variance(dailySharpes);

        System.out.println();
        System.out.println("=== 트랙 Z — 새 가설 4종 게이트 ① v3 판정 수치 (사전 선언 trial +4, 누적 " + TRIALS + ") ===");
        System.out.printf("SPA 공통 구간 %s ~ %s (%d거래일), L=%d B=%d seed=%d, MDD 예산 %.0f%%%n",
                dates.get(0), dates.get(dates.size() - 1), dates.size(), BLOCK_LENGTH, RESAMPLES, SEED, MDD_BUDGET * 100);
        List<Double> bl = toList(b);
        System.out.printf("기준선 KODEX200 B&H: 총수익 %+.1f%%, CAGR %.2f%%, MDD %.1f%%, Sharpe %.2f%n",
                (compound(bl) - 1) * 100, cagr(bl) * 100, calc.mdd(bl) * 100, calc.sharpe(bl));
        System.out.println();
        System.out.printf("%-22s | %9s | %7s | %7s | %6s | %6s | %6s | %8s | %8s | %6s | %7s%n",
                "계열", "총수익", "CAGR", "MDD", "Sharpe", "거래", "보유일", "부트p95", "P(>25%)", "w", "DSR31");
        System.out.println("-".repeat(125));
        Map<String, Double> timeIn = new LinkedHashMap<>();
        Map<String, Integer> tradeCount = new LinkedHashMap<>();
        for (Series s : pool) {
            timeIn.put(s.label(), s.timeInMarket());
            tradeCount.put(s.label(), s.trades());
        }
        for (Map.Entry<String, double[]> e : candidates.entrySet()) {
            printRow(e.getKey(), e.getValue(), b, tradeCount.get(e.getKey()), timeIn.get(e.getKey()), sharpeVar);
        }
        printRow(diag.label(), diagArr, b, diag.trades(), Double.NaN, sharpeVar);
        System.out.println();
        System.out.printf("SPA_c v3(변동성 매칭 KODEX200+현금, 풀 = Z1~Z4) p = %.4f  (판정, p<0.05 필요)%n", v3.pValueSpaConsistent());
        for (SpaTest.CandidateStat cs : v3.candidateStats()) {
            System.out.printf("  %-22s | 평균초과(일) %9.5f%% | t %7.3f%n", cs.label(), cs.meanExcessReturn() * 100, cs.tStat());
        }
        System.out.printf("SPA_c v2(KODEX200 B&H) p = %.4f  (병기)%n", v2.pValueSpaConsistent());
        for (SpaTest.CandidateStat cs : v2.candidateStats()) {
            System.out.printf("  %-22s | 평균초과(일) %9.5f%% | t %7.3f%n", cs.label(), cs.meanExcessReturn() * 100, cs.tStat());
        }
        System.out.println("[읽는 법] Z3 − Z3₀ = 품질 필터의 효과, Z3₀ − KODEX200 = 유니버스(코스닥 포함·KOSPI200과 다름)와 비용의 차이.");
        System.out.println("[캐비엇] 생존 편향(Z3), 현금 0%(양쪽 같음), w는 비교 기준값. 결과 뒤 규칙 수정 금지.");
    }

    private void printRow(String label, double[] r, double[] bench, int trades, double timeInMarket, double sharpeVar) {
        List<Double> l = toList(r);
        StationaryBootstrap.MddSummary mdd = new StationaryBootstrap(r, BLOCK_LENGTH, RESAMPLES, SEED).mddDistribution(MDD_BUDGET);
        double dsr = calc.calculate(l, BigDecimal.ONE, BigDecimal.valueOf(compound(l)), trades, TRIALS, sharpeVar).dsrConfidence();
        System.out.printf("%-22s | %8.1f%% | %6.2f%% | %6.1f%% | %6.2f | %6d | %5s | %7.1f%% | %7.1f%% | %6.3f | %7.3f%n",
                label, (compound(l) - 1) * 100, cagr(l) * 100, calc.mdd(l) * 100, calc.sharpe(l), trades,
                Double.isNaN(timeInMarket) ? "-" : String.format("%.0f%%", timeInMarket * 100),
                mdd.p95() * 100, mdd.probabilityExceedsBudget() * 100, VolatilityMatchedBenchmark.weight(r, bench), dsr);
    }

    /**
     * KODEX200 + 현금 단일 자산 시뮬레이션. target(i)는 날 i의 목표 비중(NaN이면 그대로 둠), i−1 종가에 거래하고
     * |Δw| × 편도 비용을 뺀다. 비중 사이는 가격대로 떠다닌다.
     */
    private Series simulateTiming(String label, List<LocalDate> dates, double[] close, IntToDoubleFunction target) {
        double stock = 0.0;
        double cash = 1.0;
        int trades = 0;
        double inMarketDays = 0;
        double buyCost = ETF_COST.buyFeePct() + ETF_COST.slippagePct();
        double sellCost = ETF_COST.sellFeePct() + ETF_COST.sellTaxPct() + ETF_COST.slippagePct();
        List<LocalDate> outDates = new ArrayList<>();
        List<Double> out = new ArrayList<>();
        for (int i = WARMUP; i < close.length; i++) {
            double before = stock + cash;
            double t = target.applyAsDouble(i);
            if (!Double.isNaN(t)) {
                double current = stock / before;
                double delta = t - current;
                if (Math.abs(delta) > 1e-9) {
                    double cost = delta > 0 ? delta * buyCost : -delta * sellCost;
                    double after = before * (1 - cost);
                    stock = after * t;
                    cash = after - stock;
                    trades++;
                }
            }
            if (stock > 0) {
                inMarketDays++;
            }
            stock *= close[i] / close[i - 1];
            outDates.add(dates.get(i));
            out.add((stock + cash) / before - 1);
        }
        return new Series(label, outDates, out, trades, inMarketDays / out.size());
    }

    private static double[] align(Series s, List<LocalDate> dates) {
        Map<LocalDate, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < s.dates().size(); i++) {
            m.put(s.dates().get(i), s.returns().get(i));
        }
        double[] a = new double[dates.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = m.get(dates.get(i));
        }
        return a;
    }

    private static double compound(List<Double> r) {
        double e = 1.0;
        for (double v : r) {
            e *= 1 + v;
        }
        return e;
    }

    private static double cagr(List<Double> r) {
        return Math.pow(compound(r), 252.0 / r.size()) - 1;
    }

    private static double variance(double[] a) {
        double m = 0;
        for (double v : a) {
            m += v;
        }
        m /= a.length;
        double s = 0;
        for (double v : a) {
            s += (v - m) * (v - m);
        }
        return s / a.length;
    }

    private static List<Double> toList(double[] a) {
        List<Double> out = new ArrayList<>(a.length);
        for (double v : a) {
            out.add(v);
        }
        return out;
    }
}
