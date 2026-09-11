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
import java.util.TreeSet;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 트랙 H — TradingView 인기 차트 기법 5종 trial 배치 (사전 선언 2026-09-11, trial +5 →
 * 연구 누적 13~17번째 계열).
 *
 * <h2>사전 선언 (스누핑 방지 — 결과 확인 후 파라미터 변경 금지)</h2>
 * <ul>
 *   <li>H1 일목균형표 구름 돌파 — 표준 (9, 26, 52, 선행 26)</li>
 *   <li>H2 볼린저 스퀴즈 돌파 — BB(20, 2.0), 스퀴즈=밴드폭 120일 하위 20%, 손절 -3%</li>
 *   <li>H3 SuperTrend(10, 3.0)</li>
 *   <li>H4 RSI(2) 평균회귀 — Connors 표준 (진입 RSI&lt;10, SMA200 필터, SMA5 청산)</li>
 *   <li>H5 돌파-리테스트(S/R 플립) — 전고 60일, 대기 15일, 보유 상한 20일, 손절 레벨 -3%</li>
 * </ul>
 * 전부 파라미터 <b>단일 고정(후보 1개)</b> — walk-forward는 기존 계열과 동일한 창 구조
 * (train 252 / test 63)를 유지하되 파라미터 선택은 발생하지 않는다(스윕 자체가 없음).
 *
 * <h2>판정 — 게이트 v2 (ADR-12)</h2>
 * 5종목 슬리브 포트폴리오(A~C 계열과 동일 유니버스·CostModel.defaults()), 벤치마크
 * KODEX200 buy&amp;hold. SPA_c p&lt;0.05 + 부트스트랩 p95 MDD &lt; 15%(이 계열들은 보유
 * 수일~수십일의 스윙 지평 — ADR-12 스윙 예산 적용) + OOS 수익 양수. 이 테스트는 판정
 * assert를 하지 않고(관례) 수치를 표로 출력한다 — 해석·기록은 사람이 한다.
 *
 * <p>데이터가 없으면 조용히 스킵한다(다른 Real* 테스트와 동일 — data/는 git 미커밋).
 */
class RealDataTaChartExperimentTest {

    private static final Map<String, String> SYMBOLS = new LinkedHashMap<>();
    static {
        SYMBOLS.put("005930", "삼성전자");
        SYMBOLS.put("000660", "SK하이닉스");
        SYMBOLS.put("035420", "NAVER");
        SYMBOLS.put("035720", "카카오");
        SYMBOLS.put("069500", "KODEX200");
    }
    private static final String BASELINE_SYMBOL = "069500";

    private static final int TRAIN_SIZE = 252;
    private static final int TEST_SIZE = 63;
    private static final BigDecimal SLEEVE_CAPITAL = new BigDecimal("2000000");

    /** 스윙 지평 MDD 예산 (ADR-12 게이트 v2). */
    private static final double SWING_MDD_BUDGET = 0.15;

    private static final int EXPERIMENT_RESAMPLES = 1000;
    private static final int BLOCK_LENGTH = StationaryBootstrap.DEFAULT_MEAN_BLOCK_LENGTH;
    private static final long SEED = StationaryBootstrap.DEFAULT_SEED;

    private final CandleCsvLoader loader = new CandleCsvLoader();
    private final PortfolioBacktestRunner portfolioRunner = new PortfolioBacktestRunner();

