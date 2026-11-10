package com.autostock.backtest;

import com.autostock.common.event.Candle;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 글로벌 이벤트 필터 검증 실험 — aiDoc/global-event-filter-plan.md 3장의 고정 규칙·채택 조건을 그대로 잰다.
 *
 * <ul>
 *   <li><b>E1</b>: 미국 예정 발표(FOMC·CPI·고용)가 있는 밤(한국 종가 → 다음 거래일 시가)의 갭 수익을 그 밖의 밤과 비교한다.
 *       채택 조건 ① 분산이 크다(정상 부트스트랩 p&lt;0.05) ② 평균이 크지 않다 ③ 사건 밤을 건너뛴 대리 계열의 부트스트랩 p95 MDD가 낮다.</li>
 *   <li><b>E2</b>: 미국 10년물 5거래일 변화 또는 광의 달러 지수 5거래일 변화율이 직전 252개 분포의 95백분위를 넘으면 신규 매수 금지.
 *       C3(동결 그대로)에 OFF/ON으로 붙여 walk-forward로 비교한다. 채택 조건 ① p95 MDD 감소 ② 수익 감소 &lt; MDD 감소 ③ 차단일 ≤15%.</li>
 * </ul>
 *
 * <p>시점 정렬(미래 정보 방지): 미국 날짜 t의 금리 값은 t보다 뒤의 첫 한국 거래일부터 쓴다. 달러 지수(H.10)는 주 1회
 * 월요일(미국) 공표이므로 t 다음 월요일보다 뒤의 첫 한국 거래일부터 쓴다.
 *
 * <p>판정 assert는 하지 않는다(다른 실험 테스트와 같은 관례). 표를 출력하고 사람이 계획 문서 6장에 옮긴다.
 * 데이터(data/069500.csv 등, data/macro_events.csv, data/fred_*.csv)가 없으면 스킵 —
 * {@code py -3.13 scripts/fetch_macro_event_data.py}로 만든다.
 */
class GlobalEventFilterExperimentTest {

    private static final LocalDate START = LocalDate.of(2015, 1, 1);
    private static final int E1_RESAMPLES = 10_000;
    private static final int MDD_RESAMPLES = 2_000;
    private static final int BLOCK = StationaryBootstrap.DEFAULT_MEAN_BLOCK_LENGTH;
    private static final long SEED = StationaryBootstrap.DEFAULT_SEED;
    private static final int CHANGE_DAYS = 5;
    private static final int PERCENTILE_WINDOW = 252;
    private static final double PERCENTILE = 0.95;

    /** C3 재현 — GateBootstrapSpaExperimentTest와 같은 유니버스·파라미터(재튜닝 없음). */
    private static final List<String> FAMILY = List.of("005930", "000660", "035420", "035720", "069500");
    private static final List<Integer> N_CANDIDATES = List.of(60, 120, 200);
    private static final double C_TARGET_VOL = 0.20;
    private static final int TRAIN = 252;
    private static final int TEST = 63;
    private static final int WARMUP = 200 + 21;
    private static final BigDecimal SLEEVE_CAPITAL = new BigDecimal("2000000");
    /** 대리 계열의 비용 참고값(ETF 왕복: 수수료 0.015%×2 + 슬리피지 0.05%×2). 주 판정은 비용 전(gross). */
    private static final double ETF_ROUND_TRIP = 0.00015 * 2 + 0.0005 * 2;

    private final CandleCsvLoader loader = new CandleCsvLoader();
    private final PerformanceCalculator calc = new PerformanceCalculator();

