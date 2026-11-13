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
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 트랙 Y — 자산배분 3종, 게이트 ① v3(ADR-15, autoStock D-30) 첫 적용. 사전 선언 trial +3(누적 24).
 *
 * <p>규칙·파라미터는 docs/research/adr15_preregistration_20261114.md 3장에서 실행 전에 고정했다. 결과를 본 뒤의
 * 수정은 새 trial 선언 없이는 금지. 공통 조건은 D 계열({@link RealDataDualMomentumExperimentTest})과 같다:
 * 4 ETF + 현금(0%), 공통 거래일, 워밍업 253거래일, 21거래일마다 점검, 결정은 직전 확정 종가(i−1)만,
 * ETF 비용 모델(수수료 0.015%·슬리피지 5bp·거래세 0%).
 *
 * <p>D 계열과 달리 리밸런싱 사이에는 <b>비중이 가격대로 떠다닌다</b>(보유 금액을 날마다 갱신). 다자산 고정 비중을
 * 날마다 공짜로 맞추는 가정을 피하기 위해서다. 체결은 결정일 직전 종가 기준이고 비용은 회전율 × 편도 비용.
 *
 * <p>데이터가 없으면 스킵한다(data/는 git 밖). 성과 수치는 assert하지 않고 출력하며, 판정 기록은 사람이 남긴다.
 */
class RealDataAssetAllocationExperimentTest {

    private static final String KODEX200 = "069500";
    private static final String SP500 = "143850";
    private static final String KTB10 = "148070";
    private static final String GOLD = "132030";
    private static final List<String> ASSETS = List.of(KODEX200, SP500, KTB10, GOLD);
    private static final String CASH = "CASH";

    private static final int WARMUP = 253;
    private static final int CHECK_INTERVAL = 21;
    private static final int VOL_LOOKBACK = 252;
    private static final int SMA_LOOKBACK = 210;
    private static final double BAND_LOW = 0.15;
    private static final double BAND_HIGH = 0.35;

    private static final CostModel ETF_COST = new CostModel(0.00015, 0.00015, 0.0, 0.0005);

    /** 장기 지평 MDD 예산(ADR-12). */
    private static final double LONG_MDD_BUDGET = 0.25;
    /** 누적 trial: 기존 21계열 + Y 3종. */
    private static final int CUMULATIVE_TRIALS = 24;
    private static final int RESAMPLES = 1000;
    private static final int BLOCK_LENGTH = StationaryBootstrap.DEFAULT_MEAN_BLOCK_LENGTH;
    private static final long SEED = StationaryBootstrap.DEFAULT_SEED;
    private static final int DAYS_PER_YEAR = 252;

    private final PerformanceCalculator calculator = new PerformanceCalculator();

    private enum Rule { Y1_PERMANENT, Y2_INVERSE_VOL, Y3_FABER_GTAA }

    private record Sim(String label, List<Double> dailyReturns, int rebalances, double avgCashWeight) {
    }