    @Test
    void TA차트기법_5종_게이트v2_판정수치() {
        Path dataDir = resolveDataDir();
        assumeTrue(dataDir != null, "data/ 디렉터리 없음 — 스킵(scripts/fetch_yahoo_daily.py로 채울 것)");
        for (String code : SYMBOLS.keySet()) {
            assumeTrue(Files.isRegularFile(dataDir.resolve(code + ".csv")),
                    "종목 " + code + " CSV 없음: " + dataDir.resolve(code + ".csv"));
        }

        Map<String, List<Candle>> candlesBySymbol = new LinkedHashMap<>();
        for (String code : SYMBOLS.keySet()) {
            candlesBySymbol.put(code, loader.load(dataDir.resolve(code + ".csv")));
        }
        BigDecimal totalCapital = SLEEVE_CAPITAL.multiply(BigDecimal.valueOf(SYMBOLS.size()));

        // ── 사전 선언 5종 — 파라미터 후보 1개(스윕 없음), 전략별 지표 워밍업 ──
        record Def(String label, Function<Object, BacktestStrategy> factory, int warmup) {
        }
        List<Def> defs = List.of(
                new Def("H1.일목구름돌파(9,26,52)", p -> new IchimokuCloudStrategy(), 80),
                new Def("H2.볼린저스퀴즈(20,2,120)", p -> new BollingerSqueezeStrategy(), 145),
                new Def("H3.SuperTrend(10,3)", p -> new SuperTrendStrategy(), 15),
                new Def("H4.RSI2평균회귀(Connors)", p -> new Rsi2MeanReversionStrategy(), 202),
                new Def("H5.돌파리테스트(60,15,20)", p -> new BreakoutRetestStrategy(), 62));
        List<Object> singleCandidate = List.of("표준파라미터고정");

        Map<String, PortfolioBacktestRunner.PortfolioResult> results = new LinkedHashMap<>();
        for (Def def : defs) {
            WalkForwardRunner runner = new WalkForwardRunner(CostModel.defaults());
            PortfolioBacktestRunner.SleeveRunner sleeveRunner = (candles, sleeveCapital) -> runner.run(
                    candles, singleCandidate, def.factory(), TRAIN_SIZE, TEST_SIZE, def.warmup(), sleeveCapital);
            results.put(def.label(),
                    portfolioRunner.run(candlesBySymbol, sleeveRunner, totalCapital, TRAIN_SIZE, TEST_SIZE));
        }

        // ── 무결성 확인(판정 assert 아님) ──
        for (Map.Entry<String, PortfolioBacktestRunner.PortfolioResult> e : results.entrySet()) {
            assertFalse(e.getValue().dailyReturns().isEmpty(), e.getKey() + ": 수익률 비어 있음");
            assertFalse(Double.isNaN(e.getValue().mdd()), e.getKey() + ": MDD NaN");
            assertEquals(e.getValue().dailyReturns().size(), e.getValue().dates().size(),
                    e.getKey() + ": 수익률-날짜 길이 불일치");
        }

        // ── 부트스트랩 MDD (스윙 예산 15%) ──
        Map<String, StationaryBootstrap.MddSummary> mddByLabel = new LinkedHashMap<>();
        for (Map.Entry<String, PortfolioBacktestRunner.PortfolioResult> e : results.entrySet()) {
            double[] arr = toArray(e.getValue().dailyReturns());
            StationaryBootstrap bootstrap = new StationaryBootstrap(arr, BLOCK_LENGTH, EXPERIMENT_RESAMPLES, SEED);
            mddByLabel.put(e.getKey(), bootstrap.mddDistribution(SWING_MDD_BUDGET));
        }

        // ── SPA_c — 벤치마크 KODEX200 B&H, 풀 = H 5종 전체(사전 선언 풀) ──
        Map<LocalDate, Double> benchmark = benchmarkDailyReturns(candlesBySymbol.get(BASELINE_SYMBOL));
        List<LocalDate> commonDates = intersectDates(benchmark, results);
        double[] benchArr = new double[commonDates.size()];
        for (int i = 0; i < commonDates.size(); i++) {
            benchArr[i] = benchmark.get(commonDates.get(i));
        }
        Map<String, double[]> candidates = new LinkedHashMap<>();
        for (Map.Entry<String, PortfolioBacktestRunner.PortfolioResult> e : results.entrySet()) {
            Map<LocalDate, Double> map = toDateReturnMap(e.getValue().dates(), e.getValue().dailyReturns());
            double[] arr = new double[commonDates.size()];
            for (int i = 0; i < commonDates.size(); i++) {
                arr[i] = map.get(commonDates.get(i));
            }
            candidates.put(e.getKey(), arr);
        }
        SpaTest.SpaResult spa = new SpaTest(benchArr, candidates, BLOCK_LENGTH, EXPERIMENT_RESAMPLES, SEED).run();
        assertFalse(Double.isNaN(spa.pValueSpaConsistent()), "SPA p-value NaN");

        // ── 리포트 ──
        System.out.println();
        System.out.println("=== 트랙 H — TA 차트 기법 5종 게이트 v2 판정 수치 (사전 선언 trial +5) ===");
        System.out.println("유니버스: 5종목 슬리브 / 비용: CostModel.defaults() / 창: train 252, test 63 / 스윕 없음");
        System.out.printf("SPA 교집합 구간: %s ~ %s (%d거래일), L=%d, B=%d, seed=%d, 스윙 MDD 예산=%.0f%%%n",
                commonDates.get(0), commonDates.get(commonDates.size() - 1), commonDates.size(),
                BLOCK_LENGTH, EXPERIMENT_RESAMPLES, SEED, SWING_MDD_BUDGET * 100);
        System.out.println();
        System.out.printf("%-30s | %9s | %7s | %8s | %7s | %6s | %9s | %9s | %12s%n",
                "계열", "OOS총수익", "CAGR", "실측MDD", "Sharpe", "거래수", "부트p95MDD", "P(>15%)", "게이트v2메모");
        System.out.println("-".repeat(125));
        for (Map.Entry<String, PortfolioBacktestRunner.PortfolioResult> e : results.entrySet()) {
            PortfolioBacktestRunner.PortfolioResult r = e.getValue();
            StationaryBootstrap.MddSummary mdd = mddByLabel.get(e.getKey());
            String memo = (r.totalReturn() > 0 ? "OOS+" : "OOS-")
                    + (mdd.p95() < SWING_MDD_BUDGET ? "/MDD OK" : "/MDD 초과");
            System.out.printf("%-30s | %8.2f%% | %6.2f%% | %7.2f%% | %7.2f | %6d | %8.2f%% | %8.1f%% | %s%n",
                    e.getKey(), r.totalReturn() * 100, r.cagr() * 100, r.mdd() * 100, r.sharpe(),
                    totalTrades(r), mdd.p95() * 100, mdd.probabilityExceedsBudget() * 100, memo);
        }
        System.out.println();
        System.out.printf("SPA_c p-value(최고 후보가 KODEX200 B&H를 이긴다는 증거) = %.4f  (p<0.05 필요)%n",
                spa.pValueSpaConsistent());
        for (SpaTest.CandidateStat cs : spa.candidateStats()) {
            System.out.printf("  %-30s | 평균초과수익(일) %10.5f%% | t-stat %8.3f%n",
                    cs.label(), cs.meanExcessReturn() * 100, cs.tStat());
        }
        System.out.println();
        System.out.println("[캐비엇] 판정 확정·기록은 PROGRESS §3에 사람이 남긴다. 결과를 본 후의 파라미터 수정은");
        System.out.println("새 trial 선언 없이는 금지(스누핑 방지 원칙). 봉 주기 변경(주봉/분봉 적용)도 각각 별도 trial이다.");
    }