    @Test
    void 글로벌이벤트_E1_E2_고정규칙_측정() throws IOException {
        Path dir = resolveDataDir();
        assumeTrue(dir != null, "data/ 없음 — 실험 스킵");
        for (String f : List.of("macro_events.csv", "fred_DGS10.csv", "fred_DTWEXBGS.csv", "069500.csv")) {
            assumeTrue(Files.isRegularFile(dir.resolve(f)), f + " 없음 — 실험 스킵");
        }
        Map<String, List<Candle>> candles = new LinkedHashMap<>();
        for (String code : FAMILY) {
            assumeTrue(Files.isRegularFile(dir.resolve(code + ".csv")), code + ".csv 없음 — 실험 스킵");
            candles.put(code, loader.load(dir.resolve(code + ".csv")));
        }
        Map<String, Set<LocalDate>> events = loadEvents(dir.resolve("macro_events.csv"));
        Set<LocalDate> allEvents = new TreeSet<>();
        events.values().forEach(allEvents::addAll);

        System.out.println("=== 글로벌 이벤트 필터 실험 (계획 3장 고정 규칙) ===");
        List<Candle> kodex = candles.get("069500");
        System.out.printf("기간: %s ~ %s (069500 일봉 기준)%n", START, kodex.get(kodex.size() - 1).date());

        // ── E1 ──
        System.out.println();
        System.out.println("--- E1 사건 밤(FOMC·CPI·고용) vs 그 밖의 밤: 종가→다음 거래일 시가 갭 ---");
        System.out.println("종목 | 사건 밤 수 | 그 밖 수 | 평균(사건) | 평균(그 밖) | 표준편차(사건) | 표준편차(그 밖) | 분산비 | p(분산비≤1) | p(평균차≤0) | p95MDD OFF | p95MDD ON | ①②③");
        for (String code : FAMILY) {
            Night n = nights(candles.get(code), allEvents);
            e1Row(code, n);
        }
        System.out.println();
        System.out.println("--- E1 종류별(069500) ---");
        for (String kind : List.of("FOMC", "CPI", "NFP")) {
            e1Row("069500/" + kind, nights(kodex, events.getOrDefault(kind, Set.of())));
        }
        Night kn = nights(kodex, allEvents);
        double[] offCost = new double[kn.gaps.length];
        double[] onCost = new double[kn.gaps.length];
        for (int i = 0; i < kn.gaps.length; i++) {
            offCost[i] = kn.gaps[i] - ETF_ROUND_TRIP;
            onCost[i] = kn.event[i] ? 0.0 : kn.gaps[i] - ETF_ROUND_TRIP;
        }
        System.out.printf("참고(069500, 비용 포함): 누적 OFF %.1f%% / ON %.1f%%, 점 MDD OFF %.1f%% / ON %.1f%%%n",
                100 * total(offCost), 100 * total(onCost), 100 * calc.mdd(toList(offCost)), 100 * calc.mdd(toList(onCost)));

        // ── E2 ──
        NavigableMap<LocalDate, Boolean> rateTrig = triggers(loadSeries(dir.resolve("fred_DGS10.csv")), false);
        NavigableMap<LocalDate, Boolean> dollarTrig = triggers(loadSeries(dir.resolve("fred_DTWEXBGS.csv")), true);
        List<LocalDate> krDates = kodex.stream().map(Candle::date).toList();
        Map<LocalDate, Boolean> blocked = blockedByKrDate(krDates, rateTrig, dollarTrig);

        long total = krDates.stream().filter(d -> !d.isBefore(START) && blocked.containsKey(d)).count();
        long blockedDays = krDates.stream().filter(d -> !d.isBefore(START) && blocked.getOrDefault(d, false)).count();
        long rateOnly = krDates.stream().filter(d -> !d.isBefore(START)
                && Boolean.TRUE.equals(asOf(rateTrig, d, false))).count();

        Map<LocalDate, Boolean> regime = MarketRegime.compute(kodex);
        Function<Integer, BacktestStrategy> c3 = n -> new VolatilityTargetingStrategy(
                new RegimeFilteredStrategy(new TimeSeriesMomentumStrategy(n), regime), C_TARGET_VOL);
        Function<Integer, BacktestStrategy> c3e2 = n -> new BuyBlockStrategy(c3.apply(n), blocked);
        PortfolioBacktestRunner.PortfolioResult off = runC3(candles, c3);
        PortfolioBacktestRunner.PortfolioResult on = runC3(candles, c3e2);
        assertFalse(off.dailyReturns().isEmpty());
        assertFalse(on.dailyReturns().isEmpty());

        double p95Off = new StationaryBootstrap(toArray(off.dailyReturns()), BLOCK, MDD_RESAMPLES, SEED).mddDistribution().p95();
        double p95On = new StationaryBootstrap(toArray(on.dailyReturns()), BLOCK, MDD_RESAMPLES, SEED).mddDistribution().p95();
        LocalDate oosStart = off.dates().get(0);
        long oosDays = off.dates().size();
        long oosBlocked = off.dates().stream().filter(d -> blocked.getOrDefault(d, false)).count();

        System.out.println();
        System.out.println("--- E2 미국 금리·달러 급변 → 신규 매수 금지 (C3 walk-forward, 5종목) ---");
        System.out.printf("차단일(069500 거래일, %s~): %d / %d = %.1f%% (금리 조건만 %.1f%%)%n",
                START, blockedDays, total, 100.0 * blockedDays / total, 100.0 * rateOnly / total);
        System.out.printf("OOS %s~%s(%d일) 중 차단일 %.1f%%%n", oosStart, off.dates().get(off.dates().size() - 1),
                oosDays, 100.0 * oosBlocked / oosDays);
        System.out.println("계열 | 누적 수익 | CAGR | 점 MDD | p95 MDD(부트스트랩) | Sharpe");
        System.out.printf("C3 OFF | %.1f%% | %.2f%% | %.1f%% | %.1f%% | %.2f%n", 100 * off.totalReturn(), 100 * off.cagr(),
                100 * off.mdd(), 100 * p95Off, off.sharpe());
        System.out.printf("C3 ON(E2) | %.1f%% | %.2f%% | %.1f%% | %.1f%% | %.2f%n", 100 * on.totalReturn(), 100 * on.cagr(),
                100 * on.mdd(), 100 * p95On, on.sharpe());
        double returnDrop = off.totalReturn() - on.totalReturn();
        double mddDrop = off.mdd() - on.mdd();
        boolean c1 = p95On < p95Off;
        boolean c2 = returnDrop < mddDrop;
        boolean c3ok = (double) oosBlocked / oosDays <= 0.15;
        System.out.printf("판정: ① p95 MDD 감소 %s(%.1f%%p) ② 수익 감소 %.1f%%p < MDD 감소 %.1f%%p %s ③ 차단일 ≤15%% %s → walk-forward 기준 %s (CPCV 러너 없음 — 미실행)%n",
                ok(c1), 100 * (p95Off - p95On), 100 * returnDrop, 100 * mddDrop, ok(c2), ok(c3ok),
                c1 && c2 && c3ok ? "조건 충족" : "불채택");

        // 대리 계열(069500 밤사이 갭)에 E2 적용 — 참고
        double[] gOff = new double[kn.gaps.length];
        double[] gOn = new double[kn.gaps.length];
        for (int i = 0; i < kn.gaps.length; i++) {
            gOff[i] = kn.gaps[i];
            gOn[i] = blocked.getOrDefault(kn.dates[i], false) ? 0.0 : kn.gaps[i];
        }
        System.out.printf("참고(069500 밤사이 갭, 비용 전): 누적 OFF %.1f%% / ON %.1f%%, p95 MDD OFF %.1f%% / ON %.1f%%%n",
                100 * total(gOff), 100 * total(gOn),
                100 * new StationaryBootstrap(gOff, BLOCK, MDD_RESAMPLES, SEED).mddDistribution().p95(),
                100 * new StationaryBootstrap(gOn, BLOCK, MDD_RESAMPLES, SEED).mddDistribution().p95());
    }

