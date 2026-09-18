package com.autostock.backtest;

import com.autostock.common.event.Candle;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 트랙 X — 횡단면 전략 게이트 v2 판정 (사전 선언 trial +2, docs/research/gate1_diagnosis_20260918.md 3절).
 *
 * <p>17계열(시계열 타이밍)이 전부 FAIL인 구조적 원인(표본 크기·강세장 노출 벌점·MDD 예산 긴장)을 피하는
 * 전략 종류로 횡단면 모멘텀·저변동성을 시험한다. 파라미터는 문헌 기본값 단일 고정, 스윕 없음.
 * 결과를 본 뒤의 수정은 새 trial 선언 없이는 금지.
 *
 * <p>데이터: {@code data/universe/prices/*.csv}(scripts/fetch_universe_prices.py — 야후, 수정주가) +
 * 벤치마크 {@code data/069500.csv}. 없으면 스킵(다른 Real* 테스트와 동일, data/는 git 미커밋).
 *
 * <p>유니버스: 형성 시점 직전 60거래일 평균 거래대금 상위 200(시가총액 대신 — point-in-time 보장, 엔진 Javadoc 참고).
 * 캘린더: 삼성전자(005930) 거래일. OOS: 2016-01-04 ~ 2026-08-31 단일 구간(walk-forward 아님 — 파라미터 선택이 없으므로
 * 훈련 구간이 필요 없다. D 계열과 동일 관례).
 *
 * <p>생존 편향 캐비엇: 야후에 시계열이 없는 상장폐지 종목은 유니버스에 들어오지 못한다(수집 실패분은
 * data/universe/.price_fail). 시계열이 도중에 끝나는 종목은 마지막 종가 청산으로 반영된다. 따라서 결과는 상한 추정치다.
 */
class RealDataCrossSectionalExperimentTest {

    private static final String CALENDAR_SYMBOL = "005930";
    private static final String BENCHMARK_SYMBOL = "069500";

    private static final LocalDate OOS_START = LocalDate.of(2016, 1, 4);
    private static final LocalDate OOS_END = LocalDate.of(2026, 8, 31);
    private static final int UNIVERSE_SIZE = 200;
    private static final int LIQUIDITY_WINDOW = 60;
    private static final BigDecimal CAPITAL = new BigDecimal("100000000");
    private static final int MIN_ROWS = 300;

    /** 중기 슬리브 MDD 예산(ADR-12). */
    private static final double MID_MDD_BUDGET = 0.25;
    /** DSR 병기용 연구 누적 시도 수: 기존 17계열 + X1·X2. */
    private static final int TRIALS = 19;

    private static final int EXPERIMENT_RESAMPLES = 1000;
    private static final int BLOCK_LENGTH = StationaryBootstrap.DEFAULT_MEAN_BLOCK_LENGTH;
    private static final long SEED = StationaryBootstrap.DEFAULT_SEED;

    @Test
    void 횡단면_X1_X2_게이트v2_판정수치() {
        Path dataDir = resolveDataDir();
        assumeTrue(dataDir != null, "data/ 디렉터리 없음 — 스킵");
        Path universeDir = dataDir.resolve("universe").resolve("prices");
        assumeTrue(Files.isDirectory(universeDir), "data/universe/prices 없음 — scripts/fetch_universe_prices.py 실행 후 재시도");
        assumeTrue(Files.isRegularFile(universeDir.resolve(CALENDAR_SYMBOL + ".csv")), "캘린더 종목 CSV 없음");
        assumeTrue(Files.isRegularFile(dataDir.resolve(BENCHMARK_SYMBOL + ".csv")), "벤치마크 069500.csv 없음");

        CandleCsvLoader loader = new CandleCsvLoader();
        List<Candle> calendarCandles = loader.load(universeDir.resolve(CALENDAR_SYMBOL + ".csv"));
        List<LocalDate> calendar = calendarCandles.stream().map(Candle::date).toList();
        List<Candle> benchmarkCandles = loader.load(dataDir.resolve(BENCHMARK_SYMBOL + ".csv"));

        Set<String> excluded = nonOperatingCompanies(dataDir.resolve("corp_map_all.csv"));
        CrossSectionalBacktestRunner.PriceTable table =
                CrossSectionalBacktestRunner.PriceTable.fromCsvDirectory(universeDir, calendar, MIN_ROWS, excluded);
        assumeTrue(table.symbolCount() >= 500, "유니버스 종목 수 부족(" + table.symbolCount() + ") — 수집 미완료");

        CrossSectionalBacktestRunner.Config config = new CrossSectionalBacktestRunner.Config(
                OOS_START, OOS_END, UNIVERSE_SIZE, LIQUIDITY_WINDOW, CAPITAL, CostModel.defaults(), TRIALS);

        // ── 사전 선언 2종 (스윕 없음) ──
        List<CrossSectionalStrategy> strategies = List.of(
                CrossSectionalStrategies.momentum12_1(20),
                CrossSectionalStrategies.lowVolatility(30));

        CrossSectionalBacktestRunner runner = new CrossSectionalBacktestRunner();
        Map<String, CrossSectionalBacktestRunner.Result> results = new LinkedHashMap<>();
        for (CrossSectionalStrategy s : strategies) {
            results.put(s.label(), runner.run(table, s, config));
        }

        printGateReport("트랙 X — 횡단면 전략 2종 게이트 v2 판정 수치 (사전 선언 trial +2)",
                "거래대금 상위 " + UNIVERSE_SIZE + " (60일 평균)", table, excluded, results, benchmarkCandles);
    }

    private static void printGateReport(String title, String universeDesc,
                                        CrossSectionalBacktestRunner.PriceTable table, Set<String> excluded,
                                        Map<String, CrossSectionalBacktestRunner.Result> results,
                                        List<Candle> benchmarkCandles) {
        // ── 무결성 확인(판정 assert 아님) ──
        for (Map.Entry<String, CrossSectionalBacktestRunner.Result> e : results.entrySet()) {
            assertFalse(e.getValue().dailyReturns().isEmpty(), e.getKey() + ": 수익률 비어 있음");
            assertFalse(Double.isNaN(e.getValue().result().mdd()), e.getKey() + ": MDD NaN");
        }

        // ── 부트스트랩 MDD (중기 예산 25%) ──
        Map<String, StationaryBootstrap.MddSummary> mddByLabel = new LinkedHashMap<>();
        for (Map.Entry<String, CrossSectionalBacktestRunner.Result> e : results.entrySet()) {
            StationaryBootstrap bootstrap = new StationaryBootstrap(
                    toArray(e.getValue().dailyReturns()), BLOCK_LENGTH, EXPERIMENT_RESAMPLES, SEED);
            mddByLabel.put(e.getKey(), bootstrap.mddDistribution(MID_MDD_BUDGET));
        }

        // ── SPA_c — 벤치마크 KODEX200 B&H, 풀 = X 2종(사전 선언 풀) ──
        Map<LocalDate, Double> benchmark = dailyReturns(benchmarkCandles);
        TreeSet<LocalDate> common = new TreeSet<>(benchmark.keySet());
        for (CrossSectionalBacktestRunner.Result r : results.values()) {
            common.retainAll(new TreeSet<>(r.dates()));
        }
        List<LocalDate> commonDates = new ArrayList<>(common);
        double[] benchArr = new double[commonDates.size()];
        for (int i = 0; i < commonDates.size(); i++) {
            benchArr[i] = benchmark.get(commonDates.get(i));
        }
        Map<String, double[]> candidates = new LinkedHashMap<>();
        for (Map.Entry<String, CrossSectionalBacktestRunner.Result> e : results.entrySet()) {
            Map<LocalDate, Double> map = new LinkedHashMap<>();
            List<LocalDate> d = e.getValue().dates();
            List<Double> r = e.getValue().dailyReturns();
            for (int i = 0; i < d.size(); i++) {
                map.put(d.get(i), r.get(i));
            }
            double[] arr = new double[commonDates.size()];
            for (int i = 0; i < commonDates.size(); i++) {
                arr[i] = map.get(commonDates.get(i));
            }
            candidates.put(e.getKey(), arr);
        }
        SpaTest.SpaResult spa = new SpaTest(benchArr, candidates, BLOCK_LENGTH, EXPERIMENT_RESAMPLES, SEED).run();
        assertFalse(Double.isNaN(spa.pValueSpaConsistent()), "SPA p-value NaN");

        // 벤치마크 동일 구간 총수익(기준선)
        double benchTotal = 1.0;
        for (double r : benchArr) {
            benchTotal *= 1 + r;
        }
        double benchMdd = new PerformanceCalculator().mdd(toList(benchArr));

        // ── 출력 (판정 확정·기록은 PROGRESS §3에 사람이 남긴다) ──
        System.out.println();
        System.out.println("=== " + title + " ===");
        System.out.printf("유니버스: %s / 로드 종목 %d (스팩·리츠·선박펀드 %d 제외) / 비용: CostModel.defaults() / 월말 형성·익일 시가 체결 / 스윕 없음%n",
                universeDesc, table.symbolCount(), excluded.size());
        System.out.printf("SPA 교집합 구간: %s ~ %s (%d거래일), L=%d, B=%d, seed=%d, 중기 MDD 예산=%.0f%%%n",
                commonDates.get(0), commonDates.get(commonDates.size() - 1), commonDates.size(),
                BLOCK_LENGTH, EXPERIMENT_RESAMPLES, SEED, MID_MDD_BUDGET * 100);
        System.out.printf("기준선 KODEX200 B&H(동일 구간): 총수익 %+.1f%%, MDD %.1f%%%n", (benchTotal - 1) * 100, benchMdd * 100);
        System.out.println();
        System.out.printf("%-26s | %9s | %7s | %8s | %7s | %6s | %6s | %7s | %6s | %9s | %9s | %7s%n",
                "계열", "OOS총수익", "CAGR", "실측MDD", "Sharpe", "리밸", "체결", "회전율", "폐지", "부트p95MDD", "P(>25%)", "DSR");
        System.out.println("-".repeat(140));
        for (Map.Entry<String, CrossSectionalBacktestRunner.Result> e : results.entrySet()) {
            CrossSectionalBacktestRunner.Result r = e.getValue();
            StationaryBootstrap.MddSummary mdd = mddByLabel.get(e.getKey());
            BacktestResult p = r.result();
            System.out.printf("%-26s | %8.1f%% | %6.2f%% | %7.2f%% | %7.2f | %6d | %6d | %6.1f%% | %6d | %8.2f%% | %8.1f%% | %7.3f%n",
                    e.getKey(), p.totalReturn() * 100, p.cagr() * 100, p.mdd() * 100, p.sharpe(),
                    r.rebalances(), r.trades(), r.avgTurnover() * 100, r.delistingsLiquidated(),
                    mdd.p95() * 100, mdd.probabilityExceedsBudget() * 100, p.dsrConfidence());
            System.out.printf("%-26s   평균 보유 %.1f종목, 평균 유니버스 %.0f종목%n", "", r.avgHoldings(), r.avgUniverseSize());
        }
        System.out.println();
        System.out.printf("SPA_c p-value(최고 후보가 KODEX200 B&H를 이긴다는 증거) = %.4f  (p<0.05 필요)%n", spa.pValueSpaConsistent());
        for (SpaTest.CandidateStat cs : spa.candidateStats()) {
            System.out.printf("  %-26s | 평균초과수익(일) %10.5f%% | t-stat %8.3f%n",
                    cs.label(), cs.meanExcessReturn() * 100, cs.tStat());
        }
        System.out.println();
        System.out.println("[캐비엇] 생존 편향(수집 실패 폐지 종목 미포함) → 상한 추정치. 결과를 본 후의 파라미터 수정은 새 trial 선언 없이는 금지.");
        System.out.println("[캐비엇] 게이트 ⓓ(슬리브 환산 총계좌 MDD<15%)는 통과 계열이 나온 뒤 배분 50/30/15/5로 별도 산출.");
    }

    /**
     * 유니버스 정의상 제외: 스팩·리츠(부동산투자회사)·선박투자회사·펀드·인프라펀드 — 사업회사가 아니라 횡단면
     * 이상현상(모멘텀·저변동성)의 대상이 아니며, 특히 스팩은 거래대금 상위에 올라올 수 있어 명시 제외한다.
     * (실측 2026-09-18: corp_map_all에 ETF·ETN·우선주는 없음 — DART 법인 목록이라 원래 빠져 있다.)
     * 사전 선언 규칙의 유니버스 정의에 속하며 실험 실행 전에 확정했다.
     */
    private static Set<String> nonOperatingCompanies(Path corpMap) {
        Set<String> out = new HashSet<>();
        if (!Files.isRegularFile(corpMap)) {
            return out;
        }
        Pattern p = Pattern.compile("(호스팩|스팩\\d*호|스팩$|부동산투자회사|리츠$|선박투자회사|펀드|맥쿼리인프라)");
        try (BufferedReader r = Files.newBufferedReader(corpMap)) {
            String line = r.readLine();
            while ((line = r.readLine()) != null) {
                String[] cols = line.split(",", -1);
                if (cols.length >= 3 && p.matcher(cols[2].trim()).find()) {
                    out.add(cols[0].trim());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /**
     * 트랙 X 2차 — X1b·X2b (사전 선언 trial +2, 2026-09-18 1차 결과 확인 후 선언).
     *
     * <p>1차(X1·X2) 결과: X1 -77.7%/MDD 87%, X2 +193%/MDD 50% — 둘 다 FAIL. 파이썬 독립 재현(scripts/x_diagnose.py)이
     * 엔진과 일치(-74.6%/85.5%, 비용 0)해 <b>엔진은 검증됨</b>. 원인은 유니버스 정의: "거래대금 상위 200"은 코스피200 근사가
     * 아니라 <b>개인 회전율 상위(테마주) 유니버스</b>였다 — 편입 종목이 신풍제약·씨젠·에코프로·금양·위메이드 등 테마
     * 정점 종목이었고 모멘텀은 정확히 그 정점을 산다. 이는 사전 선언 규칙이 "선언한 의도(대형·유동주)"를 표현하지 못한
     * 사양 결함이며, 수익률과 무관하게 종목 구성으로 확인된다. 따라서 유니버스 정의만 교정한 새 trial을 선언한다.
     * 전략 규칙·파라미터·비용·기간·판정은 1차와 동일.
     *
     * <p>교정 2건(실행 전 확정): ① 유니버스 = KRX 전종목 시세 월말 스냅샷의 실제 시가총액(point-in-time) 상위 200 —
     * 형성일 이하 최근 스냅샷 사용, 룩어헤드 없음(처음 계획한 "현재 상장주식수 × 수정종가" 근사는 키움 호출 제한과
     * 순증자 편향 때문에 폐기) ② 데이터 오류 가드 = 룩백 창 내 일간 |수익률|>40%인 종목 제외(KRX 제한폭 ±30% 초과는
     * 정상 거래에서 불가능).
     */
    @Test
    void 횡단면_X1b_X2b_시총유니버스_게이트v2_판정수치() {
        Path dataDir = resolveDataDir();
        assumeTrue(dataDir != null, "data/ 디렉터리 없음 — 스킵");
        Path universeDir = dataDir.resolve("universe").resolve("prices");
        Path capDir = dataDir.resolve("universe").resolve("marketcap");
        assumeTrue(Files.isDirectory(universeDir), "data/universe/prices 없음");
        assumeTrue(Files.isDirectory(capDir), "data/universe/marketcap 없음 — scripts/fetch_krx_marketcap.py 실행 후 재시도");
        assumeTrue(Files.isRegularFile(universeDir.resolve(CALENDAR_SYMBOL + ".csv")), "캘린더 종목 CSV 없음");
        assumeTrue(Files.isRegularFile(dataDir.resolve(BENCHMARK_SYMBOL + ".csv")), "벤치마크 069500.csv 없음");

        NavigableMap<LocalDate, Map<String, Double>> caps = marketCapSnapshots(capDir);
        assumeTrue(caps.size() >= 100, "시총 스냅샷 부족(" + caps.size() + "개월) — 수집 미완료");

        CandleCsvLoader loader = new CandleCsvLoader();
        List<LocalDate> calendar = loader.load(universeDir.resolve(CALENDAR_SYMBOL + ".csv")).stream().map(Candle::date).toList();
        List<Candle> benchmarkCandles = loader.load(dataDir.resolve(BENCHMARK_SYMBOL + ".csv"));
        Set<String> excluded = nonOperatingCompanies(dataDir.resolve("corp_map_all.csv"));
        CrossSectionalBacktestRunner.PriceTable table =
                CrossSectionalBacktestRunner.PriceTable.fromCsvDirectory(universeDir, calendar, MIN_ROWS, excluded);

        CrossSectionalBacktestRunner.Config config = new CrossSectionalBacktestRunner.Config(
                OOS_START, OOS_END, UNIVERSE_SIZE, LIQUIDITY_WINDOW, CAPITAL, CostModel.defaults(), TRIALS + 2,
                caps, 0.40);
        List<CrossSectionalStrategy> strategies = List.of(
                CrossSectionalStrategies.momentum12_1(20),
                CrossSectionalStrategies.lowVolatility(30));
        Map<String, CrossSectionalBacktestRunner.Result> results = new LinkedHashMap<>();
        CrossSectionalBacktestRunner runner = new CrossSectionalBacktestRunner();
        for (CrossSectionalStrategy s : strategies) {
            results.put(s.label().replace("X1", "X1b").replace("X2", "X2b"), runner.run(table, s, config));
        }
        printGateReport("트랙 X 2차 — KRX 시점별 시가총액 유니버스 + 데이터 오류 가드 (사전 선언 trial +2)",
                "KRX 월말 시가총액(point-in-time) 상위 " + UNIVERSE_SIZE + " / 스냅샷 " + caps.size() + "개월 / |일수익률|>40% 가드",
                table, excluded, results, benchmarkCandles);
    }

    /** data/universe/marketcap/{YYYYMMDD}.csv (scripts/fetch_krx_marketcap.py) — 날짜별 종목→시가총액(원). */
    private static NavigableMap<LocalDate, Map<String, Double>> marketCapSnapshots(Path dir) {
        NavigableMap<LocalDate, Map<String, Double>> out = new TreeMap<>();
        try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.csv")) {
            for (Path file : files) {
                String stem = file.getFileName().toString().replace(".csv", "");
                LocalDate d = LocalDate.parse(stem, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
                Map<String, Double> caps = new java.util.HashMap<>();
                try (BufferedReader r = Files.newBufferedReader(file)) {
                    String line = r.readLine();
                    while ((line = r.readLine()) != null) {
                        String[] cols = line.split(",", -1);
                        if (cols.length < 3 || cols[2].isBlank()) {
                            continue;
                        }
                        try {
                            caps.put(cols[0].trim(), Double.parseDouble(cols[2].trim()));
                        } catch (NumberFormatException ignored) {
                            // 비정상 행 — 제외
                        }
                    }
                }
                if (!caps.isEmpty()) {
                    out.put(d, caps);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static Map<LocalDate, Double> dailyReturns(List<Candle> candles) {
        Map<LocalDate, Double> map = new LinkedHashMap<>();
        for (int i = 1; i < candles.size(); i++) {
            BigDecimal prev = candles.get(i - 1).close();
            BigDecimal curr = candles.get(i).close();
            double r = prev.signum() == 0 ? 0.0
                    : curr.subtract(prev).divide(prev, 12, RoundingMode.HALF_UP).doubleValue();
            map.put(candles.get(i).date(), r);
        }
        return map;
    }

    private static double[] toArray(List<Double> list) {
        double[] arr = new double[list.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private static List<Double> toList(double[] arr) {
        List<Double> out = new ArrayList<>(arr.length);
        for (double v : arr) {
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
