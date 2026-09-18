package com.autostock.backtest;

import com.autostock.common.event.Candle;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;

/**
 * 횡단면 백테스트 엔진 — 매월 말 유니버스를 구성하고 {@link CrossSectionalStrategy}가 고른 종목을
 * 익일 시가에 동일가중으로 리밸런싱한다. 출력은 일간 수익률 시계열이라 기존 판정 도구
 * ({@link PerformanceCalculator}, {@link StationaryBootstrap}, {@link SpaTest})를 그대로 받는다.
 *
 * <p>기존 {@link PortfolioBacktestRunner}(종목별 슬리브에 시계열 전략을 독립 적용)와 다른 점은 "종목 집합이
 * 매달 바뀐다"는 것뿐이고, 비용 모델·룩어헤드 원칙은 같다.
 *
 * <p>룩어헤드 방지 규칙:
 * <ul>
 *   <li>형성일(월 마지막 거래일) <b>종가까지</b>의 데이터로만 유니버스·순위를 계산한다.</li>
 *   <li>체결은 <b>익일 시가</b>이며 {@link CostModel}의 슬리피지·수수료·거래세를 적용한다.</li>
 *   <li>유니버스는 형성 시점의 <b>직전 60거래일 평균 거래대금 상위 N</b>이다 — 시가총액 대신 거래대금을 쓰는 이유는
 *       역사적 상장주식수가 없어 "현재 주식수 × 과거 가격"으로 근사하면 룩어헤드가 섞이기 때문이다. 거래대금은
 *       그 시점 데이터만으로 계산되는 point-in-time 지표다.</li>
 *   <li>형성일에 거래가 없는 종목(정지·미상장)은 후보에서 제외한다.</li>
 * </ul>
 *
 * <p>상장폐지 처리: 어떤 종목의 시계열이 캘린더 도중에 끝나면(이후 데이터가 전혀 없음) 마지막 종가에 매도 비용을
 * 물고 청산한 것으로 본다. 폐지 직전 급락은 데이터에 있는 만큼만 반영되므로 결과는 <b>상한 추정치</b>이며,
 * 아예 데이터가 없는 폐지 종목(수집 실패)은 유니버스에 들어오지 못한다(생존 편향 — 실험 기록에 병기할 것).
 *
 * <p>정지·결측일: 보유 종목이 어느 날 거래가 없으면 그날은 마지막 종가로 평가한다(가격 불변). 형성일에 거래가
 * 없으면 후보에서 빠지고, 이미 보유 중이면 다음 거래 재개일 시가에 교체 매도가 실행된다.
 *
 * <p>수량은 분수 주식을 허용한다(동일가중 회계 단순화). 종목당 수백만 원 규모에선 정수 주식 반올림 오차가
 * 수익률에 유의미한 영향을 주지 않으며, 이 엔진의 목적은 게이트 판정용 수익률 분포 산출이다.
 */
public final class CrossSectionalBacktestRunner {