    // ───────────────────────────── E1 ─────────────────────────────

    /** 밤 i = 거래일 i 종가 → 거래일 i+1 시가. 미국 날짜 d의 발표가 이 밤에 들면 date_i ≤ d < date_{i+1}. */
    private record Night(LocalDate[] dates, double[] gaps, boolean[] event) {
    }

    private Night nights(List<Candle> cs, Set<LocalDate> eventDates) {
        TreeSet<LocalDate> ev = new TreeSet<>(eventDates);
        List<LocalDate> ds = new ArrayList<>();
        List<Double> gs = new ArrayList<>();
        List<Boolean> es = new ArrayList<>();
        for (int i = 0; i + 1 < cs.size(); i++) {
            Candle a = cs.get(i);
            Candle b = cs.get(i + 1);
            if (a.date().isBefore(START)) {
                continue;
            }
            LocalDate next = ev.ceiling(a.date());
            ds.add(a.date());
            gs.add(b.open().doubleValue() / a.close().doubleValue() - 1.0);
            es.add(next != null && next.isBefore(b.date()));
        }
        boolean[] e = new boolean[es.size()];
        for (int i = 0; i < e.length; i++) {
            e[i] = es.get(i);
        }
        return new Night(ds.toArray(LocalDate[]::new), toArray(gs), e);
    }