    @Test
    void 트랙Y_자산배분_3종_게이트v3_판정수치() {
        Path dataDir = resolveDataDir();
        assumeTrue(dataDir != null, "data/ 디렉터리 없음 — 스킵");
        CandleCsvLoader loader = new CandleCsvLoader();
        Map<String, List<Candle>> candles = new LinkedHashMap<>();
        for (String code : ASSETS) {
            Path csv = dataDir.resolve(code + ".csv");
            assumeTrue(Files.isRegularFile(csv), code + ".csv 없음 — 스킵");
            candles.put(code, loader.load(csv));
        }
        List<LocalDate> dates = commonDates(candles);
        assumeTrue(dates.size() > WARMUP + CHECK_INTERVAL * 12, "공통 거래일 부족(" + dates.size() + ")");
        Map<String, double[]> closes = alignCloses(candles, dates);
        int n = dates.size();

        List<Sim> sims = List.of(
                simulate(Rule.Y1_PERMANENT, "Y1.영구포트폴리오", closes, n),
                simulate(Rule.Y2_INVERSE_VOL, "Y2.역변동성", closes, n),
                simulate(Rule.Y3_FABER_GTAA, "Y3.Faber_GTAA", closes, n));

        double[] bench = new double[n - WARMUP];
        double[] kodex = closes.get(KODEX200);
        for (int i = WARMUP; i < n; i++) {
            bench[i - WARMUP] = kodex[i] / kodex[i - 1] - 1;
        }

        Map<String, double[]> pool = new LinkedHashMap<>();
        for (Sim s : sims) {
            assertEquals(bench.length, s.dailyReturns().size(), s.label() + ": 길이 불일치");
            assertFalse(s.dailyReturns().stream().anyMatch(r -> Double.isNaN(r)), s.label() + ": NaN 수익률");
            pool.put(s.label(), toArray(s.dailyReturns()));
        }
        SpaTest.SpaResult spaV3 = SpaTest.volatilityMatched(bench, pool, BLOCK_LENGTH, RESAMPLES, SEED).run();
        SpaTest.SpaResult spaV2 = new SpaTest(bench, pool, BLOCK_LENGTH, RESAMPLES, SEED).run();
        assertFalse(Double.isNaN(spaV3.pValueSpaConsistent()), "SPA v3 p NaN");

        double[] dailySharpes = new double[sims.size()];
        for (int k = 0; k < sims.size(); k++) {
            dailySharpes[k] = calculator.sharpe(sims.get(k).dailyReturns()) / Math.sqrt(DAYS_PER_YEAR);
        }
        double sharpeVariance = variance(dailySharpes);

        List<Double> benchList = toList(bench);
        System.out.println();
        System.out.println("=== 트랙 Y — 자산배분 3종 게이트 ① v3 판정 수치 (사전 선언 trial +3, 누적 " + CUMULATIVE_TRIALS + ") ===");
        System.out.printf("OOS %s ~ %s (%d거래일), 4 ETF + 현금 0%%, 21거래일 점검, ETF 비용, L=%d B=%d seed=%d, 장기 MDD 예산 %.0f%%%n",
                dates.get(WARMUP), dates.get(n - 1), bench.length, BLOCK_LENGTH, RESAMPLES, SEED, LONG_MDD_BUDGET * 100);
        System.out.printf("기준선 KODEX200 B&H: 총수익 %+.1f%%, CAGR %.2f%%, MDD %.1f%%, Sharpe %.2f%n",
                (compound(benchList) - 1) * 100, cagr(benchList) * 100, calculator.mdd(benchList) * 100, calculator.sharpe(benchList));
        System.out.println();
        System.out.printf("%-18s | %9s | %7s | %7s | %6s | %6s | %5s | %9s | %8s | %6s | %9s | %7s | %7s%n",
                "계열", "OOS총수익", "CAGR", "MDD", "Sharpe", "리밸", "현금", "부트p95", "P(>25%)", "w", "매칭B총수익", "DSR3", "DSR24");
        System.out.println("-".repeat(150));
        for (Sim s : sims) {
            List<Double> r = s.dailyReturns();
            double[] arr = toArray(r);
            StationaryBootstrap.MddSummary mdd = new StationaryBootstrap(arr, BLOCK_LENGTH, RESAMPLES, SEED).mddDistribution(LONG_MDD_BUDGET);
            double w = VolatilityMatchedBenchmark.weight(arr, bench);
            double matchedTotal = compound(toList(VolatilityMatchedBenchmark.series(arr, bench))) - 1;
            double dsr3 = calculator.deflatedSharpeAcrossFamilies(r, dailySharpes);
            double dsr24 = calculator.calculate(r, BigDecimal.ONE, BigDecimal.valueOf(compound(r)),
                    s.rebalances(), CUMULATIVE_TRIALS, sharpeVariance).dsrConfidence();
            System.out.printf("%-18s | %8.1f%% | %6.2f%% | %6.1f%% | %6.2f | %6d | %4.0f%% | %8.1f%% | %7.1f%% | %6.3f | %8.1f%% | %7.3f | %7.3f%n",
                    s.label(), (compound(r) - 1) * 100, cagr(r) * 100, calculator.mdd(r) * 100, calculator.sharpe(r),
                    s.rebalances(), s.avgCashWeight() * 100, mdd.p95() * 100, mdd.probabilityExceedsBudget() * 100,
                    w, matchedTotal * 100, dsr3, dsr24);
        }
        System.out.println();
        System.out.printf("SPA_c v3(벤치마크 = 후보별 변동성 매칭 KODEX200+현금) p = %.4f  (판정, p<0.05 필요)%n", spaV3.pValueSpaConsistent());
        for (SpaTest.CandidateStat cs : spaV3.candidateStats()) {
            System.out.printf("  %-18s | 평균초과(일) %9.5f%% | t %7.3f%n", cs.label(), cs.meanExcessReturn() * 100, cs.tStat());
        }
        System.out.printf("SPA_c v2(벤치마크 = KODEX200 B&H) p = %.4f  (병기, 판정 아님)%n", spaV2.pValueSpaConsistent());
        for (SpaTest.CandidateStat cs : spaV2.candidateStats()) {
            System.out.printf("  %-18s | 평균초과(일) %9.5f%% | t %7.3f%n", cs.label(), cs.meanExcessReturn() * 100, cs.tStat());
        }
        System.out.println("[판정 순서] ⓐ OOS 수익>0 ⓑ SPA_c v3 p<0.05(풀 = Y 3종) ⓒ 부트 p95 MDD<25% ⓓ 총계좌 MDD<15%(통과 계열이 나온 뒤)");
        System.out.println("[캐비엇] w는 전 구간 변동성으로 정한 비교 기준값(후보는 이 정보를 쓰지 않음). DSR24의 계열 간 분산은 Y 3종 샤프 분산으로 근사.");
    }