    /**
     * @param start           시뮬레이션 첫날(이 날 시가에 최초 편입). 룩백을 위해 캘린더는 이보다 앞서 시작해야 한다
     * @param end             마지막 날(포함)
     * @param universeSize    형성 시점 거래대금 상위 N
     * @param liquidityWindow 거래대금 평균 창(거래일)
     * @param initialCapital  초기 자본
     * @param costModel       비용 모델
     * @param trials          DSR 병기용 시도 수(연구 누적 계열 수). 판정은 SPA·부트스트랩이 담당하고 DSR은 참고치
     * @param marketCapByDate 시점별 시가총액(날짜 → 종목코드 → 시총, 원). 비어 있지 않으면 유니버스를 <b>형성일 이하 가장
     *                        최근 날짜의 실제 시가총액 상위 N</b>으로 뽑는다(X1b·X2b — KRX 전종목 시세 월말 스냅샷,
     *                        scripts/fetch_krx_marketcap.py). 그 날짜의 실제 상장주식수 기준이라 룩어헤드가 없고 폐지 종목도
     *                        그 시점엔 포함된다. 스냅샷이 형성일보다 오래된 경우(결측 월)는 직전 스냅샷을 쓴다.
     *                        거래대금 조건(유동성 하한: 창 내 거래일 90% 이상)은 그대로 적용된다
     * @param maxAbsDailyReturnGuard 0보다 크면, 전략 룩백 창 안에 일간 |수익률|이 이 값을 넘는 날이 있는 종목을 유니버스에서 뺀다.
     *                        KRX 가격제한폭은 ±30%라 정상 거래에선 불가능한 값(정지 후 재개·수정주가 오류) — 데이터 오류 가드.
     *                        실측 2026-09-18: 야후 데이터 343종목에서 >40% 일간 변동 542건
     */
    public record Config(LocalDate start,
                         LocalDate end,
                         int universeSize,
                         int liquidityWindow,
                         BigDecimal initialCapital,
                         CostModel costModel,
                         int trials,
                         java.util.NavigableMap<LocalDate, Map<String, Double>> marketCapByDate,
                         double maxAbsDailyReturnGuard) {

        /** 거래대금 유니버스(X1·X2 원 선언) — 시가총액 근사 없음, 데이터 오류 가드 없음. */
        public Config(LocalDate start, LocalDate end, int universeSize, int liquidityWindow,
                      BigDecimal initialCapital, CostModel costModel, int trials) {
            this(start, end, universeSize, liquidityWindow, initialCapital, costModel, trials, new java.util.TreeMap<>(), 0.0);
        }

        public Config {
            Objects.requireNonNull(start);
            Objects.requireNonNull(end);
            Objects.requireNonNull(initialCapital);
            Objects.requireNonNull(costModel);
            if (!end.isAfter(start)) {
                throw new IllegalArgumentException("end는 start 이후여야 함");
            }
            if (universeSize <= 0 || liquidityWindow <= 0) {
                throw new IllegalArgumentException("universeSize/liquidityWindow는 양수");
            }
        }
    }

    /**
     * @param dates                 일간 수익률에 대응하는 날짜(start~end 캘린더)
     * @param dailyReturns          일간 수익률
     * @param result                성과 지표(PerformanceCalculator)
     * @param rebalances            리밸런싱 횟수
     * @param trades                매수·매도 체결 건수(리밸런싱 조정 포함)
     * @param avgTurnover           리밸런싱당 평균 회전율(매도 금액 / 직전 자산) — 비용 드래그 해석용
     * @param delistingsLiquidated  시계열 종료로 강제 청산된 건수
     * @param avgHoldings           평균 보유 종목 수
     * @param avgUniverseSize       형성 시점 평균 유니버스 크기(데이터 커버리지 점검용)
     */
    public record Result(String label,
                         List<LocalDate> dates,
                         List<Double> dailyReturns,
                         BacktestResult result,
                         int rebalances,
                         int trades,
                         double avgTurnover,
                         int delistingsLiquidated,
                         double avgHoldings,
                         double avgUniverseSize) {
    }

    private final PerformanceCalculator performanceCalculator = new PerformanceCalculator();

    /**
     * @param candlesBySymbol 종목별 캔들(날짜 오름차순). 캘린더 밖의 날짜는 무시된다
     * @param calendar        거래일 캘린더(오름차순) — 보통 벤치마크(KODEX200)의 날짜. 룩백을 위해 start보다 앞서야 한다
     */
    public Result run(Map<String, List<Candle>> candlesBySymbol,
                      List<LocalDate> calendar,
                      CrossSectionalStrategy strategy,
                      Config config) {
        return run(PriceTable.fromCandles(candlesBySymbol, calendar), strategy, config);
    }

