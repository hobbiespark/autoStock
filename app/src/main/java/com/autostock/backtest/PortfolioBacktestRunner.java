package com.autostock.backtest;

import com.autostock.common.event.Candle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 여러 종목을 "슬리브(sleeve)" 방식으로 각각 독립적으로 walk-forward 백테스트한 뒤,
 * 슬리브별 OOS(out-of-sample) 자산곡선을 날짜로 합쳐 하나의 포트폴리오 자산곡선을 만드는 러너.
 *
 * <h2>왜 슬리브 방식인가 — 의도적으로 보수적인 설계</h2>
 * 초기자본을 종목 수만큼 균등 분할해 각 종목에 그대로 고정 배정하고, 그 뒤로는 종목 간
 * 자금을 옮기지 않는다(교차 자금이동 없음). 어떤 종목의 슬리브가 손실을 봐도 다른 슬리브의
 * 여유 자금을 끌어다 쓰지 않고, 어떤 슬리브가 이익을 내도 다른 슬리브를 증액하지 않는다
 * (리밸런싱 없음). 실전에서는 손실난 슬리브의 자금을 다른 슬리브로 옮기는 등 더 적극적인
 * 자금 운용이 가능하므로, 이 방식은 실제 가능한 최선이 아니라 <b>하한(보수적 추정)</b>이다.
 * "종목을 여러 개로 나누기만 해도 분산 효과가 있는가"를 최대한 단순하고 왜곡 없이 확인하려는
 * 목적이므로, 사이징/리밸런싱 최적화는 의도적으로 범위 밖에 둔다(PLAN 과최적화 방지 원칙과
 * 같은 맥락 — 단순한 것부터 검증하고, 더 정교한 자금 운용은 그 다음 단계다).
 *
 * <h2>왜 여기서 OOS 날짜를 다시 계산하는가</h2>
 * {@link WalkForwardResult}는 OOS 일별 수익률만 담고 있고 날짜를 담지 않는다(대부분의
 * 호출부가 날짜 없이 성과지표만 쓰면 충분했기 때문). 하지만 포트폴리오 합산에는 "이
 * 수익률이 어느 날짜의 것인가"가 필수다. {@link WalkForwardRunner}/{@link BacktestRunner}의
 * 창 분할 로직을 보면(그리고 {@code RealDataWalkForwardTest}의 buy&amp;hold 기준선 계산에서
 * 이미 검증됐듯) 워밍업 구간은 성과 집계에서 제외되므로, 이어붙인 OOS 수익률은 정확히
 * {@code candles[trainSize .. trainSize + windowCount*testSize)} 구간(연속 슬라이스)의
 * 날짜와 1:1로 대응한다. 이 사실을 이용해 {@link WalkForwardResult}나 {@link BacktestRunner}를
 * 건드리지 않고도(다른 호출부에 영향 없이) 같은 trainSize/testSize/캔들 목록으로부터
 * 날짜를 독립적으로 재계산한다 — 호출자가 전달한 {@code trainSize}/{@code testSize}가
 * sleeveRunner 내부에서 실제로 쓰는 값과 다르면 날짜와 수익률의 길이가 어긋나므로, 그
 * 경우 즉시 예외를 던져 조용한 오정렬을 막는다.
 *
 * <h2>합산 규칙 — 합집합 날짜 + carry-forward + 가장 늦은 공통 시작일</h2>
 * <ul>
 *   <li><b>합집합(union) 날짜를 쓴다</b>: 종목마다 휴장일(상장폐지, 개별 이슈 등)이 다를 수
 *       있으므로 모든 슬리브 날짜의 합집합을 포트폴리오의 타임라인으로 삼는다.</li>
 *   <li><b>날짜 불일치는 이월(carry-forward)한다</b>: 어떤 날짜에 특정 슬리브의 그날 자산가치가
 *       없으면(그 종목만 휴장 등) 그 슬리브가 마지막으로 관측된 자산가치를 그대로 유지한
 *       것으로 본다 — 그날 그 종목에 아무 일도 없었다고 보수적으로 가정하는 것이다.</li>
 *   <li><b>가장 늦은 공통 시작일 이후만 합산한다</b>: 종목마다 데이터 길이가 달라 슬리브의
 *       OOS 시작일이 다를 수 있다. 만약 어떤 슬리브가 다른 슬리브보다 먼저 시작한 구간까지
 *       합산에 포함시키면, 그 구간은 사실 "일부 슬리브만 투자된" 상태라서 정말로 "5종목에
 *       분산투자했다"는 전제와 맞지 않는다(공정성 문제). 그래서 모든 슬리브가 이미 시작한
 *       시점, 즉 슬리브별 시작일 중 가장 늦은 날짜(commonStart) 이후만 포트폴리오 성과에
 *       반영한다. 그 이전 구간의 개별 슬리브 성과는 {@link SleeveResult#walkForwardResult()}로
 *       여전히 확인할 수 있다(개별 종목 지표는 원래 그대로 유지).</li>
 * </ul>
 */
public final class PortfolioBacktestRunner {

    private final PerformanceCalculator performanceCalculator = new PerformanceCalculator();

    /**
     * 종목 하나(캔들 목록 + 그 종목에 배정된 슬리브 자본)를 받아 walk-forward 결과를 돌려주는
     * 함수. 호출자가 기존 {@link WalkForwardRunner#run}을 그대로 람다로 감싸 넘기면 된다 —
     * 파라미터 후보/전략 팩토리/trainSize/testSize/워밍업 등은 호출자가 캡처해서 쓴다.
     */
    @FunctionalInterface
    public interface SleeveRunner {
        WalkForwardResult<?> run(List<Candle> candles, BigDecimal sleeveCapital);
    }

    /**
     * 슬리브(종목) 하나의 개별 결과.
     *
     * @param symbol            종목코드
     * @param walkForwardResult 이 종목에 대해 독립적으로 실행한 walk-forward 결과(선택 파라미터,
     *                          OOS 성과 등 전부 포함)
     * @param oosDates          walkForwardResult.oosResult().dailyReturns()와 1:1 대응하는 날짜 목록
     * @param sleeveCapital     이 종목에 배정된 초기자본(전체 자본 / 종목 수)
     */
    public record SleeveResult(
            String symbol,
            WalkForwardResult<?> walkForwardResult,
            List<LocalDate> oosDates,
            BigDecimal sleeveCapital
    ) {
    }

    /**
     * 포트폴리오 전체 합산 결과.
     *
     * @param dailyReturns  포트폴리오 일별수익률(가장 늦은 공통 시작일부터 합집합 날짜 순).
     *                      인덱스 0(첫날)은 "공통 시작일 직전, 아직 어떤 수익률도 반영되지
     *                      않은 슬리브 자본 총합"을 기준선으로 계산한 실제 수익률이다 —
     *                      {@link BacktestRunner}가 첫날 수익률을 initialCapital 대비로
     *                      계산하는 것과 같은 방식(하드코딩된 0이 아니다).
     * @param totalReturn   포트폴리오 총수익률
     * @param cagr          포트폴리오 연환산복리수익률
     * @param mdd           포트폴리오 최대낙폭
     * @param sharpe        포트폴리오 연율화 샤프비율
     * @param sleeveResults 종목별 개별 결과 목록(입력 Map의 순회 순서를 유지)
     */
    public record PortfolioResult(
            List<Double> dailyReturns,
            double totalReturn,
            double cagr,
            double mdd,
            double sharpe,
            List<SleeveResult> sleeveResults
    ) {
    }

    /**
     * 포트폴리오 백테스트를 실행한다.
     *
     * @param candlesBySymbol     종목코드 → 시간순 정렬된 전체 캔들 목록
     * @param sleeveRunner        종목 하나를 독립 실행하는 함수(기존 WalkForwardRunner 재사용 권장)
     * @param totalInitialCapital 포트폴리오 전체 초기자본(종목 수로 균등 분할됨)
     * @param trainSize           sleeveRunner 내부에서 실제로 쓰는 것과 반드시 같은 훈련 구간 길이
     *                            (OOS 날짜 재계산에 필요 — 클래스 설명의 "왜 여기서 OOS 날짜를
     *                            다시 계산하는가" 참고)
     * @param testSize            sleeveRunner 내부에서 실제로 쓰는 것과 반드시 같은 테스트 구간 길이
     */
    public PortfolioResult run(Map<String, List<Candle>> candlesBySymbol, SleeveRunner sleeveRunner,
                                BigDecimal totalInitialCapital, int trainSize, int testSize) {
        if (candlesBySymbol.isEmpty()) {
            throw new IllegalArgumentException("종목 목록이 비어 있음");
        }

        int symbolCount = candlesBySymbol.size();
        // 균등 분할 — 슬리브 방식의 핵심 규칙(클래스 설명 참고).
        BigDecimal sleeveCapital = totalInitialCapital.divide(BigDecimal.valueOf(symbolCount), 6, RoundingMode.HALF_UP);

        List<SleeveResult> sleeveResults = new ArrayList<>();
        // 종목 → (날짜 → 그날 종가 기준 슬리브 평가자산). LinkedHashMap이라 날짜 삽입 순서(=시간순)가 유지된다.
        Map<String, Map<LocalDate, BigDecimal>> equityBySymbol = new LinkedHashMap<>();

        for (Map.Entry<String, List<Candle>> entry : candlesBySymbol.entrySet()) {
            String symbol = entry.getKey();
            List<Candle> candles = entry.getValue();

            WalkForwardResult<?> wfr = sleeveRunner.run(candles, sleeveCapital);
            List<LocalDate> dates = oosDates(candles, trainSize, testSize);
            List<Double> returns = wfr.oosResult().dailyReturns();
            if (dates.size() != returns.size()) {
                throw new IllegalStateException("슬리브 '" + symbol + "'의 OOS 날짜 수(" + dates.size()
                        + ")와 수익률 수(" + returns.size() + ")가 다름 — sleeveRunner가 이 메서드에 넘긴 "
                        + "trainSize=" + trainSize + "/testSize=" + testSize + "와 다른 값을 내부적으로 쓰고 "
                        + "있지 않은지 확인할 것");
            }

            Map<LocalDate, BigDecimal> equityByDate = new LinkedHashMap<>();
            BigDecimal equity = sleeveCapital;
            for (int i = 0; i < dates.size(); i++) {
                equity = equity.multiply(BigDecimal.valueOf(1 + returns.get(i)));
                equityByDate.put(dates.get(i), equity);
            }
            equityBySymbol.put(symbol, equityByDate);

            sleeveResults.add(new SleeveResult(symbol, wfr, dates, sleeveCapital));
        }

        // ── 가장 늦은 공통 시작일: 모든 슬리브가 "이미 투자를 시작한" 첫 시점 ──
        LocalDate commonStart = null;
        for (SleeveResult sr : sleeveResults) {
            if (sr.oosDates().isEmpty()) {
                continue;
            }
            LocalDate first = sr.oosDates().get(0);
            if (commonStart == null || first.isAfter(commonStart)) {
                commonStart = first;
            }
        }
        if (commonStart == null) {
            throw new IllegalStateException("모든 슬리브의 OOS 구간이 비어 있음 — walk-forward 창을 하나도 "
                    + "만들 만큼 데이터가 충분한 종목이 없음");
        }

        // ── 각 슬리브의 "commonStart 시작 직전" 기준 자산가치를 구한다 ──
        // 이 값은 이후 첫날 수익률을 계산하는 기준선(BacktestRunner가 initialCapital을 첫날
        // prevEquity로 쓰는 것과 같은 발상)이다. commonStart보다 앞선 관측치가 있으면(더 일찍
        // 시작한 슬리브) 그 마지막 관측치를 쓰고, 없으면(바로 이 슬리브가 commonStart 당일에
        // 처음 시작한 경우) 아직 어떤 수익률도 반영되지 않은 원래 슬리브 자본 그대로 쓴다.
        Map<String, BigDecimal> lastKnownEquity = new LinkedHashMap<>();
        BigDecimal rawStartTotal = BigDecimal.ZERO;
        for (Map.Entry<String, Map<LocalDate, BigDecimal>> e : equityBySymbol.entrySet()) {
            BigDecimal beforeStart = null;
            for (Map.Entry<LocalDate, BigDecimal> point : e.getValue().entrySet()) {
                if (point.getKey().isBefore(commonStart)) {
                    beforeStart = point.getValue();
                } else {
                    break; // 날짜순 삽입이므로 여기부터는 commonStart 이상
                }
            }
            if (beforeStart == null) {
                beforeStart = sleeveCapital; // 아직 첫 수익률이 반영되기 전 = 배정된 슬리브 자본 그대로
            }
            lastKnownEquity.put(e.getKey(), beforeStart);
            rawStartTotal = rawStartTotal.add(beforeStart);
        }

        // ── 합집합 날짜(공통 시작일 이후만) ──
        TreeSet<LocalDate> unionDates = new TreeSet<>();
        for (Map<LocalDate, BigDecimal> curve : equityBySymbol.values()) {
            for (LocalDate d : curve.keySet()) {
                if (!d.isBefore(commonStart)) {
                    unionDates.add(d);
                }
            }
        }

        List<Double> portfolioReturns = new ArrayList<>();
        BigDecimal prevTotal = rawStartTotal;
        BigDecimal finalTotal = rawStartTotal;

        for (LocalDate date : unionDates) {
            BigDecimal total = BigDecimal.ZERO;
            for (Map.Entry<String, Map<LocalDate, BigDecimal>> e : equityBySymbol.entrySet()) {
                String symbol = e.getKey();
                BigDecimal todayEquity = e.getValue().get(date);
                if (todayEquity != null) {
                    lastKnownEquity.put(symbol, todayEquity);
                }
                // 오늘 관측치가 없으면(그 종목만 휴장 등) 마지막으로 관측된 값을 그대로 이월한다 —
                // "그날 그 종목에는 아무 일도 없었다"는 보수적 가정.
                total = total.add(lastKnownEquity.get(symbol));
            }

            double r = prevTotal.signum() == 0
                    ? 0.0
                    : total.subtract(prevTotal).divide(prevTotal, 12, RoundingMode.HALF_UP).doubleValue();
            portfolioReturns.add(r);
            prevTotal = total;
            finalTotal = total;
        }

        int totalTradeCount = sumTradeCount(sleeveResults);
        BacktestResult combined = performanceCalculator.calculate(
                portfolioReturns, rawStartTotal, finalTotal, totalTradeCount, 1, 0.0);

        return new PortfolioResult(
                combined.dailyReturns(), combined.totalReturn(), combined.cagr(), combined.mdd(),
                combined.sharpe(), sleeveResults);
    }

    private int sumTradeCount(List<SleeveResult> sleeveResults) {
        int sum = 0;
        for (SleeveResult sr : sleeveResults) {
            sum += sr.walkForwardResult().oosResult().tradeCount();
        }
        return sum;
    }

    /**
     * WalkForwardRunner의 창 분할 로직상 이어붙인 OOS 수익률은 정확히
     * {@code candles[trainSize .. trainSize + windowCount*testSize)}의 날짜와 대응한다
     * (클래스 설명 "왜 여기서 OOS 날짜를 다시 계산하는가" 참고). RealDataWalkForwardTest/
     * RealDataStrategyComparisonTest의 buy&amp;hold 기준선 계산과 동일한 공식이다.
     */
    private List<LocalDate> oosDates(List<Candle> candles, int trainSize, int testSize) {
        int windows = windowCount(candles.size(), trainSize, testSize);
        int oosStart = trainSize;
        int oosEndExclusive = trainSize + windows * testSize;
        if (oosEndExclusive <= oosStart || oosEndExclusive > candles.size()) {
            return List.of();
        }
        List<LocalDate> dates = new ArrayList<>(oosEndExclusive - oosStart);
        for (Candle c : candles.subList(oosStart, oosEndExclusive)) {
            dates.add(c.date());
        }
        return dates;
    }

    /** WalkForwardRunner와 동일한 창 개수 계산 규칙. */
    private static int windowCount(int totalCandles, int trainSize, int testSize) {
        int count = 0;
        int start = 0;
        while (start + trainSize + testSize <= totalCandles) {
            count++;
            start += testSize;
        }
        return count;
    }
}