    // ─────────────────────────────── 시뮬레이션 ───────────────────────────────

    private Sim simulate(Rule rule, String label, Map<String, double[]> closes, int n) {
        Map<String, Double> value = new LinkedHashMap<>();
        for (String a : ASSETS) {
            value.put(a, 0.0);
        }
        value.put(CASH, 1.0);
        List<Double> daily = new ArrayList<>();
        int rebalances = 0;
        double cashWeightSum = 0.0;
        double buyCost = ETF_COST.buyFeePct() + ETF_COST.slippagePct();
        double sellCost = ETF_COST.sellFeePct() + ETF_COST.sellTaxPct() + ETF_COST.slippagePct();

        for (int i = WARMUP; i < n; i++) {
            double before = total(value);
            if ((i - WARMUP) % CHECK_INTERVAL == 0) {
                Map<String, Double> current = weights(value);
                Map<String, Double> target = target(rule, current, closes, i - 1);
                if (target != null) {
                    double cost = 0.0;
                    for (String a : ASSETS) {
                        double delta = target.getOrDefault(a, 0.0) - current.getOrDefault(a, 0.0);
                        cost += delta > 0 ? delta * buyCost : -delta * sellCost;
                    }
                    if (cost > 1e-12) {
                        rebalances++;
                    }
                    double after = before * (1 - cost);
                    for (String k : value.keySet()) {
                        value.put(k, after * target.getOrDefault(k, 0.0));
                    }
                }
            }
            for (String a : ASSETS) {
                double[] c = closes.get(a);
                value.put(a, value.get(a) * (c[i] / c[i - 1]));
            }
            double end = total(value);
            daily.add(end / before - 1);
            cashWeightSum += value.get(CASH) / end;
        }
        return new Sim(label, daily, rebalances, cashWeightSum / (n - WARMUP));
    }