    /** 대용량(수천 종목) 실험용 — {@link PriceTable#fromCsvDirectory}로 만든 표를 그대로 받는다. */
    public Result run(PriceTable table, CrossSectionalStrategy strategy, Config config) {
        Objects.requireNonNull(strategy);
        List<LocalDate> calendar = table.calendar;
        Map<String, Series> series = table.series;
        int startIdx = firstIndexAtOrAfter(calendar, config.start());
        int endIdx = lastIndexAtOrBefore(calendar, config.end());
        if (startIdx < 0 || endIdx < 0 || startIdx > endIdx) {
            throw new IllegalArgumentException("start~end가 캘린더와 겹치지 않음");
        }
        if (startIdx == 0) {
            throw new IllegalArgumentException("start 이전에 룩백용 캘린더가 없음 — 캘린더를 더 앞에서 시작할 것");
        }

        double cash = config.initialCapital().doubleValue();
        Map<String, Double> qty = new LinkedHashMap<>();     // 보유 수량(분수 허용)
        Map<String, Double> lastClose = new HashMap<>();     // 결측일 평가용 마지막 종가
        List<String> pendingTargets = null;                  // 형성일에 계산돼 익일 시가에 실행될 목표
        Set<String> pendingExits = new HashSet<>();          // 리밸런싱 날 거래가 없어 못 판 종목 — 다음 거래일 시가에 매도

        List<LocalDate> dates = new ArrayList<>();
        List<Double> dailyReturns = new ArrayList<>();
        int rebalances = 0;
        int trades = 0;
        int delistings = 0;
        double turnoverSum = 0;
        double holdingsSum = 0;
        double universeSum = 0;
        int universeCount = 0;

        // 최초 편입: start 전날을 형성일로 삼아 첫날 시가부터 투자한다
        Formation initial = form(series, calendar, startIdx - 1, strategy, config);
        pendingTargets = initial.targets();
        universeSum += initial.universeSize();
        universeCount++;

        double prevEquity = cash;
        for (int i = startIdx; i <= endIdx; i++) {
            // ── 0) 시가: 지연 매도(리밸런싱 날 거래정지였던 종목) ────────────────
            for (String s : new ArrayList<>(pendingExits)) {
                if (!qty.containsKey(s)) {
                    pendingExits.remove(s);
                    continue;
                }
                double open = series.get(s).open[i];
                if (!Double.isNaN(open)) {
                    cash += sell(qty.remove(s), open, config.costModel());
                    pendingExits.remove(s);
                    trades++;
                }
            }

            // ── 1) 시가: 리밸런싱 실행 ─────────────────────────────────────
            if (pendingTargets != null) {
                double equityAtOpen = cash + markToMarket(qty, series, lastClose, i, true);
                double soldValue = 0;
                // 매도: 목표에 없는 종목 전량, 목표에 있는 종목은 초과분
                List<String> held = new ArrayList<>(qty.keySet());
                Map<String, Double> targetValue = new HashMap<>();
                if (!pendingTargets.isEmpty()) {
                    double each = equityAtOpen / pendingTargets.size();
                    for (String t : pendingTargets) {
                        targetValue.put(t, each);
                    }
                }
                for (String s : held) {
                    double open = series.get(s).open[i];
                    if (Double.isNaN(open)) {
                        if (!targetValue.containsKey(s)) {
                            pendingExits.add(s); // 오늘 거래 없음(정지) — 거래 재개 첫날 시가에 매도
                        }
                        continue;
                    }
                    pendingExits.remove(s);
                    double current = qty.get(s) * open;
                    double target = targetValue.getOrDefault(s, 0.0);
                    if (current > target) {
                        double sellQty = (current - target) / open;
                        double proceeds = sell(sellQty, open, config.costModel());
                        cash += proceeds;
                        soldValue += sellQty * open;
                        trades++;
                        double remain = qty.get(s) - sellQty;
                        if (remain * open < 1.0) {
                            qty.remove(s);
                        } else {
                            qty.put(s, remain);
                        }
                    }
                }
                // 매수: 목표 대비 부족분
                for (String t : pendingTargets) {
                    double open = series.get(t).open[i];
                    if (Double.isNaN(open) || open <= 0) {
                        continue; // 체결 불가 — 그 몫은 현금으로 남는다
                    }
                    double current = qty.getOrDefault(t, 0.0) * open;
                    double target = targetValue.get(t);
                    double shortfall = target - current;
                    if (shortfall <= 1.0) {
                        continue;
                    }
                    double buyPrice = open * (1 + config.costModel().slippagePct());
                    double notional = Math.min(shortfall, cash / (1 + config.costModel().buyFeePct()));
                    if (notional <= 1.0) {
                        continue;
                    }
                    double fee = notional * config.costModel().buyFeePct();
                    cash -= notional + fee;
                    qty.merge(t, notional / buyPrice, Double::sum);
                    lastClose.put(t, open);
                    trades++;
                }
                rebalances++;
                turnoverSum += equityAtOpen > 0 ? soldValue / equityAtOpen : 0;
                pendingTargets = null;
            }

            // ── 2) 상장폐지(시계열 종료) 청산 ────────────────────────────────
            for (String s : new ArrayList<>(qty.keySet())) {
                Series sr = series.get(s);
                if (sr.lastIndex < i) {
                    double px = lastClose.getOrDefault(s, sr.close[sr.lastIndex]);
                    cash += sell(qty.get(s), px, config.costModel());
                    qty.remove(s);
                    delistings++;
                    trades++;
                }
            }

            // ── 3) 종가 평가 ───────────────────────────────────────────────
            double equity = cash + markToMarket(qty, series, lastClose, i, false);
            dates.add(calendar.get(i));
            dailyReturns.add(prevEquity > 0 ? equity / prevEquity - 1.0 : 0.0);
            prevEquity = equity;
            holdingsSum += qty.size();

            // ── 4) 월말 형성 → 익일 시가 실행 예약 ────────────────────────────
            if (i < endIdx && isMonthEnd(calendar, i)) {
                Formation f = form(series, calendar, i, strategy, config);
                pendingTargets = f.targets();
                universeSum += f.universeSize();
                universeCount++;
            }
        }

        int n = dailyReturns.size();
        BacktestResult perf = performanceCalculator.calculate(
                dailyReturns, config.initialCapital(), BigDecimal.valueOf(prevEquity),
                trades, Math.max(1, config.trials()), 0.0);
        return new Result(strategy.label(), dates, dailyReturns, perf, rebalances, trades,
                rebalances > 0 ? turnoverSum / rebalances : 0.0, delistings,
                n > 0 ? holdingsSum / n : 0.0,
                universeCount > 0 ? universeSum / universeCount : 0.0);
    }