    private int totalTrades(PortfolioBacktestRunner.PortfolioResult r) {
        int sum = 0;
        for (PortfolioBacktestRunner.SleeveResult sr : r.sleeveResults()) {
            sum += sr.walkForwardResult().oosResult().tradeCount();
        }
        return sum;
    }

    private double[] toArray(List<Double> list) {
        double[] arr = new double[list.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private Map<LocalDate, Double> toDateReturnMap(List<LocalDate> dates, List<Double> returns) {
        Map<LocalDate, Double> map = new LinkedHashMap<>();
        for (int i = 0; i < dates.size(); i++) {
            map.put(dates.get(i), returns.get(i));
        }
        return map;
    }

    private Map<LocalDate, Double> benchmarkDailyReturns(List<Candle> kodex) {
        Map<LocalDate, Double> map = new LinkedHashMap<>();
        for (int i = 1; i < kodex.size(); i++) {
            BigDecimal prev = kodex.get(i - 1).close();
            BigDecimal curr = kodex.get(i).close();
            double r = prev.signum() == 0 ? 0.0
                    : curr.subtract(prev).divide(prev, 12, RoundingMode.HALF_UP).doubleValue();
            map.put(kodex.get(i).date(), r);
        }
        return map;
    }

    private List<LocalDate> intersectDates(Map<LocalDate, Double> benchmark,
                                           Map<String, PortfolioBacktestRunner.PortfolioResult> results) {
        TreeSet<LocalDate> common = new TreeSet<>(benchmark.keySet());
        for (PortfolioBacktestRunner.PortfolioResult r : results.values()) {
            common.retainAll(new TreeSet<>(r.dates()));
        }
        return new ArrayList<>(common);
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
}