    /** 결정일 asOf(=i−1) 기준 목표 비중. null이면 이번 점검에서 거래하지 않음(Y1 밴드 안). */
    private Map<String, Double> target(Rule rule, Map<String, Double> current, Map<String, double[]> closes, int asOf) {
        Map<String, Double> t = new LinkedHashMap<>();
        switch (rule) {
            case Y1_PERMANENT -> {
                List<String> held = List.of(KODEX200, KTB10, GOLD, CASH);
                boolean outOfBand = false;
                for (String k : held) {
                    double w = current.getOrDefault(k, 0.0);
                    if (w < BAND_LOW || w > BAND_HIGH) {
                        outOfBand = true;
                    }
                }
                if (!outOfBand) {
                    return null;
                }
                for (String k : held) {
                    t.put(k, 0.25);
                }
            }
            case Y2_INVERSE_VOL -> {
                double sumInv = 0.0;
                Map<String, Double> inv = new LinkedHashMap<>();
                for (String a : ASSETS) {
                    double v = 1.0 / dailyVol(closes.get(a), asOf, VOL_LOOKBACK);
                    inv.put(a, v);
                    sumInv += v;
                }
                for (String a : ASSETS) {
                    t.put(a, inv.get(a) / sumInv);
                }
            }
            case Y3_FABER_GTAA -> {
                double cash = 0.0;
                for (String a : ASSETS) {
                    double[] c = closes.get(a);
                    if (c[asOf] > sma(c, asOf, SMA_LOOKBACK)) {
                        t.put(a, 0.25);
                    } else {
                        cash += 0.25;
                    }
                }
                t.put(CASH, cash);
            }
        }
        double sum = t.values().stream().mapToDouble(Double::doubleValue).sum();
        assertTrue(Math.abs(sum - 1.0) < 1e-9, rule + ": 목표 비중 합 " + sum);
        return t;
    }

    private static double dailyVol(double[] c, int asOf, int lookback) {
        double[] r = new double[lookback];
        for (int j = 0; j < lookback; j++) {
            int idx = asOf - lookback + 1 + j;
            r[j] = c[idx] / c[idx - 1] - 1;
        }
        return Math.sqrt(variance(r));
    }

    private static double sma(double[] c, int asOf, int lookback) {
        double s = 0.0;
        for (int j = asOf - lookback + 1; j <= asOf; j++) {
            s += c[j];
        }
        return s / lookback;
    }

    private static double total(Map<String, Double> value) {
        return value.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    private static Map<String, Double> weights(Map<String, Double> value) {
        double tot = total(value);
        Map<String, Double> w = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : value.entrySet()) {
            w.put(e.getKey(), e.getValue() / tot);
        }
        return w;
    }

    // ─────────────────────────────── 유틸 ───────────────────────────────

    private static List<LocalDate> commonDates(Map<String, List<Candle>> candles) {
        TreeSet<LocalDate> common = null;
        for (List<Candle> list : candles.values()) {
            TreeSet<LocalDate> d = new TreeSet<>();
            for (Candle c : list) {
                d.add(c.date());
            }
            if (common == null) {
                common = d;
            } else {
                common.retainAll(d);
            }
        }
        return common == null ? List.of() : new ArrayList<>(common);
    }

    private static Map<String, double[]> alignCloses(Map<String, List<Candle>> candles, List<LocalDate> dates) {
        Map<String, double[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Candle>> e : candles.entrySet()) {
            Map<LocalDate, Double> byDate = new LinkedHashMap<>();
            for (Candle c : e.getValue()) {
                byDate.put(c.date(), c.close().doubleValue());
            }
            double[] arr = new double[dates.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = byDate.get(dates.get(i));
            }
            out.put(e.getKey(), arr);
        }
        return out;
    }

    private static double compound(List<Double> r) {
        double eq = 1.0;
        for (double v : r) {
            eq *= 1 + v;
        }
        return eq;
    }

    private static double cagr(List<Double> r) {
        return Math.pow(compound(r), DAYS_PER_YEAR / (double) r.size()) - 1;
    }

    private static double variance(double[] a) {
        double m = 0.0;
        for (double v : a) {
            m += v;
        }
        m /= a.length;
        double s = 0.0;
        for (double v : a) {
            s += (v - m) * (v - m);
        }
        return s / a.length;
    }

    private static double[] toArray(List<Double> list) {
        double[] a = new double[list.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = list.get(i);
        }
        return a;
    }

    private static List<Double> toList(double[] a) {
        List<Double> out = new ArrayList<>(a.length);
        for (double v : a) {
            out.add(v);
        }
        return out;
    }

    private static Path resolveDataDir() {
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