    // ── 형성: 유니버스 → 전략 선택 ──────────────────────────────────────────

    private record Formation(List<String> targets, int universeSize) {
    }

    private Formation form(Map<String, Series> series, List<LocalDate> calendar, int f,
                           CrossSectionalStrategy strategy, Config config) {
        int minHistory = strategy.minHistoryDays();
        int window = config.liquidityWindow();
        boolean byCap = !config.marketCapByDate().isEmpty();
        Map<String, Double> capSnapshot = Map.of();
        if (byCap) {
            Map.Entry<LocalDate, Map<String, Double>> snap = config.marketCapByDate().floorEntry(calendar.get(f));
            if (snap == null) {
                return new Formation(List.of(), 0); // 형성일 이전 스냅샷 없음 — 이 달은 현금
            }
            capSnapshot = snap.getValue();
        }
        double guard = config.maxAbsDailyReturnGuard();
        List<Object[]> liquid = new ArrayList<>();
        for (Map.Entry<String, Series> e : series.entrySet()) {
            Series s = e.getValue();
            if (f - Math.max(minHistory, window) + 1 < 0 || f < s.firstIndex || s.lastIndex < f) {
                continue;
            }
            if (Double.isNaN(s.close[f])) {
                continue; // 형성일 거래 없음(정지) — 제외
            }
            double sum = 0;
            int cnt = 0;
            for (int i = f - window + 1; i <= f; i++) {
                if (!Double.isNaN(s.close[i]) && s.volume[i] > 0) {
                    sum += s.close[i] * s.volume[i];
                    cnt++;
                }
            }
            if (cnt < window * 0.9) {
                continue; // 최근 창에서 결측이 많은 종목(신규 상장·장기 정지) 제외
            }
            if (guard > 0 && s.hasJumpBeyond(f - minHistory + 1, f, guard)) {
                continue; // 데이터 오류 가드
            }
            double rankValue = sum / cnt;
            if (byCap) {
                Double cap = capSnapshot.get(e.getKey());
                if (cap == null || cap <= 0) {
                    continue; // 그 시점 KRX 시세에 없음(미상장·정지) — 제외
                }
                rankValue = cap;
            }
            liquid.add(new Object[]{e.getKey(), rankValue});
        }
        liquid.sort((a, b) -> {
            int c = Double.compare((double) b[1], (double) a[1]);
            return c != 0 ? c : ((String) a[0]).compareTo((String) b[0]);
        });
        Map<String, double[]> histories = new LinkedHashMap<>();
        for (int k = 0; k < Math.min(config.universeSize(), liquid.size()); k++) {
            String sym = (String) liquid.get(k)[0];
            double[] hist = series.get(sym).forwardFilledCloses(f - minHistory + 1, f);
            if (hist != null) {
                histories.put(sym, hist);
            }
        }
        return new Formation(List.copyOf(strategy.select(histories)), histories.size());
    }

