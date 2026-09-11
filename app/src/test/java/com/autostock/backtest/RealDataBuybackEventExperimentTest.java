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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * G2(자사주 취득 이벤트 스윙, PLAN ADR-14) — 사전 선언 2변형(G2a/G2b) 실험 테스트.
 *
 * <h2>사전 선언 — 파라미터 스윕 금지</h2>
 * ADR-14는 G2를 "2순위 — 게이트 v2 백테스트 후보(직접취득/신탁 구분 필수)"로 못 박았다.
 * 이 테스트는 그 구분을 그대로 반영한 2개 변형만 고정해서 실행한다 — 보유기간(20거래일)이나
 * 비용모델을 흔들어보는 파라미터 탐색은 하지 않는다(다른 Real* 실험 테스트와 같은 관례).
 * <ul>
 *   <li>G2a — 원공시(amended=N) 전체(직접취득 BUYBACK_DIRECT + 신탁 BUYBACK_TRUST)</li>
 *   <li>G2b — 원공시 중 BUYBACK_DIRECT만(국내 실증 — 신탁은 신호가 약하다는 가설)</li>
 * </ul>
 *
 * <h2>G2d — 고정 10슬롯 자본 배분(2026-09-11 추가, trial +1)</h2>
 * PROGRESS.md 3절 G2 후속으로 사전 선언한 단일 구성. G2a(전체 원공시) 이벤트 풀·진입/청산 규칙을 그대로
 * 쓰되, 포트폴리오화만 다르다 — 슬리브 자본을 고정 10슬롯(각 1/10)으로 나누고, 신규 이벤트는 빈 슬롯이
 * 있을 때만 진입(선착순, entryDate 오름차순)한다. 10슬롯이 모두 찬 시점의 이벤트는 스킵한다(지연 진입
 * 없음). 일별 포트폴리오 수익률 = (그날 활성 슬롯들의 수익률 합) / 10 — 빈 슬롯은 현금 0%로 반영되므로
 * 기존 동일가중 평균(활성 수로 나눔 = 상시 100% 투입 가정)과 달리 자본 투입률이 현실적이다. 근거:
 * PLAN.md 8절(위험관리) "종목당 최대 계좌의 10%" 원칙을 스윙 슬리브 동시 보유 상한으로 준용한 것 —
 * 파라미터 탐색이 아니라 기존 리스크 명세의 직접 적용이다. 다른 슬롯 수·보유기간 변형은 시도하지 않는다.
 *
 * <h2>신호·체결 규칙(룩어헤드 방지)</h2>
 * 신호일 = 공시 접수일(rcept_dt). 진입 = 신호일 "다음 거래일"(해당 종목 자체 캔들 기준) 시가.
 * 청산 = 진입 후 20거래일째(진입일 포함 21번째 캔들) 종가. 이벤트별 왕복 비용은
 * {@link CostModel#defaults()}(매수 수수료+슬리피지, 매도 수수료+거래세 0.20%+슬리피지)를 그대로 적용한다.
 *
 * <h2>포트폴리오화 — 캘린더타임(Fama-French 방식)</h2>
 * 각 거래일의 포트폴리오 수익률은 그날 활성(보유 중)인 이벤트 포지션들의 일별 수익률을
 * 동일가중 평균한 값이다. 활성 이벤트가 없는 날은 0%(현금 대기)로 둔다 — 이는 전략 정의의
 * 일부이지 데이터 누락이 아니다(진입 신호가 없으면 현금을 들고 있는 것이 전략 그 자체).
 * 평가 구간은 2015-01~2026-09이며, 거래일 캘린더는 KODEX200(069500) 캔들을 기준으로 삼는다.
 *
 * <h2>캐비엇 — 생존 편향</h2>
 * data/events/prices/에는 수집 시점 상장사만 존재한다(상장폐지 종목 누락) — 실제 커버리지
 * 비율은 리포트 헤더에 항상 출력한다. 이 실험의 수익률은 "살아남은 종목만 대상으로 한
 * 자사주 공시 신호"의 성과이며, 상장폐지로 이어진 자사주 공시(대개 부정적 사건과 얽힘)는
 * 구조적으로 빠져 있어 실제보다 낙관적으로 나올 수 있다.
 *
 * <h2>캐비엇 — 이벤트 중복(동일 종목 연속 공시)</h2>
 * 같은 종목이 20거래일 이내에 자사주 공시를 연속으로 내면 두 이벤트의 보유 구간이 겹칠 수
 * 있다. 이 테스트는 이를 별도로 걸러내지 않는다 — 캘린더타임 포트폴리오화 자체가 "그날 활성인
 * 모든 포지션의 동일가중 평균"이므로 중복 활성 포지션은 자연스럽게 여러 개의 활성 슬롯으로
 * 반영된다(개별 종목 비중이 일시적으로 커질 뿐, 별도 처리 로직이 필요 없다).
 *
 * <p>성과 수치는 assert하지 않는다(계산 무결성만 확인) — 실제 게이트 판정은 사람이 콘솔 표를
 * 읽고 내린다({@link GateBootstrapSpaExperimentTest}와 같은 관례). 데이터가 없으면 조용히
 * 스킵한다(data/는 git에 커밋하지 않음).
 */
class RealDataBuybackEventExperimentTest {

    private static final int HOLDING_DAYS = 20;
    private static final LocalDate EVAL_START = LocalDate.of(2015, 1, 1);
    private static final LocalDate EVAL_END = LocalDate.of(2026, 9, 30);
    private static final String BENCHMARK_SYMBOL = "069500";

    /** 스윙 지평 게이트 v2 부트스트랩 MDD 예산(PLAN 게이트①: 스윙=15%). */
    private static final double SWING_MDD_BUDGET = 0.15;

    /** 실험 전용 부트스트랩/SPA 설정 — 과제 명세 고정값(GateBootstrapSpaExperimentTest와 동일 관례). */
    private static final int EXPERIMENT_MEAN_BLOCK_LENGTH = 21;
    private static final int EXPERIMENT_RESAMPLES = 1000;
    private static final long EXPERIMENT_SEED = 42L;

    private static final DateTimeFormatter RCEPT_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final CandleCsvLoader loader = new CandleCsvLoader();
    private final PerformanceCalculator performanceCalculator = new PerformanceCalculator();

    private record BuybackEvent(LocalDate rceptDt, String corpName, String stockCode, String type) {
    }

    private record EventSim(String stockCode, String corpName, LocalDate signalDate, LocalDate entryDate,
                             LocalDate exitDate, double eventReturn, Map<LocalDate, Double> dailyReturns) {
    }

    private record VariantOutcome(String label, List<EventSim> usedEvents, Map<String, Integer> excluded) {
    }

    @Test
    void G2_자사주취득이벤트스윙_사전선언2변형_캘린더타임_게이트v2() {
        Path dataDir = resolveDataDir();
        assumeTrue(dataDir != null,
                "data/ 디렉터리를 찾지 못해 G2 자사주 이벤트 실험을 스킵함(ADR-14) — data/events/를 채운 뒤 다시 실행할 것");

        Path eventsCsv = dataDir.resolve("events/buyback_events.csv");
        assumeTrue(Files.isRegularFile(eventsCsv), "data/events/buyback_events.csv 없음 — G2 실험 스킵: " + eventsCsv);

        Path pricesDir = dataDir.resolve("events/prices");
        assumeTrue(Files.isDirectory(pricesDir), "data/events/prices/ 없음 — G2 실험 스킵: " + pricesDir);

        Path benchmarkCsv = dataDir.resolve(BENCHMARK_SYMBOL + ".csv");
        assumeTrue(Files.isRegularFile(benchmarkCsv), "벤치마크 " + BENCHMARK_SYMBOL + ".csv 없음 — G2 실험 스킵: " + benchmarkCsv);

        List<BuybackEvent> originalEvents = loadOriginalEvents(eventsCsv);
        assumeTrue(!originalEvents.isEmpty(), "buyback_events.csv에 원공시(amended=N) 행이 없어 G2 실험을 스킵함");

        // ── 생존 편향 커버리지 계산 ──
        Set<String> uniqueCodes = new LinkedHashSet<>();
        for (BuybackEvent e : originalEvents) {
            uniqueCodes.add(e.stockCode());
        }
        int codesWithPrice = 0;
        for (String code : uniqueCodes) {
            if (Files.isRegularFile(pricesDir.resolve(code + ".csv"))) {
                codesWithPrice++;
            }
        }

        List<Candle> kodexCandles = loader.load(benchmarkCsv);
        Map<LocalDate, Double> benchmarkMap = buildBenchmarkDailyReturnMap(kodexCandles);
        List<LocalDate> calendar = new ArrayList<>();
        for (Candle c : kodexCandles) {
            LocalDate d = c.date();
            if (!d.isBefore(EVAL_START) && !d.isAfter(EVAL_END) && benchmarkMap.containsKey(d)) {
                calendar.add(d);
            }
        }
        assumeTrue(!calendar.isEmpty(), "평가구간(2015-01~2026-09) 내 KODEX200 거래일이 없어 G2 실험을 스킵함");

        CostModel cost = CostModel.defaults();
        Map<String, List<Candle>> candleCache = new HashMap<>();

        VariantOutcome g2a = runVariant("G2a.전체원공시(직접+신탁)", originalEvents, e -> true, pricesDir, candleCache);
        VariantOutcome g2b = runVariant("G2b.직접취득만(BUYBACK_DIRECT)", originalEvents,
                e -> "BUYBACK_DIRECT".equals(e.type()), pricesDir, candleCache);
        List<VariantOutcome> variants = List.of(g2a, g2b);

        for (VariantOutcome v : variants) {
            assertFalse(v.usedEvents().isEmpty(), v.label() + ": 사용 가능한 이벤트가 0건임");
        }

        // ── 캘린더타임 포트폴리오 일별수익률 + 동시 보유 이벤트 수 ──
        Map<String, List<Double>> dailyReturnsByVariant = new LinkedHashMap<>();
        Map<String, List<Integer>> concurrencyByVariant = new LinkedHashMap<>();
        Map<String, Map<LocalDate, Double>> dateReturnMapByVariant = new LinkedHashMap<>();
        for (VariantOutcome v : variants) {
            Map<LocalDate, List<Double>> activeByDate = new HashMap<>();
            for (LocalDate d : calendar) {
                activeByDate.put(d, new ArrayList<>());
            }
            for (EventSim ev : v.usedEvents()) {
                for (Map.Entry<LocalDate, Double> e : ev.dailyReturns().entrySet()) {
                    List<Double> bucket = activeByDate.get(e.getKey());
                    if (bucket != null) {
                        bucket.add(e.getValue());
                    }
                }
            }
            List<Double> dailyReturns = new ArrayList<>(calendar.size());
            List<Integer> concurrency = new ArrayList<>(calendar.size());
            Map<LocalDate, Double> dateReturnMap = new LinkedHashMap<>();
            for (LocalDate d : calendar) {
                List<Double> active = activeByDate.get(d);
                concurrency.add(active.size());
                double avg = 0.0;
                for (double r : active) {
                    avg += r;
                }
                avg = active.isEmpty() ? 0.0 : avg / active.size();
                dailyReturns.add(avg);
                dateReturnMap.put(d, avg);
            }
            dailyReturnsByVariant.put(v.label(), dailyReturns);
            concurrencyByVariant.put(v.label(), concurrency);
            dateReturnMapByVariant.put(v.label(), dateReturnMap);
        }

        // ── 표 2: 성과지표 + 부트스트랩 MDD 분포 ──
        Map<String, BacktestResult> perfByVariant = new LinkedHashMap<>();
        Map<String, StationaryBootstrap.MddSummary> bootByVariant = new LinkedHashMap<>();
        for (VariantOutcome v : variants) {
            List<Double> dailyReturns = dailyReturnsByVariant.get(v.label());
            double equity = 1.0;
            for (double r : dailyReturns) {
                equity *= (1 + r);
            }
            BacktestResult perf = performanceCalculator.calculate(
                    dailyReturns, BigDecimal.ONE, BigDecimal.valueOf(equity), v.usedEvents().size(), 1, 0.0);
            assertFalse(Double.isNaN(perf.mdd()), v.label() + ": mdd가 NaN");
            assertFalse(Double.isNaN(perf.totalReturn()), v.label() + ": totalReturn이 NaN");
            perfByVariant.put(v.label(), perf);

            double[] arr = toArray(dailyReturns);
            StationaryBootstrap bootstrap = new StationaryBootstrap(arr, EXPERIMENT_MEAN_BLOCK_LENGTH, EXPERIMENT_RESAMPLES, EXPERIMENT_SEED);
            StationaryBootstrap.MddSummary summary = bootstrap.mddDistribution(SWING_MDD_BUDGET);
            assertFalse(Double.isNaN(summary.median()), v.label() + ": 부트스트랩 MDD median이 NaN");
            assertFalse(Double.isNaN(summary.p95()), v.label() + ": 부트스트랩 MDD p95가 NaN");
            bootByVariant.put(v.label(), summary);
        }

        // ── 표 3: SPA — 벤치마크 KODEX200 B&H, 공통 날짜 교집합 ──
        List<Map<LocalDate, Double>> maps = new ArrayList<>();
        maps.add(benchmarkMap);
        for (VariantOutcome v : variants) {
            maps.add(dateReturnMapByVariant.get(v.label()));
        }
        List<LocalDate> commonDates = intersectDates(maps);
        assumeTrue(!commonDates.isEmpty(), "벤치마크·G2a·G2b 간 날짜 교집합이 비어 있어 SPA를 계산할 수 없음");

        double[] benchArr = new double[commonDates.size()];
        for (int i = 0; i < commonDates.size(); i++) {
            benchArr[i] = benchmarkMap.get(commonDates.get(i));
        }
        Map<String, double[]> candidateArrays = new LinkedHashMap<>();
        for (VariantOutcome v : variants) {
            Map<LocalDate, Double> m = dateReturnMapByVariant.get(v.label());
            double[] arr = new double[commonDates.size()];
            for (int i = 0; i < commonDates.size(); i++) {
                arr[i] = m.get(commonDates.get(i));
            }
            candidateArrays.put(v.label(), arr);
        }
        SpaTest spa = new SpaTest(benchArr, candidateArrays, EXPERIMENT_MEAN_BLOCK_LENGTH, EXPERIMENT_RESAMPLES, EXPERIMENT_SEED);
        SpaTest.SpaResult spaResult = spa.run();
        assertFalse(Double.isNaN(spaResult.pValueSpaConsistent()), "SPA p-value가 NaN");
        for (SpaTest.CandidateStat cs : spaResult.candidateStats()) {
            assertFalse(Double.isNaN(cs.tStat()), "SPA " + cs.label() + " t-stat이 NaN");
        }

        // ── G2d: 고정 10슬롯 자본 배분 (G2a 이벤트 풀 기반, 사전 선언 단일 구성) ──
        final int NUM_SLOTS = 10;
        List<EventSim> g2aChronological = new ArrayList<>(g2a.usedEvents());
        g2aChronological.sort(Comparator.comparing(EventSim::entryDate).thenComparing(EventSim::stockCode));

        LocalDate[] slotOccupiedUntil = new LocalDate[NUM_SLOTS];
        List<EventSim> g2dUsedEvents = new ArrayList<>();
        int g2dSkippedFull = 0;
        for (EventSim ev : g2aChronological) {
            int slotIdx = -1;
            for (int i = 0; i < NUM_SLOTS; i++) {
                if (slotOccupiedUntil[i] == null || slotOccupiedUntil[i].isBefore(ev.entryDate())) {
                    slotIdx = i;
                    break;
                }
            }
            if (slotIdx < 0) {
                g2dSkippedFull++;
                continue;
            }
            slotOccupiedUntil[slotIdx] = ev.exitDate();
            g2dUsedEvents.add(ev);
        }
        assertFalse(g2dUsedEvents.isEmpty(), "G2d: 사용 가능한 이벤트가 0건임");

        Map<LocalDate, List<Double>> g2dActiveByDate = new HashMap<>();
        for (LocalDate d : calendar) {
            g2dActiveByDate.put(d, new ArrayList<>());
        }
        for (EventSim ev : g2dUsedEvents) {
            for (Map.Entry<LocalDate, Double> e : ev.dailyReturns().entrySet()) {
                List<Double> bucket = g2dActiveByDate.get(e.getKey());
                if (bucket != null) {
                    bucket.add(e.getValue());
                }
            }
        }
        List<Double> g2dDailyReturns = new ArrayList<>(calendar.size());
        Map<LocalDate, Double> g2dDateReturnMap = new LinkedHashMap<>();
        double occupancySum = 0.0;
        for (LocalDate d : calendar) {
            List<Double> active = g2dActiveByDate.get(d);
            double sum = 0.0;
            for (double r : active) {
                sum += r;
            }
            double portfolioReturn = sum / NUM_SLOTS; // 고정 분모 — 빈 슬롯은 현금 0%
            g2dDailyReturns.add(portfolioReturn);
            g2dDateReturnMap.put(d, portfolioReturn);
            occupancySum += (double) active.size() / NUM_SLOTS;
        }
        double avgSlotOccupancyPct = calendar.isEmpty() ? 0.0 : 100.0 * occupancySum / calendar.size();

        double g2dEquity = 1.0;
        for (double r : g2dDailyReturns) {
            g2dEquity *= (1 + r);
        }
        BacktestResult g2dPerf = performanceCalculator.calculate(
                g2dDailyReturns, BigDecimal.ONE, BigDecimal.valueOf(g2dEquity), g2dUsedEvents.size(), 1, 0.0);
        assertFalse(Double.isNaN(g2dPerf.mdd()), "G2d: mdd가 NaN");
        assertFalse(Double.isNaN(g2dPerf.totalReturn()), "G2d: totalReturn이 NaN");

        double[] g2dArr = toArray(g2dDailyReturns);
        StationaryBootstrap g2dBootstrap = new StationaryBootstrap(g2dArr, EXPERIMENT_MEAN_BLOCK_LENGTH, EXPERIMENT_RESAMPLES, EXPERIMENT_SEED);
        StationaryBootstrap.MddSummary g2dBoot = g2dBootstrap.mddDistribution(SWING_MDD_BUDGET);
        assertFalse(Double.isNaN(g2dBoot.median()), "G2d: 부트스트랩 MDD median이 NaN");
        assertFalse(Double.isNaN(g2dBoot.p95()), "G2d: 부트스트랩 MDD p95가 NaN");

        // SPA — G2d 단독 후보
        List<LocalDate> g2dCommonDates = intersectDates(List.of(benchmarkMap, g2dDateReturnMap));
        assumeTrue(!g2dCommonDates.isEmpty(), "벤치마크·G2d 간 날짜 교집합이 비어 있어 SPA를 계산할 수 없음");
        double[] g2dSoloBenchArr = new double[g2dCommonDates.size()];
        double[] g2dSoloArr = new double[g2dCommonDates.size()];
        for (int i = 0; i < g2dCommonDates.size(); i++) {
            g2dSoloBenchArr[i] = benchmarkMap.get(g2dCommonDates.get(i));
            g2dSoloArr[i] = g2dDateReturnMap.get(g2dCommonDates.get(i));
        }
        Map<String, double[]> g2dSoloCandidates = new LinkedHashMap<>();
        g2dSoloCandidates.put("G2d.고정10슬롯", g2dSoloArr);
        SpaTest g2dSoloSpa = new SpaTest(g2dSoloBenchArr, g2dSoloCandidates, EXPERIMENT_MEAN_BLOCK_LENGTH, EXPERIMENT_RESAMPLES, EXPERIMENT_SEED);
        SpaTest.SpaResult g2dSoloSpaResult = g2dSoloSpa.run();
        assertFalse(Double.isNaN(g2dSoloSpaResult.pValueSpaConsistent()), "G2d 단독 SPA p-value가 NaN");

        // SPA — 참고: G2a·G2b·G2d 3후보 풀 (동일 교집합)
        List<Map<LocalDate, Double>> poolMaps = new ArrayList<>();
        poolMaps.add(benchmarkMap);
        poolMaps.add(dateReturnMapByVariant.get(g2a.label()));
        poolMaps.add(dateReturnMapByVariant.get(g2b.label()));
        poolMaps.add(g2dDateReturnMap);
        List<LocalDate> poolCommonDates = intersectDates(poolMaps);
        assumeTrue(!poolCommonDates.isEmpty(), "벤치마크·G2a·G2b·G2d 간 날짜 교집합이 비어 있어 참고 SPA를 계산할 수 없음");
        double[] poolBenchArr = new double[poolCommonDates.size()];
        for (int i = 0; i < poolCommonDates.size(); i++) {
            poolBenchArr[i] = benchmarkMap.get(poolCommonDates.get(i));
        }
        Map<String, double[]> poolCandidates = new LinkedHashMap<>();
        for (VariantOutcome v : variants) {
            Map<LocalDate, Double> m = dateReturnMapByVariant.get(v.label());
            double[] arr = new double[poolCommonDates.size()];
            for (int i = 0; i < poolCommonDates.size(); i++) {
                arr[i] = m.get(poolCommonDates.get(i));
            }
            poolCandidates.put(v.label(), arr);
        }
        double[] poolG2dArr = new double[poolCommonDates.size()];
        for (int i = 0; i < poolCommonDates.size(); i++) {
            poolG2dArr[i] = g2dDateReturnMap.get(poolCommonDates.get(i));
        }
        poolCandidates.put("G2d.고정10슬롯", poolG2dArr);
        SpaTest poolSpa = new SpaTest(poolBenchArr, poolCandidates, EXPERIMENT_MEAN_BLOCK_LENGTH, EXPERIMENT_RESAMPLES, EXPERIMENT_SEED);
        SpaTest.SpaResult poolSpaResult = poolSpa.run();
        assertFalse(Double.isNaN(poolSpaResult.pValueSpaConsistent()), "3후보 풀 참고 SPA p-value가 NaN");

        printReport(variants, uniqueCodes.size(), codesWithPrice, calendar, perfByVariant, bootByVariant,
                concurrencyByVariant, commonDates, spaResult);

        printG2dReport(g2aChronological.size(), g2dUsedEvents.size(), g2dSkippedFull, avgSlotOccupancyPct,
                g2dPerf, g2dBoot, g2dSoloSpaResult, g2dCommonDates.size(), poolSpaResult, poolCommonDates.size());
    }

    // ───────────────────────────── 변형 실행 ─────────────────────────────

    private VariantOutcome runVariant(String label, List<BuybackEvent> originalEvents, Predicate<BuybackEvent> typeFilter,
                                       Path pricesDir, Map<String, List<Candle>> candleCache) {
        List<EventSim> used = new ArrayList<>();
        Map<String, Integer> excluded = new LinkedHashMap<>();
        excluded.put("가격데이터없음", 0);
        excluded.put("신호일이후거래일없음", 0);
        excluded.put("보유기간데이터부족", 0);

        CostModel cost = CostModel.defaults();

        for (BuybackEvent e : originalEvents) {
            if (!typeFilter.test(e)) {
                continue;
            }
            List<Candle> candles = candleCache.computeIfAbsent(e.stockCode(), code -> {
                Path p = pricesDir.resolve(code + ".csv");
                return Files.isRegularFile(p) ? loader.load(p) : null;
            });
            if (candles == null || candles.isEmpty()) {
                excluded.merge("가격데이터없음", 1, Integer::sum);
                continue;
            }

            int entryIdx = findNextTradingDayIndex(candles, e.rceptDt());
            if (entryIdx < 0) {
                excluded.merge("신호일이후거래일없음", 1, Integer::sum);
                continue;
            }
            int exitIdx = entryIdx + HOLDING_DAYS;
            if (exitIdx >= candles.size()) {
                excluded.merge("보유기간데이터부족", 1, Integer::sum);
                continue;
            }

            used.add(simulateEvent(e, candles, entryIdx, exitIdx, cost));
        }

        return new VariantOutcome(label, used, excluded);
    }

    /** 신호일(rceptDt) 다음 거래일 인덱스(캔들 자체 날짜 기준) — 없으면 -1. 룩어헤드 방지(신호일 당일 체결 아님). */
    private int findNextTradingDayIndex(List<Candle> candles, LocalDate rceptDt) {
        int lo = 0;
        int hi = candles.size() - 1;
        int ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            LocalDate d = candles.get(mid).date();
            if (d.isAfter(rceptDt)) {
                ans = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return ans;
    }

    /**
     * 이벤트 하나를 시뮬레이션한다 — 진입(다음 거래일 시가, 비용 반영) → 청산(20거래일째 종가, 비용 반영).
     * 반환하는 dailyReturns는 진입일부터 청산일까지 "체인 곱하면 이벤트 전체 수익률과 정확히 일치"하도록
     * 구성한다(진입일=costBasis→그날 종가, 중간일=전일종가→당일종가, 청산일=전일종가→proceeds) — 캘린더타임
     * 포트폴리오화에 그대로 쓸 수 있게 하기 위함.
     */
    private EventSim simulateEvent(BuybackEvent e, List<Candle> candles, int entryIdx, int exitIdx, CostModel cost) {
        BigDecimal entryOpen = candles.get(entryIdx).open();
        BigDecimal buyPrice = cost.slippageAdjustedBuyPrice(entryOpen);
        BigDecimal buyFee = cost.buyFee(buyPrice);
        BigDecimal costBasis = buyPrice.add(buyFee);

        BigDecimal exitClose = candles.get(exitIdx).close();
        BigDecimal sellPrice = cost.slippageAdjustedSellPrice(exitClose);
        BigDecimal sellFee = cost.sellFee(sellPrice);
        BigDecimal proceeds = sellPrice.subtract(sellFee);

        double eventReturn = costBasis.signum() == 0 ? 0.0
                : proceeds.subtract(costBasis).divide(costBasis, 12, RoundingMode.HALF_UP).doubleValue();

        Map<LocalDate, Double> dailyReturns = new LinkedHashMap<>();

        BigDecimal entryClose = candles.get(entryIdx).close();
        double entryDayReturn = costBasis.signum() == 0 ? 0.0
                : entryClose.subtract(costBasis).divide(costBasis, 12, RoundingMode.HALF_UP).doubleValue();
        dailyReturns.put(candles.get(entryIdx).date(), entryDayReturn);

        for (int i = entryIdx + 1; i < exitIdx; i++) {
            BigDecimal prevClose = candles.get(i - 1).close();
            BigDecimal currClose = candles.get(i).close();
            double r = prevClose.signum() == 0 ? 0.0
                    : currClose.subtract(prevClose).divide(prevClose, 12, RoundingMode.HALF_UP).doubleValue();
            dailyReturns.put(candles.get(i).date(), r);
        }

        BigDecimal prevCloseBeforeExit = candles.get(exitIdx - 1).close();
        double exitDayReturn = prevCloseBeforeExit.signum() == 0 ? 0.0
                : proceeds.subtract(prevCloseBeforeExit).divide(prevCloseBeforeExit, 12, RoundingMode.HALF_UP).doubleValue();
        dailyReturns.put(candles.get(exitIdx).date(), exitDayReturn);

        return new EventSim(e.stockCode(), e.corpName(), e.rceptDt(), candles.get(entryIdx).date(),
                candles.get(exitIdx).date(), eventReturn, dailyReturns);
    }

    // ───────────────────────────── 데이터 로딩 ─────────────────────────────

    /** amended=N(원공시)만 로드 — 수정/정정 공시는 신호로 쓰지 않는다. */
    private List<BuybackEvent> loadOriginalEvents(Path csv) {
        List<BuybackEvent> events = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(csv)) {
            String line = reader.readLine(); // 헤더: rcept_dt,rcept_no,corp_name,stock_code,type,amended
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] cols = line.split(",", -1);
                String amended = cols[5].trim();
                if (!"N".equals(amended)) {
                    continue;
                }
                LocalDate rceptDt = LocalDate.parse(cols[0].trim(), RCEPT_FMT);
                String corpName = cols[2].trim();
                String stockCode = cols[3].trim();
                String type = cols[4].trim();
                events.add(new BuybackEvent(rceptDt, corpName, stockCode, type));
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("buyback_events.csv 로드 실패: " + csv, ex);
        }
        return events;
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

    private double[] toArray(List<Double> list) {
        double[] arr = new double[list.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = list.get(i);
        }
        return arr;
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

    private void printReport(List<VariantOutcome> variants, int uniqueCodes, int codesWithPrice,
                              List<LocalDate> calendar, Map<String, BacktestResult> perfByVariant,
                              Map<String, StationaryBootstrap.MddSummary> bootByVariant,
                              Map<String, List<Integer>> concurrencyByVariant,
                              List<LocalDate> commonDates, SpaTest.SpaResult spaResult) {
        double coveragePct = uniqueCodes == 0 ? 0.0 : 100.0 * codesWithPrice / uniqueCodes;

        System.out.println();
        System.out.println("=== G2. 자사주 취득 이벤트 스윙 — 사전 선언 2변형, 캘린더타임 포트폴리오, 게이트 v2 (ADR-14) ===");
        System.out.printf("[캐비엇] 생존 편향 — 신호 대상 종목 %d개 중 가격 이력 보유 %d개(커버리지 %.1f%%). "
                        + "상장폐지 종목은 data/events/prices/에서 구조적으로 누락돼 있어 실제보다 낙관적일 수 있음.%n",
                uniqueCodes, codesWithPrice, coveragePct);
        System.out.println("[캐비엇] 이벤트 중복(동일 종목 연속 공시) — 별도 필터 없음. 캘린더타임 동일가중 평균이 "
                + "중복 활성 포지션을 자연 처리함(해당 종목 비중이 일시적으로 커질 뿐).");
        System.out.printf("[평가 구간] %s ~ %s (KODEX200 기준 %d거래일) | 보유기간=%d거래일 | 비용모델=CostModel.defaults()%n",
                calendar.get(0), calendar.get(calendar.size() - 1), calendar.size(), HOLDING_DAYS);
        System.out.printf("[실험 설정] 부트스트랩/SPA: L=%d, B=%d, seed=%d | 스윙 지평 MDD 예산=%.0f%%%n",
                EXPERIMENT_MEAN_BLOCK_LENGTH, EXPERIMENT_RESAMPLES, EXPERIMENT_SEED, SWING_MDD_BUDGET * 100);

        // ── 표 1 ──
        System.out.println();
        System.out.println("--- [표 1] 변형별 이벤트 통계 ---");
        System.out.printf("%-30s | %6s | %8s | %8s | %8s | %14s | %8s | %10s%n",
                "변형", "사용", "제외(가격)", "제외(신호일)", "제외(보유)", "평균이벤트수익률", "승률", "평균동시보유");
        System.out.println("-".repeat(120));
        for (VariantOutcome v : variants) {
            List<EventSim> used = v.usedEvents();
            int wins = 0;
            double sumReturn = 0.0;
            for (EventSim ev : used) {
                sumReturn += ev.eventReturn();
                if (ev.eventReturn() > 0) {
                    wins++;
                }
            }
            double avgReturn = used.isEmpty() ? 0.0 : sumReturn / used.size();
            double winRate = used.isEmpty() ? 0.0 : 100.0 * wins / used.size();
            List<Integer> concurrency = concurrencyByVariant.get(v.label());
            double avgConcurrency = 0.0;
            for (int c : concurrency) {
                avgConcurrency += c;
            }
            avgConcurrency = concurrency.isEmpty() ? 0.0 : avgConcurrency / concurrency.size();

            System.out.printf("%-30s | %6d | %8d | %8d | %8d | %13.2f%% | %7.1f%% | %10.2f%n",
                    v.label(), used.size(),
                    v.excluded().getOrDefault("가격데이터없음", 0),
                    v.excluded().getOrDefault("신호일이후거래일없음", 0),
                    v.excluded().getOrDefault("보유기간데이터부족", 0),
                    avgReturn * 100, winRate, avgConcurrency);
        }

        // ── 표 2 ──
        System.out.println();
        System.out.println("--- [표 2] 변형별 캘린더타임 성과 + 부트스트랩 MDD 분포 ---");
        System.out.printf("%-30s | %9s | %9s | %9s | %9s | %10s | %9s | %12s%n",
                "변형", "총수익률", "CAGR", "MDD(점)", "Sharpe(연)", "부트median", "부트p95", "P(MDD>15%)");
        System.out.println("-".repeat(120));
        for (VariantOutcome v : variants) {
            BacktestResult perf = perfByVariant.get(v.label());
            StationaryBootstrap.MddSummary boot = bootByVariant.get(v.label());
            System.out.printf("%-30s | %8.2f%% | %8.2f%% | %8.2f%% | %9.3f | %9.2f%% | %8.2f%% | %11.1f%%%n",
                    v.label(), perf.totalReturn() * 100, perf.cagr() * 100, perf.mdd() * 100, perf.sharpe(),
                    boot.median() * 100, boot.p95() * 100, boot.probabilityExceedsBudget() * 100);
        }

        // ── 표 3 ──
        System.out.println();
        System.out.println("--- [표 3] SPA_c 검정 — 벤치마크=KODEX200 buy&hold(공통 날짜 교집합) ---");
        System.out.println("[참고] 활성 이벤트가 없는 날의 0% 수익은 '현금 대기'로 정당하다 — 전략 정의의 일부이지 데이터 결측이 아님.");
        System.out.printf("날짜 교집합 구간: %s ~ %s (%d거래일)%n",
                commonDates.get(0), commonDates.get(commonDates.size() - 1), commonDates.size());
        System.out.printf("SPA_c p-value(최고 후보가 벤치마크를 이긴다는 증거) = %.4f  (검정통계량 T_n=%.4f)%n",
                spaResult.pValueSpaConsistent(), spaResult.testStatistic());
        System.out.printf("%-30s | %14s | %10s%n", "후보", "평균초과수익(일)", "t-stat");
        System.out.println("-".repeat(60));
        for (SpaTest.CandidateStat cs : spaResult.candidateStats()) {
            System.out.printf("%-30s | %13.5f%% | %10.3f%n", cs.label(), cs.meanExcessReturn() * 100, cs.tStat());
        }

        // ── 게이트 v2 판정 ──
        System.out.println();
        System.out.println("--- [게이트 v2 판정] 스윙 지평 — 부트스트랩 p95 MDD < 15% 예산 ---");
        for (VariantOutcome v : variants) {
            StationaryBootstrap.MddSummary boot = bootByVariant.get(v.label());
            boolean pass = boot.p95() < SWING_MDD_BUDGET;
            System.out.printf("%-30s : %s (부트p95 MDD=%.2f%%, 예산=%.0f%%)%n",
                    v.label(), pass ? "PASS" : "FAIL", boot.p95() * 100, SWING_MDD_BUDGET * 100);
        }
        System.out.println("[유의] 위 판정은 명세된 p95 MDD 기준 하나만 본다 — PLAN 게이트①의 전체 기준(OOS 수익>0, "
                + "SPA_c p<0.05, 슬리브 환산 총계좌 MDD<15%, DSR·PBO 병기)은 표 1~3을 사람이 종합해 판단해야 한다.");
    }

    /**
     * G2d(고정 10슬롯 자본 배분) 전용 리포트 — 표 1~3의 G2a/G2b 출력은 건드리지 않고 별도 블록으로 추가한다.
     * 사전 선언: PROGRESS.md 3절 G2 후속, trial +1 (연구 전체 12번째 계열).
     */
    private void printG2dReport(int g2aEventPoolSize, int usedCount, int skippedFullCount, double avgSlotOccupancyPct,
                                 BacktestResult perf, StationaryBootstrap.MddSummary boot,
                                 SpaTest.SpaResult soloSpaResult, int soloCommonDays,
                                 SpaTest.SpaResult poolSpaResult, int poolCommonDays) {
        final double SLEEVE_ALLOCATION = 0.15; // PLAN.md 게이트①(v2): 스윙 슬리브 배분 15%

        System.out.println();
        System.out.println("=== G2d. 자사주 취득 이벤트 스윙 — 고정 10슬롯 자본 배분 (사전 선언 단일 구성, trial +1) ===");
        System.out.println("[근거] G2a(전체 원공시) 이벤트·진입/청산 규칙 그대로 + PLAN.md 8절 \"종목당 최대 계좌의 10%\" "
                + "원칙을 스윙 슬리브 동시 보유 상한(10슬롯)으로 준용. 파라미터 탐색 아님 — 단일 구성만 실행.");
        System.out.printf("[캐비엇] 생존 편향은 G2a와 동일 — data/events/prices/ 커버리지 한계 상속(표 1 헤더 참조). "
                + "이벤트 중복(동일 종목 연속 공시)도 G2a와 동일하게 별도 필터 없이 슬롯 점유로 자연 처리됨.%n");

        System.out.println();
        System.out.println("--- [표 4] G2d 슬롯 사용 통계 + 캘린더타임 성과 ---");
        System.out.printf("G2a 이벤트 풀(원공시 전체) = %d건 | 사용(슬롯 배정) = %d건 | 스킵(10슬롯 만석) = %d건 | "
                        + "평균 슬롯 점유율 = %.1f%%%n",
                g2aEventPoolSize, usedCount, skippedFullCount, avgSlotOccupancyPct);
        System.out.printf("%-20s | %9s | %9s | %9s | %10s%n", "변형", "총수익률", "CAGR", "MDD(점)", "Sharpe(연)");
        System.out.println("-".repeat(70));
        System.out.printf("%-20s | %8.2f%% | %8.2f%% | %8.2f%% | %9.3f%n",
                "G2d.고정10슬롯", perf.totalReturn() * 100, perf.cagr() * 100, perf.mdd() * 100, perf.sharpe());

        System.out.println();
        System.out.println("--- [표 5] G2d 부트스트랩 MDD 분포 (L=21, B=1000, seed=42) ---");
        System.out.printf("median = %.2f%% | p95 = %.2f%% | P(MDD>15%%) = %.1f%%%n",
                boot.median() * 100, boot.p95() * 100, boot.probabilityExceedsBudget() * 100);

        System.out.println();
        System.out.println("--- [표 6] SPA_c 검정 — 벤치마크=KODEX200 buy&hold ---");
        System.out.printf("G2d 단독 후보 (공통 %d거래일): SPA_c p-value = %.4f (T_n=%.4f)%n",
                soloCommonDays, soloSpaResult.pValueSpaConsistent(), soloSpaResult.testStatistic());
        for (SpaTest.CandidateStat cs : soloSpaResult.candidateStats()) {
            System.out.printf("  %-20s | 평균초과수익(일) %8.5f%% | t-stat %8.3f%n",
                    cs.label(), cs.meanExcessReturn() * 100, cs.tStat());
        }
        System.out.printf("[참고] G2a·G2b·G2d 3후보 풀 (공통 %d거래일): SPA_c p-value = %.4f (T_n=%.4f)%n",
                poolCommonDays, poolSpaResult.pValueSpaConsistent(), poolSpaResult.testStatistic());
        for (SpaTest.CandidateStat cs : poolSpaResult.candidateStats()) {
            System.out.printf("  %-20s | 평균초과수익(일) %8.5f%% | t-stat %8.3f%n",
                    cs.label(), cs.meanExcessReturn() * 100, cs.tStat());
        }

        System.out.println();
        System.out.println("--- [게이트 v2 판정] 스윙 지평 — 부트스트랩 p95 MDD < 15% 예산 ---");
        boolean gatePass = boot.p95() < SWING_MDD_BUDGET;
        System.out.printf("G2d.고정10슬롯 : %s (부트p95 MDD=%.2f%%, 예산=%.0f%%)%n",
                gatePass ? "PASS" : "FAIL", boot.p95() * 100, SWING_MDD_BUDGET * 100);
        double contributionMdd = boot.p95() * SLEEVE_ALLOCATION;
        System.out.printf("[참고] 총계좌 환산 — 스윙 슬리브 배분 %.0f%% 가정 시 부트p95 MDD 기여분(선형 근사) = %.2f%%"
                        + " (다른 슬리브와 상관 0 가정 — 실제로는 동시 하락 시 과소추정 가능, 상한 아님)%n",
                SLEEVE_ALLOCATION * 100, contributionMdd * 100);
        System.out.println("[유의] 위 판정은 명세된 p95 MDD 기준 하나만 본다 — 생존 편향은 G2a와 동일하게 상속되며, "
                + "이 실험(G2d)은 연구 누적 trial 카운트에 +1이다(기존 11계열 → 12계열, 전부 게이트① 미통과 계열에 합류 여부는 이 결과로 판정).");
    }
}
