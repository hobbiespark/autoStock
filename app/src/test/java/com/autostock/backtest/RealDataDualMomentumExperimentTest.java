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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * D 계열(듀얼 모멘텀, ADR-11 1순위) — 장기(저빈도) 자산배분 실험.
 *
 * <h2>배경</h2>
 * PLAN ADR-11은 이번 사이클의 1순위 연구를 "장기(저빈도) 전략"으로 지정했다: Antonacci의
 * 듀얼 모멘텀 + Faber의 10개월(약 210거래일) 이동평균 추세필터 + 변동성 타게팅, 유니버스는
 * KODEX200 + 해외/대체자산 ETF + 현금. STRATEGY.md에 따르면 C3(시계열모멘텀+국면필터+
 * 변동성타게팅)는 게이트①에서 MDD 25.0%, 교정DSR(N=6) 0.058로 탈락했다 — 이 실험은 완전히
 * 다른 저빈도(월간 리밸런싱) 접근으로 게이트①(OOS수익&gt;0, 교정DSR(N=10)&gt;0.95, MDD&lt;15%)
 * 통과 후보를 찾는다.
 *
 * <h2>3개 사전등록 변형 — 파라미터 스윕 없음, 문헌 기본값만 사용</h2>
 * <ul>
 *   <li><b>D0</b> — GEM(Global Equities Momentum, Antonacci) 듀얼 모멘텀: 21거래일마다
 *       리밸런싱. 4개 ETF의 252거래일 추세수익률(상대모멘텀)을 비교해 최고 자산을 고르고,
 *       그 자산의 252일 수익률이 0 이하이면(절대모멘텀 실패) 현금을 보유한다. 항상 단일
 *       자산(100%) 배분.</li>
 *   <li><b>D1</b> — D0 + Faber 10개월(210거래일) 이동평균 추세필터: D0와 동일하되, 선택된
 *       자산의 가격이 210거래일 이동평균선 위에 있어야만 그 자산을 보유한다(아래면 현금).</li>
 *   <li><b>D2</b> — D1 + 변동성 타게팅(목표 연변동성 10%): D1이 고른 자산에 대해
 *       {@link VolTargetMath#fraction}으로 투입비중을 줄이고, 줄인 나머지는 현금으로 둔다.</li>
 * </ul>
 *
 * <h2>유니버스 — 4개 ETF + 현금 (인버스 없음, C4 프로덕션 코드 미접촉)</h2>
 * 069500(KODEX200), 143850(미국 S&amp;P500선물 H), 148070(국내 국고채10년), 132030(금선물 H).
 * <b>현금 수익률은 0%로 가정한다</b> — 무위험 이자수익을 반영하지 않는 보수적 가정이며,
 * 실제로는 현금 보유 기간의 CAGR/Sharpe를 과소평가하는 방향으로만 작용하므로 게이트
 * 판정에는 오히려 불리(보수적)하다.
 *
 * <h2>평가 방식 — walk-forward 불필요</h2>
 * 이 실험의 모든 파라미터(252일 모멘텀, 210일 SMA, 21일 리밸런싱, 목표변동성 10%)는
 * 문헌 표준값이며 이 데이터로 파라미터를 선택(in-sample 튜닝)한 적이 없다. 따라서
 * walk-forward로 train/test를 분리할 다중검정 대상이 없다 — 252거래일 워밍업 이후
 * 전체 잔여 기간을 단일 OOS 구간으로 평가한다.
 *
 * <h2>비용 모델 — ETF 전용(국내 상장 ETF는 증권거래세 면제)</h2>
 * 매수/매도 수수료 각 0.015% + 슬리피지 5bp, 매도세 0%(국내 상장 ETF는 증권거래세가
 * 면제된다 — 2026년 세제 기준). ETF 총보수는 이미 순자산가치(및 시장가격)에 매일 반영돼
 * 있으므로 별도 차감하지 않는다. D2에 한해 "비용 가정이 틀렸을 경우"의 리스크를 가늠하기
 * 위한 참고용 민감도 수치로, 매도세를 주식형(0.20%)으로 바꾼 버전도 함께 계산한다(4번째
 * 변형이 아니라 D2 옆에 붙는 참고 숫자).
 *
 * <h2>게이트 — N=3(D계열 내부)과 N=10(보수적, 전체 연구계열)</h2>
 * N=10 = A, B, C0, C1, C2, C3, C4({@link RealDataC4InverseExperimentTest}) + D0, D1, D2.
 * <b>방법론적 주의</b>: A~C4는 walk-forward(train=252/test=63) OOS 구간을 이어붙인 것이고,
 * D0~D2는 이 테스트만의 단일·전체이력 OOS 구간(워밍업 이후 전체)이다 — 서로 다른 평가
 * 창을 하나의 N=10 풀에 섞는 것이므로 엄밀한 의미의 동질적 비교는 아니다(과최적화 위험을
 * "과소평가"하는 방향일 수 있음 — 아래 최종 판정 출력에서 명시적으로 경고한다).
 *
 * <p>데이터가 없으면 조용히 스킵한다(다른 Real* 테스트와 동일 이유). 성과 수치는 assert하지
 * 않고(느슨한 정합성 체크만) 콘솔 표로 출력하며, 실제 채택 판단은 사람이 표를 읽고 내린다.
 */
class RealDataDualMomentumExperimentTest {

    // ───────────────────────────── D계열 유니버스(4 ETF + 현금) ─────────────────────────────

    private static final Map<String, String> D_SYMBOLS = new LinkedHashMap<>();
    static {
        D_SYMBOLS.put("069500", "KODEX200");
        D_SYMBOLS.put("143850", "미국S&P500선물(H)");
        D_SYMBOLS.put("148070", "국내 국고채10년");
        D_SYMBOLS.put("132030", "금선물(H)");
    }
    private static final String CASH = "CASH";
    private static final String BASELINE_SYMBOL = "069500";

    private static final int MOMENTUM_LOOKBACK = 252; // 252거래일 추세수익률
    private static final int SMA_LOOKBACK = 210;       // Faber 10개월 근사(거래일)
    private static final int REBALANCE_INTERVAL = 21;  // 21거래일마다 리밸런싱
    private static final double D2_TARGET_VOL = 0.10;  // D2 목표 연변동성 10%

    /**
     * 워밍업 — 리밸런싱 결정일 i의 판단은 "직전 확정 종가"(인덱스 i-1)만 사용한다(룩어헤드
     * 방지). 모멘텀 계산에 asOf 기준 252거래일 전 데이터가 필요하므로 asOf(=i-1) &gt;= 252여야
     * 하고, 그러려면 i &gt;= 253이어야 한다 — 그래서 워밍업은 "252거래일"이 아니라 253으로
     * 한 칸 밀린다(스펙의 "252거래일 워밍업"과 사실상 동일, 하루 오프셋 차이).
     */
    private static final int WARMUP = MOMENTUM_LOOKBACK + 1;

    private static final CostModel ETF_COST_MODEL = new CostModel(0.00015, 0.00015, 0.0, 0.0005);
    /** D2 전용 민감도 체크 — 매도세를 주식형(0.20%)으로 바꿨을 때. */
    private static final CostModel ETF_COST_MODEL_STOCK_TAX = new CostModel(0.00015, 0.00015, 0.0020, 0.0005);

    private static final double GATE_MIN_DSR = 0.95;
    private static final double GATE_MAX_MDD = 0.15;
    private static final int TRADING_DAYS_PER_YEAR = 252;

    // ───────────────────────────── N=10 보수적 게이트용 — 기존 6개 종목/전략계열 재현 ─────────────────────────────

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

    private final CandleCsvLoader loader = new CandleCsvLoader();
    private final PerformanceCalculator performanceCalculator = new PerformanceCalculator();
    private final PortfolioBacktestRunner portfolioRunner = new PortfolioBacktestRunner();

    private enum Variant { D0, D1, D2 }

    /** 하루치 리밸런싱 시뮬레이션 결과물 하나. */
    private record SimResult(List<Double> dailyReturns, double totalReturn, double cagr, double mdd,
                              double sharpeAnnualized, int tradeCount, Map<String, Double> timeInPositionPct) {
    }

    @Test
    void D계열_듀얼모멘텀_3변형_게이트1_평가() {
        Path dataDir = resolveDataDir();
        assumeTrue(dataDir != null,
                "data/ 디렉터리를 찾지 못해 D계열 실험을 스킵함 — "
                        + "먼저 `python3 scripts/fetch_yahoo_daily.py` 로 data/ 를 채운 뒤 다시 실행할 것");

        // ── 1) D계열 4개 ETF 로드 ──
        Map<String, List<Candle>> dCandles = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : D_SYMBOLS.entrySet()) {
            String code = entry.getKey();
            Path csv = dataDir.resolve(code + ".csv");
            if (!Files.isRegularFile(csv)) {
                assumeTrue(false, "종목 " + code + "(" + entry.getValue() + ") CSV 없음 — D계열 4종목 모두 필요하므로 전체 스킵함: " + csv);
                return;
            }
            List<Candle> candles = loader.load(csv);
            if (candles.size() < WARMUP + REBALANCE_INTERVAL) {
                assumeTrue(false, "종목 " + code + "(" + entry.getValue() + ") 캔들 수 부족 — 전체 스킵함");
                return;
            }
            dCandles.put(code, candles);
        }

        // ── 2) 공통 거래일(교집합)로 정렬 — 종목별 데이터 종료일이 다를 수 있어 반드시 필요 ──
        List<LocalDate> commonDates = commonDates(dCandles);
        assumeTrue(commonDates.size() >= WARMUP + REBALANCE_INTERVAL,
                "공통 거래일 수가 부족해 D계열 실험을 스킵함(실제 " + commonDates.size() + "일)");

        Map<String, List<BigDecimal>> closesByAsset = alignCloses(dCandles, commonDates);
        int n = commonDates.size();

        LocalDate dataStart = commonDates.get(0);
        LocalDate dataEndCommon = commonDates.get(n - 1);
        LocalDate oosStart = commonDates.get(WARMUP);
        LocalDate oosEnd = commonDates.get(n - 1);

        // ── 3) D0/D1/D2 실행 ──
        SimResult d0 = simulate(Variant.D0, closesByAsset, n, ETF_COST_MODEL);
        SimResult d1 = simulate(Variant.D1, closesByAsset, n, ETF_COST_MODEL);
        SimResult d2 = simulate(Variant.D2, closesByAsset, n, ETF_COST_MODEL);
        SimResult d2StockTax = simulate(Variant.D2, closesByAsset, n, ETF_COST_MODEL_STOCK_TAX);

        for (Map.Entry<String, SimResult> e : Map.of("D0", d0, "D1", d1, "D2", d2).entrySet()) {
            SimResult r = e.getValue();
            assertFalse(r.dailyReturns().isEmpty(), e.getKey() + ": OOS 일별수익률이 비어 있음");
            assertFalse(Double.isNaN(r.totalReturn()), e.getKey() + ": totalReturn이 NaN");
            assertFalse(Double.isNaN(r.cagr()), e.getKey() + ": cagr이 NaN");
            assertFalse(Double.isNaN(r.mdd()), e.getKey() + ": mdd가 NaN");
            assertTrue(r.mdd() >= 0.0 && r.mdd() <= 1.0, e.getKey() + ": mdd가 [0,1] 범위를 벗어남");
            assertFalse(Double.isNaN(r.sharpeAnnualized()), e.getKey() + ": sharpe가 NaN");
        }

        // ── 4) 기준선 2종 — 같은 OOS 구간 ──
        double kodexBuyHold = buyHoldReturn(closesByAsset.get(BASELINE_SYMBOL), WARMUP, n);
        double equalWeightBuyHold = equalWeightBuyHoldReturn(closesByAsset, WARMUP, n);

        // ── 5) N=3 (D계열 내부만) DSR ──
        double[] sharpesDailyN3 = {
                d0.sharpeAnnualized() / Math.sqrt(TRADING_DAYS_PER_YEAR),
                d1.sharpeAnnualized() / Math.sqrt(TRADING_DAYS_PER_YEAR),
                d2.sharpeAnnualized() / Math.sqrt(TRADING_DAYS_PER_YEAR)
        };
        double d0DsrN3 = performanceCalculator.deflatedSharpeAcrossFamilies(d0.dailyReturns(), sharpesDailyN3);
        double d1DsrN3 = performanceCalculator.deflatedSharpeAcrossFamilies(d1.dailyReturns(), sharpesDailyN3);
        double d2DsrN3 = performanceCalculator.deflatedSharpeAcrossFamilies(d2.dailyReturns(), sharpesDailyN3);

        // ── 6) N=10 (보수적, 전체 연구계열: A,B,C0,C1,C2,C3,C4,D0,D1,D2) — 기존 7계열 재현 ──
        Path familyDataDir = dataDir; // 동일 data/ 디렉터리
        boolean familyDataAvailable = familyDataAvailable(familyDataDir);

        Double d0DsrN10 = null, d1DsrN10 = null, d2DsrN10 = null;
        List<String> n10Labels = new ArrayList<>();
        List<Double> n10SharpesDaily = new ArrayList<>();
        List<Double> n10OosReturnsPct = new ArrayList<>();

        // 선택풀 N=8(ADR-12 제안 검토용 병기) — OOS수익 양수 계열만: C0,C1,C2,C3,C4,D0,D1,D2(A,B 제외)
        Double c0DsrN8 = null, c1DsrN8 = null, c2DsrN8 = null, c3DsrN8 = null, c4DsrN8 = null;
        Double d0DsrN8 = null, d1DsrN8 = null, d2DsrN8 = null;
        List<String> n8Labels = new ArrayList<>();
        List<Double> n8SharpesDaily = new ArrayList<>();
        List<Double> n8OosReturnsPct = new ArrayList<>();

        if (familyDataAvailable) {
            List<FamilyRun> priorFamilies = runPriorSevenFamilies(familyDataDir);
            double[] sharpesDailyN10 = new double[10];
            for (int i = 0; i < 7; i++) {
                sharpesDailyN10[i] = priorFamilies.get(i).result().sharpe() / Math.sqrt(TRADING_DAYS_PER_YEAR);
                n10Labels.add(priorFamilies.get(i).label());
                n10SharpesDaily.add(sharpesDailyN10[i]);
                n10OosReturnsPct.add(priorFamilies.get(i).result().totalReturn() * 100);
            }
            sharpesDailyN10[7] = sharpesDailyN3[0];
            sharpesDailyN10[8] = sharpesDailyN3[1];
            sharpesDailyN10[9] = sharpesDailyN3[2];
            n10Labels.add("D0.GEM듀얼모멘텀");
            n10Labels.add("D1.+SMA210추세필터");
            n10Labels.add("D2.+변동성타게팅10%");
            n10SharpesDaily.add(sharpesDailyN10[7]);
            n10SharpesDaily.add(sharpesDailyN10[8]);
            n10SharpesDaily.add(sharpesDailyN10[9]);
            n10OosReturnsPct.add(d0.totalReturn() * 100);
            n10OosReturnsPct.add(d1.totalReturn() * 100);
            n10OosReturnsPct.add(d2.totalReturn() * 100);

            d0DsrN10 = performanceCalculator.deflatedSharpeAcrossFamilies(d0.dailyReturns(), sharpesDailyN10);
            d1DsrN10 = performanceCalculator.deflatedSharpeAcrossFamilies(d1.dailyReturns(), sharpesDailyN10);
            d2DsrN10 = performanceCalculator.deflatedSharpeAcrossFamilies(d2.dailyReturns(), sharpesDailyN10);

            assertFalse(Double.isNaN(d0DsrN10), "D0 DSR(N=10)이 NaN");
            assertFalse(Double.isNaN(d1DsrN10), "D1 DSR(N=10)이 NaN");
            assertFalse(Double.isNaN(d2DsrN10), "D2 DSR(N=10)이 NaN");

            // ── 7) N=8 (재계산 전용, ADR-12 제안 검토용 병기) — 선택 후보 풀을 "OOS수익 양수 계열만"으로
            //      한정: A,B는 OOS 수익이 음수라 애초에 "선택될 수 없는" 계열이므로 제외하고
            //      C0,C1,C2,C3,C4,D0,D1,D2(N=8)만으로 DSR을 다시 계산한다. 새 백테스트/전략/파라미터
            //      변경 없음 — 이미 위에서 계산된 priorFamilies/d0/d1/d2의 동일 일별수익률·샤프를
            //      다른 N 풀 정의로 재조합만 한다.
            double[] sharpesDailyN8 = new double[8];
            for (int i = 0; i < 5; i++) { // C0~C4 = priorFamilies[2..6] (A=0, B=1 제외)
                sharpesDailyN8[i] = priorFamilies.get(i + 2).result().sharpe() / Math.sqrt(TRADING_DAYS_PER_YEAR);
                n8Labels.add(priorFamilies.get(i + 2).label());
                n8SharpesDaily.add(sharpesDailyN8[i]);
                n8OosReturnsPct.add(priorFamilies.get(i + 2).result().totalReturn() * 100);
            }
            sharpesDailyN8[5] = sharpesDailyN3[0];
            sharpesDailyN8[6] = sharpesDailyN3[1];
            sharpesDailyN8[7] = sharpesDailyN3[2];
            n8Labels.add("D0.GEM듀얼모멘텀");
            n8Labels.add("D1.+SMA210추세필터");
            n8Labels.add("D2.+변동성타게팅10%");
            n8SharpesDaily.add(sharpesDailyN8[5]);
            n8SharpesDaily.add(sharpesDailyN8[6]);
            n8SharpesDaily.add(sharpesDailyN8[7]);
            n8OosReturnsPct.add(d0.totalReturn() * 100);
            n8OosReturnsPct.add(d1.totalReturn() * 100);
            n8OosReturnsPct.add(d2.totalReturn() * 100);

            c0DsrN8 = performanceCalculator.deflatedSharpeAcrossFamilies(priorFamilies.get(2).result().dailyReturns(), sharpesDailyN8);
            c1DsrN8 = performanceCalculator.deflatedSharpeAcrossFamilies(priorFamilies.get(3).result().dailyReturns(), sharpesDailyN8);
            c2DsrN8 = performanceCalculator.deflatedSharpeAcrossFamilies(priorFamilies.get(4).result().dailyReturns(), sharpesDailyN8);
            c3DsrN8 = performanceCalculator.deflatedSharpeAcrossFamilies(priorFamilies.get(5).result().dailyReturns(), sharpesDailyN8);
            c4DsrN8 = performanceCalculator.deflatedSharpeAcrossFamilies(priorFamilies.get(6).result().dailyReturns(), sharpesDailyN8);
            d0DsrN8 = performanceCalculator.deflatedSharpeAcrossFamilies(d0.dailyReturns(), sharpesDailyN8);
            d1DsrN8 = performanceCalculator.deflatedSharpeAcrossFamilies(d1.dailyReturns(), sharpesDailyN8);
            d2DsrN8 = performanceCalculator.deflatedSharpeAcrossFamilies(d2.dailyReturns(), sharpesDailyN8);

            assertFalse(Double.isNaN(c0DsrN8), "C0 DSR(N=8)이 NaN");
            assertFalse(Double.isNaN(c1DsrN8), "C1 DSR(N=8)이 NaN");
            assertFalse(Double.isNaN(c2DsrN8), "C2 DSR(N=8)이 NaN");
            assertFalse(Double.isNaN(c3DsrN8), "C3 DSR(N=8)이 NaN");
            assertFalse(Double.isNaN(c4DsrN8), "C4 DSR(N=8)이 NaN");
            assertFalse(Double.isNaN(d0DsrN8), "D0 DSR(N=8)이 NaN");
            assertFalse(Double.isNaN(d1DsrN8), "D1 DSR(N=8)이 NaN");
            assertFalse(Double.isNaN(d2DsrN8), "D2 DSR(N=8)이 NaN");
        }

        printReport(dataStart, dataEndCommon, oosStart, oosEnd, d0, d1, d2, d2StockTax,
                kodexBuyHold, equalWeightBuyHold, d0DsrN3, d1DsrN3, d2DsrN3,
                familyDataAvailable, n10Labels, n10SharpesDaily, n10OosReturnsPct,
                d0DsrN10, d1DsrN10, d2DsrN10,
                n8Labels, n8OosReturnsPct,
                c0DsrN8, c1DsrN8, c2DsrN8, c3DsrN8, c4DsrN8, d0DsrN8, d1DsrN8, d2DsrN8);
    }

    // ───────────────────────────── D계열 시뮬레이션 코어 ─────────────────────────────

    /**
     * 21거래일마다 리밸런싱하는 단일자산(D0/D1) 또는 단일자산+현금(D2) 배분을 시뮬레이션한다.
     * 리밸런싱일 i의 배분 결정은 오직 인덱스 i-1(직전 확정 종가)까지의 데이터만 사용한다
     * (룩어헤드 방지). 배분이 바뀌면 그 리밸런싱일의 수익률에 회전비용을 합성해 반영한다.
     */
    private SimResult simulate(Variant variant, Map<String, List<BigDecimal>> closesByAsset, int n, CostModel cost) {
        List<String> assets = new ArrayList<>(closesByAsset.keySet());

        List<Double> dailyReturns = new ArrayList<>();
        Map<String, Double> currentWeights = new LinkedHashMap<>();
        currentWeights.put(CASH, 1.0);

        int tradeCount = 0;
        Map<String, Double> weightDaysSum = new LinkedHashMap<>();
        for (String a : assets) {
            weightDaysSum.put(a, 0.0);
        }
        weightDaysSum.put(CASH, 0.0);

        double equity = 1.0;

        for (int i = WARMUP; i < n; i++) {
            boolean isRebalanceDay = (i - WARMUP) % REBALANCE_INTERVAL == 0;
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
            for (Map.Entry<String, Double> we : currentWeights.entrySet()) {
                weightDaysSum.merge(we.getKey(), we.getValue(), Double::sum);
            }
        }

        int oosDays = n - WARMUP;
        Map<String, Double> timeInPositionPct = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : weightDaysSum.entrySet()) {
            timeInPositionPct.put(e.getKey(), oosDays == 0 ? 0.0 : e.getValue() / oosDays * 100.0);
        }

        BigDecimal initialCapital = BigDecimal.ONE;
        BigDecimal finalEquity = BigDecimal.valueOf(equity);
        BacktestResult r = performanceCalculator.calculate(dailyReturns, initialCapital, finalEquity, tradeCount, 1, 0.0);

        return new SimResult(dailyReturns, r.totalReturn(), r.cagr(), r.mdd(), r.sharpe(), tradeCount, timeInPositionPct);
    }

    /** i-1(직전 확정 종가) 기준으로 목표 배분을 결정한다. */
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
            // 절대모멘텀 실패 — 상대적으로 제일 나은 자산조차 252일 수익률이 마이너스면 현금
            weights.put(CASH, 1.0);
            return weights;
        }

        if (variant == Variant.D0) {
            weights.put(best, 1.0);
            return weights;
        }

        // D1/D2 — Faber 10개월(210거래일) 추세필터
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

        // D2 — 변동성 타게팅(목표 연변동성 10%), 남는 비중은 현금
        List<BigDecimal> closesUpToAsOf = closesByAsset.get(best).subList(0, asOf + 1);
        double fraction = VolTargetMath.fraction(closesUpToAsOf, D2_TARGET_VOL);
        weights.put(best, fraction);
        if (fraction < 1.0) {
            weights.put(CASH, 1.0 - fraction);
        }
        return weights;
    }

    /** asOf 기준 MOMENTUM_LOOKBACK거래일 추세수익률: close[asOf]/close[asOf-lookback] - 1. */
    private double momentumReturn(List<BigDecimal> closes, int asOf) {
        BigDecimal now = closes.get(asOf);
        BigDecimal past = closes.get(asOf - MOMENTUM_LOOKBACK);
        if (past.signum() == 0) {
            return 0.0;
        }
        return now.subtract(past).divide(past, 12, RoundingMode.HALF_UP).doubleValue();
    }

    /** asOf를 마지막 점으로 하는 lookback거래일 단순이동평균(종가 기준). */
    private double sma(List<BigDecimal> closes, int asOf, int lookback) {
        double sum = 0.0;
        for (int i = asOf - lookback + 1; i <= asOf; i++) {
            sum += closes.get(i).doubleValue();
        }
        return sum / lookback;
    }

    /** 오늘(day i) 하루치 시장수익률 — currentWeights 각 자산의 close[i]/close[i-1]-1 가중합. 현금 기여는 0. */
    private double dayReturn(Map<String, Double> weights, Map<String, List<BigDecimal>> closesByAsset, int i) {
        double total = 0.0;
        for (Map.Entry<String, Double> e : weights.entrySet()) {
            String asset = e.getKey();
            if (CASH.equals(asset)) {
                continue; // 현금 수익률 0% 가정
            }
            List<BigDecimal> closes = closesByAsset.get(asset);
            BigDecimal prev = closes.get(i - 1);
            BigDecimal curr = closes.get(i);
            double r = prev.signum() == 0 ? 0.0 : curr.subtract(prev).divide(prev, 12, RoundingMode.HALF_UP).doubleValue();
            total += e.getValue() * r;
        }
        return total;
    }

    /**
     * 배분 변경에 따른 왕복비용 — 늘어난 비중은 매수비용(수수료+슬리피지), 줄어든 비중은
     * 매도비용(수수료+거래세+슬리피지)을 적용한다. 현금 비중 자체에는 비용이 붙지 않는다.
     */
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

    // ───────────────────────────── 정렬/기준선 유틸 ─────────────────────────────

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
                    throw new IllegalStateException("공통 거래일 " + d + "에 " + entry.getKey() + " 종가가 없음 — commonDates 계산 오류");
                }
                aligned.add(close);
            }
            result.put(entry.getKey(), aligned);
        }
        return result;
    }

    /** 단순보유(buy&hold) 기준선 — 워밍업 종료 시점 종가로 진입, OOS 마지막 종가로 청산. */
    private double buyHoldReturn(List<BigDecimal> closes, int warmup, int n) {
        BigDecimal entry = closes.get(warmup - 1);
        BigDecimal exit = closes.get(n - 1);
        if (entry.signum() == 0) {
            return 0.0;
        }
        return exit.subtract(entry).divide(entry, 12, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * 4종목 동일비중 buy&hold 기준선 — 워밍업 종료 시점에 25%씩 배분한 뒤 리밸런싱 없이
     * (기존 코드베이스의 buy&hold 기준선은 재조정을 하지 않는 정적 방식이므로 동일 관례를 따름)
     * OOS 마지막 날까지 그대로 들고 간다.
     */
    private double equalWeightBuyHoldReturn(Map<String, List<BigDecimal>> closesByAsset, int warmup, int n) {
        double value = 0.0;
        for (List<BigDecimal> closes : closesByAsset.values()) {
            double entry = closes.get(warmup - 1).doubleValue();
            double exit = closes.get(n - 1).doubleValue();
            value += 0.25 * (entry == 0.0 ? 1.0 : exit / entry);
        }
        return value - 1.0;
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

    private boolean familyDataAvailable(Path dataDir) {
        for (String code : FAMILY_SYMBOLS.keySet()) {
            if (!Files.isRegularFile(dataDir.resolve(code + ".csv"))) {
                return false;
            }
        }
        return Files.isRegularFile(dataDir.resolve(INVERSE_SYMBOL + ".csv"));
    }

    // ───────────────────────────── N=10용 — A,B,C0,C1,C2,C3,C4 재현(GateDsrRobustnessTest/RealDataC4InverseExperimentTest 패턴 그대로 복제) ─────────────────────────────

    private record FamilyRun(String label, PortfolioBacktestRunner.PortfolioResult result) {
    }

    private List<FamilyRun> runPriorSevenFamilies(Path dataDir) {
        Map<String, List<Candle>> candlesBySymbol = new LinkedHashMap<>();
        for (String code : FAMILY_SYMBOLS.keySet()) {
            candlesBySymbol.put(code, loader.load(dataDir.resolve(code + ".csv")));
        }
        List<Candle> inverseCandles = loader.load(dataDir.resolve(INVERSE_SYMBOL + ".csv"));

        BigDecimal totalCapital = SLEEVE_CAPITAL.multiply(BigDecimal.valueOf(candlesBySymbol.size()));
        List<Candle> kodexCandles = candlesBySymbol.get(FAMILY_BASELINE_SYMBOL);
        Map<LocalDate, Boolean> regimeByDate = MarketRegime.compute(kodexCandles);

        List<FamilyRun> runs = new ArrayList<>();

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
            runs.add(new FamilyRun(def.label(), result));
        }

        // C3
        WalkForwardRunner c3WalkForwardRunner = new WalkForwardRunner(CostModel.defaults());
        Function<Integer, BacktestStrategy> c3Factory = n -> new VolatilityTargetingStrategy(
                new RegimeFilteredStrategy(new TimeSeriesMomentumStrategy(n), regimeByDate), C_TARGET_VOL);
        PortfolioBacktestRunner.SleeveRunner c3SleeveRunner = (candles, sleeveCapital) -> c3WalkForwardRunner.run(
                candles, N_CANDIDATES, c3Factory, FAMILY_TRAIN_SIZE, FAMILY_TEST_SIZE, MOMENTUM_WARMUP, sleeveCapital);
        PortfolioBacktestRunner.PortfolioResult c3Result =
                portfolioRunner.run(candlesBySymbol, c3SleeveRunner, totalCapital, FAMILY_TRAIN_SIZE, FAMILY_TEST_SIZE);
        runs.add(new FamilyRun("C3.+국면필터+변동성타게팅", c3Result));

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
        runs.add(new FamilyRun("C4.+인버스국면롱숏(ADR-8)", c4Result));

        return runs;
    }

    // ───────────────────────────── 리포트 출력 ─────────────────────────────

    private void printReport(LocalDate dataStart, LocalDate dataEndCommon, LocalDate oosStart, LocalDate oosEnd,
                              SimResult d0, SimResult d1, SimResult d2, SimResult d2StockTax,
                              double kodexBuyHold, double equalWeightBuyHold,
                              double d0DsrN3, double d1DsrN3, double d2DsrN3,
                              boolean familyDataAvailable, List<String> n10Labels, List<Double> n10SharpesDaily,
                              List<Double> n10OosReturnsPct,
                              Double d0DsrN10, Double d1DsrN10, Double d2DsrN10,
                              List<String> n8Labels, List<Double> n8OosReturnsPct,
                              Double c0DsrN8, Double c1DsrN8, Double c2DsrN8, Double c3DsrN8, Double c4DsrN8,
                              Double d0DsrN8, Double d1DsrN8, Double d2DsrN8) {
        System.out.println();
        System.out.println("=== D계열(듀얼 모멘텀, ADR-11) 실험 — GEM/SMA210/변동성타게팅 3변형, 게이트① 평가 ===");
        System.out.println("(유니버스=" + D_SYMBOLS + " + 현금(수익률 0% 가정), 데이터 범위=" + dataStart + " ~ " + dataEndCommon
                + ", 워밍업=" + WARMUP + "거래일, OOS구간=" + oosStart + " ~ " + oosEnd
                + ", 리밸런싱주기=" + REBALANCE_INTERVAL + "거래일, 모멘텀룩백=" + MOMENTUM_LOOKBACK
                + "거래일, SMA룩백=" + SMA_LOOKBACK + "거래일, D2목표변동성=" + (D2_TARGET_VOL * 100) + "%)");
        if (!dataEndCommon.isAfter(LocalDate.of(2026, 9, 30)) && dataEndCommon.isBefore(LocalDate.of(2026, 9, 1))) {
            System.out.println("[편차 안내] 스펙은 OOS를 2026-09까지 요구했으나, 4종목 공통(교집합) 거래일 기준 데이터 종료일이 "
                    + dataEndCommon + "까지만 확보됨(069500.csv가 다른 종목보다 며칠 앞서 끝남) — 실제 OOS는 "
                    + oosStart + " ~ " + oosEnd + ".");
        }

        System.out.println();
        System.out.println("--- [표 1] D0/D1/D2 OOS 성과 (비용모델=ETF 전용: 수수료 0.015%×2, 매도세 0%, 슬리피지 5bp) ---");
        System.out.printf("%-6s | %10s | %10s | %8s | %8s | %6s%n",
                "변형", "총수익률", "CAGR", "MDD", "Sharpe", "거래수");
        System.out.println("-".repeat(65));
        printSimRow("D0", d0);
        printSimRow("D1", d1);
        printSimRow("D2", d2);

        System.out.println();
        System.out.println("--- [표 1-부속] D2 비용 민감도 — 매도세 0%(ETF) vs 0.20%(주식형 가정) ---");
        System.out.printf("%-20s | %10s | %10s | %8s | %8s | %6s%n",
                "D2 비용가정", "총수익률", "CAGR", "MDD", "Sharpe", "거래수");
        System.out.println("-".repeat(80));
        printSimRow("D2(ETF,매도세0%)", d2);
        printSimRow("D2(주식형,매도세0.2%)", d2StockTax);

        System.out.println();
        System.out.println("--- [표 2] 변형별 보유일 비중(%) — 자산별/현금 (OOS 구간 가중평균, D2는 부분현금 포함) ---");
        System.out.printf("%-6s", "변형");
        List<String> posKeys = new ArrayList<>(D_SYMBOLS.keySet());
        posKeys.add(CASH);
        for (String k : posKeys) {
            System.out.printf(" | %14s", D_SYMBOLS.getOrDefault(k, k));
        }
        System.out.println();
        System.out.println("-".repeat(20 + posKeys.size() * 17));
        printPositionRow("D0", d0, posKeys);
        printPositionRow("D1", d1, posKeys);
        printPositionRow("D2", d2, posKeys);

        System.out.println();
        System.out.println("--- [기준선] 동일 OOS 구간, 재조정 없는 정적(static) buy&hold ---");
        System.out.printf("  KODEX200(069500) buy&hold        : %.2f%%%n", kodexBuyHold * 100);
        System.out.printf("  4종목 동일비중(25%%×4) buy&hold    : %.2f%%%n", equalWeightBuyHold * 100);

        System.out.println();
        System.out.println("--- [DSR ①] N=3 (D0/D1/D2 계열 내부만 비교, 참고치) ---");
        System.out.printf("  D0 DSR(N=3): %.3f    D1 DSR(N=3): %.3f    D2 DSR(N=3): %.3f%n", d0DsrN3, d1DsrN3, d2DsrN3);

        System.out.println();
        System.out.println("--- [DSR ②] N=10 보수적 — 전체 연구계열(A,B,C0,C1,C2,C3,C4,D0,D1,D2) ---");
        System.out.println("[방법론 주의] A~C4는 walk-forward(train=252/test=63) OOS를 이어붙인 시계열이고, D0~D2는 이 테스트");
        System.out.println("단독의 단일·전체이력 OOS 구간(워밍업 이후 전체, 리밸런싱 walk-forward 아님)이다 — 서로 다른 평가");
        System.out.println("창을 하나의 N=10 풀에 섞어 비교하는 것이므로 완전히 동질적인 비교는 아니라는 점에 유의할 것.");
        if (!familyDataAvailable) {
            System.out.println("  [스킵] A,B,C0~C4 재현에 필요한 5종목+인버스(114800) 데이터가 없어 N=10 DSR을 계산하지 못함.");
        } else {
            System.out.printf("%-24s | %10s | %10s%n", "전략계열", "OOS수익률", "일별Sharpe");
            System.out.println("-".repeat(50));
            for (int i = 0; i < n10Labels.size(); i++) {
                System.out.printf("%-24s | %9.2f%% | %10.5f%n", n10Labels.get(i), n10OosReturnsPct.get(i), n10SharpesDaily.get(i));
            }
            System.out.println();
            System.out.printf("  D0 DSR(N=10) : %.3f%n", d0DsrN10);
            System.out.printf("  D1 DSR(N=10) : %.3f%n", d1DsrN10);
            System.out.printf("  D2 DSR(N=10) : %.3f%n", d2DsrN10);
        }

        System.out.println();
        System.out.println("--- [DSR ③] 선택풀 N=8 (음수 수익 A/B 제외) — ADR-12 제안 검토용 병기 ---");
        System.out.println("[방법론 캐비엇] 선택풀 한정은 ADR-12 제안 검토용 병기 수치 — 공식 게이트 기준은 ADR 채택 전까지");
        System.out.println("현행(전체 N=10) 유지. 재계산 전용(새 백테스트/전략/파라미터 변경 없음) — A,B는 OOS 수익이 음수라");
        System.out.println("애초에 \"선택될 수 없는\" 계열이므로 선택 후보 풀에서 제외하고, C0,C1,C2,C3,C4,D0,D1,D2(N=8)만으로");
        System.out.println("DSR을 다시 계산한 것이다.");
        if (!familyDataAvailable) {
            System.out.println("  [스킵] A,B,C0~C4 재현에 필요한 5종목+인버스(114800) 데이터가 없어 N=8 DSR을 계산하지 못함.");
        } else {
            System.out.printf("%-24s | %10s | %10s%n", "전략계열", "OOS수익률", "DSR(N=8)");
            System.out.println("-".repeat(50));
            Double[] n8Dsrs = {c0DsrN8, c1DsrN8, c2DsrN8, c3DsrN8, c4DsrN8, d0DsrN8, d1DsrN8, d2DsrN8};
            for (int i = 0; i < n8Labels.size(); i++) {
                System.out.printf("%-24s | %9.2f%% | %10.3f%n", n8Labels.get(i), n8OosReturnsPct.get(i), n8Dsrs[i]);
            }
            System.out.println();
            System.out.println("  선택풀 N=8 기준 게이트① DSR 항목(교정DSR(N=8)>0.95) 재판정 — 다른 두 기준(OOS수익>0, MDD<15%)");
            System.out.println("  판정은 기존 수치를 그대로 병기함:");
            printN8DsrGateLine("C3", c3DsrN8, 0.250);
            printN8DsrGateLine("D1", d1DsrN8, 0.408);
            printN8DsrGateLine("D2", d2DsrN8, 0.210);
        }

        System.out.println();
        System.out.println("--- [최종 판정] 게이트① = OOS수익>0 AND 교정DSR(N=10)>0.95 AND MDD<15% ---");
        printGateVerdict("D0", d0, d0DsrN10, familyDataAvailable);
        printGateVerdict("D1", d1, d1DsrN10, familyDataAvailable);
        printGateVerdict("D2", d2, d2DsrN10, familyDataAvailable);
    }

    private void printSimRow(String label, SimResult r) {
        System.out.printf("%-6s | %9.2f%% | %9.2f%% | %7.2f%% | %8.3f | %6d%n",
                label, r.totalReturn() * 100, r.cagr() * 100, r.mdd() * 100, r.sharpeAnnualized(), r.tradeCount());
    }

    private void printPositionRow(String label, SimResult r, List<String> posKeys) {
        System.out.printf("%-6s", label);
        for (String k : posKeys) {
            System.out.printf(" | %13.2f%%", r.timeInPositionPct().getOrDefault(k, 0.0));
        }
        System.out.println();
    }

    /**
     * 선택풀 N=8 기준 게이트①의 DSR 항목만 재판정하는 행 — MDD는 기존(전체 N) 수치를 그대로
     * 병기한다(재계산 아님). OOS수익>0 여부는 N=8 풀 정의상(음수 수익 계열 제외) 자명하게 O.
     */
    private void printN8DsrGateLine(String label, double dsrN8, double existingMdd) {
        boolean dsrPass = dsrN8 > GATE_MIN_DSR;
        System.out.printf("  %-4s N=8 게이트①-DSR항목 = %s  (OOS수익>0: O(병기, 선택풀 정의상 자명), " +
                        "교정DSR(N=8)>0.95: %s(%.3f), MDD<15%%: X(병기, 기존수치 %.1f%%))%n",
                label, dsrPass ? "PASS" : "FAIL", dsrPass ? "O" : "X", dsrN8, existingMdd * 100);
    }

    private void printGateVerdict(String label, SimResult r, Double dsrN10, boolean familyDataAvailable) {
        boolean oosPositive = r.totalReturn() > 0;
        boolean mddPass = r.mdd() < GATE_MAX_MDD;
        if (!familyDataAvailable || dsrN10 == null) {
            System.out.printf("  %-4s 게이트① = 판정불가(N=10 DSR 미계산)  (OOS수익>0: %s, MDD<15%%: %s(%.2f%%))%n",
                    label, oosPositive ? "O" : "X", mddPass ? "O" : "X", r.mdd() * 100);
            return;
        }
        boolean dsrPass = dsrN10 > GATE_MIN_DSR;
        boolean gate1Pass = oosPositive && dsrPass && mddPass;
        System.out.printf("  %-4s 게이트① = %s  (OOS수익>0: %s, 교정DSR(N=10)>0.95: %s(%.3f), MDD<15%%: %s(%.2f%%))%n",
                label, gate1Pass ? "PASS" : "FAIL",
                oosPositive ? "O" : "X", dsrPass ? "O" : "X", dsrN10, mddPass ? "O" : "X", r.mdd() * 100);
    }
}