    // ── 회계 보조 ──────────────────────────────────────────────────────────

    private static double sell(double quantity, double price, CostModel cost) {
        double sellPrice = price * (1 - cost.slippagePct());
        double notional = quantity * sellPrice;
        return notional - notional * (cost.sellFeePct() + cost.sellTaxPct());
    }

    private static double markToMarket(Map<String, Double> qty, Map<String, Series> series,
                                       Map<String, Double> lastClose, int i, boolean atOpen) {
        double total = 0;
        for (Map.Entry<String, Double> e : qty.entrySet()) {
            Series s = series.get(e.getKey());
            double px = atOpen ? s.open[i] : s.close[i];
            if (Double.isNaN(px)) {
                px = lastClose.getOrDefault(e.getKey(), Double.NaN);
                if (Double.isNaN(px)) {
                    px = s.close[Math.min(i, s.lastIndex)];
                }
            } else if (!atOpen) {
                lastClose.put(e.getKey(), px);
            }
            total += e.getValue() * px;
        }
        return total;
    }

    private static boolean isMonthEnd(List<LocalDate> calendar, int i) {
        return i + 1 < calendar.size()
                && calendar.get(i + 1).getMonth() != calendar.get(i).getMonth();
    }

    private static int firstIndexAtOrAfter(List<LocalDate> calendar, LocalDate date) {
        for (int i = 0; i < calendar.size(); i++) {
            if (!calendar.get(i).isBefore(date)) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexAtOrBefore(List<LocalDate> calendar, LocalDate date) {
        for (int i = calendar.size() - 1; i >= 0; i--) {
            if (!calendar.get(i).isAfter(date)) {
                return i;
            }
        }
        return -1;
    }

    // ── 캘린더 정렬 시계열 ─────────────────────────────────────────────────

    private static final class Series {
        final double[] open;
        final double[] close;
        final long[] volume;
        int firstIndex = Integer.MAX_VALUE;
        int lastIndex = -1;

        Series(int n) {
            open = new double[n];
            close = new double[n];
            volume = new long[n];
            Arrays.fill(open, Double.NaN);
            Arrays.fill(close, Double.NaN);
        }

        void put(int idx, double openPx, double closePx, long vol) {
            if (closePx <= 0) {
                return;
            }
            close[idx] = closePx;
            open[idx] = openPx > 0 ? openPx : closePx;
            volume[idx] = vol;
            firstIndex = Math.min(firstIndex, idx);
            lastIndex = Math.max(lastIndex, idx);
        }

        /** [from, to] 구간에 인접 거래일 간 |수익률| > threshold 인 날이 있는지(결측일은 건너뛰고 직전 유효 종가와 비교). */
        boolean hasJumpBeyond(int from, int to, double threshold) {
            double last = Double.NaN;
            for (int i = Math.max(from, 0); i <= to; i++) {
                double c = close[i];
                if (Double.isNaN(c)) {
                    continue;
                }
                if (!Double.isNaN(last) && Math.abs(c / last - 1) > threshold) {
                    return true;
                }
                last = c;
            }
            return false;
        }

        /** [from, to] 구간 종가를 앞 값으로 채워 반환. 첫 값부터 결측이면 null(이력 부족). */
        double[] forwardFilledCloses(int from, int to) {
            if (from < 0 || from < firstIndex) {
                return null;
            }
            double[] out = new double[to - from + 1];
            double last = Double.NaN;
            for (int i = from; i <= to; i++) {
                if (!Double.isNaN(close[i])) {
                    last = close[i];
                }
                if (Double.isNaN(last)) {
                    return null;
                }
                out[i - from] = last;
            }
            return out;
        }
    }

    /**
     * 캘린더에 정렬된 종목별 가격표(결측 = NaN). 수천 종목 × 수천 일을 double 배열로 들고 있어
     * {@link Candle} 리스트(BigDecimal, 종목당 수백 KB)보다 메모리가 10분의 1 수준이다 — 유니버스 실험은
     * 반드시 {@link #fromCsvDirectory}로 만든다(CandleCsvLoader로 3,000종목을 올리면 테스트 힙 2g를 넘긴다).
     */
    public static final class PriceTable {
        final List<LocalDate> calendar;
        final Map<LocalDate, Integer> dateIndex;
        final Map<String, Series> series;

        private PriceTable(List<LocalDate> calendar, Map<String, Series> series) {
            if (calendar.isEmpty()) {
                throw new IllegalArgumentException("calendar가 비어 있음");
            }
            this.calendar = List.copyOf(calendar);
            this.dateIndex = new HashMap<>();
            for (int i = 0; i < calendar.size(); i++) {
                dateIndex.put(calendar.get(i), i);
            }
            this.series = series;
        }

        public int symbolCount() {
            return series.size();
        }

        public List<LocalDate> calendar() {
            return calendar;
        }

        public static PriceTable fromCandles(Map<String, List<Candle>> candlesBySymbol, List<LocalDate> calendar) {
            Map<LocalDate, Integer> idx = new HashMap<>();
            for (int i = 0; i < calendar.size(); i++) {
                idx.put(calendar.get(i), i);
            }
            Map<String, Series> out = new LinkedHashMap<>();
            for (Map.Entry<String, List<Candle>> e : candlesBySymbol.entrySet()) {
                Series s = new Series(calendar.size());
                for (Candle c : e.getValue()) {
                    Integer i = idx.get(c.date());
                    if (i != null) {
                        s.put(i, c.open().doubleValue(), c.close().doubleValue(), c.volume());
                    }
                }
                if (s.lastIndex >= 0) {
                    out.put(e.getKey(), s);
                }
            }
            return new PriceTable(calendar, out);
        }

        /**
         * 디렉터리의 {@code {종목코드}.csv}(date,open,high,low,close,volume — CandleCsvLoader와 동일 형식)를 전부 읽는다.
         * 캘린더 밖 날짜는 버린다. {@code minRows} 미만인 파일은 제외한다(데이터 빈약 종목).
         */
        public static PriceTable fromCsvDirectory(Path dir, List<LocalDate> calendar, int minRows) {
            return fromCsvDirectory(dir, calendar, minRows, Set.of());
        }

        /** {@code exclude}에 든 종목코드는 읽지 않는다(스팩·리츠·선박펀드 등 유니버스 정의상 제외 대상). */
        public static PriceTable fromCsvDirectory(Path dir, List<LocalDate> calendar, int minRows, Set<String> exclude) {
            Map<LocalDate, Integer> idx = new HashMap<>();
            for (int i = 0; i < calendar.size(); i++) {
                idx.put(calendar.get(i), i);
            }
            Map<String, Series> out = new java.util.TreeMap<>();
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.csv")) {
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    String symbol = name.substring(0, name.length() - 4);
                    if (exclude.contains(symbol)) {
                        continue;
                    }
                    Series s = new Series(calendar.size());
                    int rows = 0;
                    try (BufferedReader r = Files.newBufferedReader(file)) {
                        String line = r.readLine(); // header
                        while ((line = r.readLine()) != null) {
                            if (line.isBlank()) {
                                continue;
                            }
                            int c1 = line.indexOf(',');
                            if (c1 < 0) {
                                continue;
                            }
                            Integer i = idx.get(LocalDate.parse(line.substring(0, c1)));
                            if (i == null) {
                                continue; // 캘린더 밖(주말·해외 휴장 등) — 버린다
                            }
                            String[] cols = line.split(",", -1);
                            if (cols.length < 6) {
                                continue;
                            }
                            s.put(i, Double.parseDouble(cols[1]), Double.parseDouble(cols[4]), (long) Double.parseDouble(cols[5]));
                            rows++;
                        }
                    }
                    if (rows >= minRows && s.lastIndex >= 0) {
                        out.put(symbol, s);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("가격표 로드 실패: " + dir, e);
            }
            return new PriceTable(calendar, out);
        }
    }
}