    private void e1Row(String label, Night n) {
        Stats s = stats(n.gaps, n.event, null);
        int[][] paths = StationaryBootstrap.generateIndexPaths(n.gaps.length, BLOCK, E1_RESAMPLES, SEED);
        int varLe1 = 0;
        int meanLe0 = 0;
        int valid = 0;
        for (int[] path : paths) {
            Stats b = stats(n.gaps, n.event, path);
            if (b.nEvent < 2 || b.nOther < 2) {
                continue;
            }
            valid++;
            if (b.varEvent / b.varOther <= 1.0) {
                varLe1++;
            }
            if (b.meanEvent - b.meanOther <= 0.0) {
                meanLe0++;
            }
        }
        double pVar = varLe1 / (double) valid;
        double pMean = meanLe0 / (double) valid;
        double[] off = n.gaps.clone();
        double[] on = new double[off.length];
        for (int i = 0; i < off.length; i++) {
            on[i] = n.event[i] ? 0.0 : off[i];
        }
        double p95Off = new StationaryBootstrap(off, BLOCK, MDD_RESAMPLES, SEED).mddDistribution().p95();
        double p95On = new StationaryBootstrap(on, BLOCK, MDD_RESAMPLES, SEED).mddDistribution().p95();
        boolean c1 = pVar < 0.05;
        boolean c2 = s.meanEvent <= s.meanOther;
        boolean c3 = p95On < p95Off;
        System.out.printf("%s | %d | %d | %.3f%% | %.3f%% | %.3f%% | %.3f%% | %.2f | %.4f | %.4f | %.1f%% | %.1f%% | %s%s%s → %s%n",
                label, s.nEvent, s.nOther, 100 * s.meanEvent, 100 * s.meanOther, 100 * Math.sqrt(s.varEvent),
                100 * Math.sqrt(s.varOther), s.varEvent / s.varOther, pVar, pMean, 100 * p95Off, 100 * p95On,
                ok(c1), ok(c2), ok(c3), c1 && c2 && c3 ? "조건 충족" : "불채택");
    }

    private record Stats(int nEvent, int nOther, double meanEvent, double meanOther, double varEvent, double varOther) {
    }

    /** path가 null이면 원표본, 아니면 정상 부트스트랩 경로(갭과 사건 표시를 같은 인덱스로 함께 뽑는다). */
    private Stats stats(double[] g, boolean[] e, int[] path) {
        int len = path == null ? g.length : path.length;
        double se = 0, so = 0, qe = 0, qo = 0;
        int ne = 0, no = 0;
        for (int k = 0; k < len; k++) {
            int i = path == null ? k : path[k];
            if (e[i]) {
                ne++;
                se += g[i];
                qe += g[i] * g[i];
            } else {
                no++;
                so += g[i];
                qo += g[i] * g[i];
            }
        }
        double me = ne == 0 ? 0 : se / ne;
        double mo = no == 0 ? 0 : so / no;
        double ve = ne < 2 ? Double.NaN : (qe - ne * me * me) / (ne - 1);
        double vo = no < 2 ? Double.NaN : (qo - no * mo * mo) / (no - 1);
        return new Stats(ne, no, me, mo, ve, vo);
    }

    // ───────────────────────────── E2 ─────────────────────────────

    /**
     * 관측일별 급변 여부. 변화 = 5관측 전 대비(금리는 차이 %p, 달러는 변화율). 기준 = 직전 252개 변화의 95백분위(오늘 제외).
     * 직전 변화가 252개 미만인 날은 판정하지 않는다(맵에 넣지 않음).
     */
    private NavigableMap<LocalDate, Boolean> triggers(List<Obs> obs, boolean pct) {
        double[] change = new double[obs.size()];
        for (int t = CHANGE_DAYS; t < obs.size(); t++) {
            double now = obs.get(t).value;
            double then = obs.get(t - CHANGE_DAYS).value;
            change[t] = pct ? now / then - 1.0 : now - then;
        }
        NavigableMap<LocalDate, Boolean> out = new TreeMap<>();
        for (int t = CHANGE_DAYS + PERCENTILE_WINDOW; t < obs.size(); t++) {
            double[] window = new double[PERCENTILE_WINDOW];
            System.arraycopy(change, t - PERCENTILE_WINDOW, window, 0, PERCENTILE_WINDOW);
            java.util.Arrays.sort(window);
            double p = window[(int) Math.ceil(PERCENTILE * PERCENTILE_WINDOW) - 1];
            out.put(obs.get(t).date, change[t] > p);
        }
        return out;
    }

    /** 한국 거래일별 매수 금지 여부 — 그날 아침까지 쓸 수 있는 최신 판정 둘 중 하나라도 급변이면 금지. */
    private Map<LocalDate, Boolean> blockedByKrDate(List<LocalDate> krDates,
                                                   NavigableMap<LocalDate, Boolean> rate,
                                                   NavigableMap<LocalDate, Boolean> dollar) {
        Map<LocalDate, Boolean> out = new HashMap<>();
        for (LocalDate d : krDates) {
            Boolean r = asOf(rate, d, false);
            Boolean u = asOf(dollar, d, true);
            if (r == null || u == null) {
                continue;
            }
            out.put(d, r || u);
        }
        return out;
    }

    /**
     * 한국 날짜 krDate 아침에 쓸 수 있는 가장 최근 판정.
     * 금리: 관측일 t &lt; krDate. 달러(H.10 주간 공표): t 다음 월요일(공표일) &lt; krDate.
     */
    private Boolean asOf(NavigableMap<LocalDate, Boolean> trig, LocalDate krDate, boolean weeklyH10) {
        if (!weeklyH10) {
            Map.Entry<LocalDate, Boolean> e = trig.lowerEntry(krDate);
            return e == null ? null : e.getValue();
        }
        // 공표일 = t 다음 월요일. 공표일 < krDate ⇔ t < krDate 직전 월요일(krDate가 월요일이면 그 전 주 월요일)
        LocalDate lastPublishBefore = krDate.minusDays(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Map.Entry<LocalDate, Boolean> e = trig.lowerEntry(lastPublishBefore);
        return e == null ? null : e.getValue();
    }

    /** 신규 매수 금지 장식자 — 내부 전략은 매일 그대로 호출하고(상태 보존), 금지일에는 매수 의도만 지운다. 매도는 그대로. */
    static final class BuyBlockStrategy implements BacktestStrategy {
        private final BacktestStrategy inner;
        private final Map<LocalDate, Boolean> blocked;

        BuyBlockStrategy(BacktestStrategy inner, Map<LocalDate, Boolean> blocked) {
            this.inner = inner;
            this.blocked = blocked;
        }

        @Override
        public List<TradeIntent> onCandle(Candle today, PortfolioState state) {
            List<TradeIntent> intents = inner.onCandle(today, state);
            if (!blocked.getOrDefault(today.date(), false)) {
                return intents;
            }
            return intents.stream().filter(t -> t.kind() != TradeIntent.Kind.BUY_STOP).toList();
        }
    }

    private PortfolioBacktestRunner.PortfolioResult runC3(Map<String, List<Candle>> candles,
                                                          Function<Integer, BacktestStrategy> factory) {
        WalkForwardRunner wf = new WalkForwardRunner(CostModel.defaults());
        PortfolioBacktestRunner.SleeveRunner sleeve = (cs, cap) -> wf.run(
                cs, N_CANDIDATES, factory, TRAIN, TEST, WARMUP, cap);
        BigDecimal capital = SLEEVE_CAPITAL.multiply(BigDecimal.valueOf(candles.size()));
        return new PortfolioBacktestRunner().run(candles, sleeve, capital, TRAIN, TEST);
    }

    // ───────────────────────────── 입출력·보조 ─────────────────────────────

    private record Obs(LocalDate date, double value) {
    }

    private List<Obs> loadSeries(Path p) throws IOException {
        List<Obs> out = new ArrayList<>();
        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split(",");
            out.add(new Obs(LocalDate.parse(f[0]), Double.parseDouble(f[1])));
        }
        return out;
    }

    private Map<String, Set<LocalDate>> loadEvents(Path p) throws IOException {
        Map<String, Set<LocalDate>> out = new HashMap<>();
        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split(",");
            out.computeIfAbsent(f[1].trim(), k -> new TreeSet<>()).add(LocalDate.parse(f[0]));
        }
        return out;
    }

    private static String ok(boolean b) {
        return b ? "○" : "✕";
    }

    private static double total(double[] r) {
        double v = 1.0;
        for (double x : r) {
            v *= 1.0 + x;
        }
        return v - 1.0;
    }

    private static double[] toArray(List<Double> l) {
        double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = l.get(i);
        }
        return a;
    }

    private static List<Double> toList(double[] a) {
        List<Double> l = new ArrayList<>(a.length);
        for (double v : a) {
            l.add(v);
        }
        return l;
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
