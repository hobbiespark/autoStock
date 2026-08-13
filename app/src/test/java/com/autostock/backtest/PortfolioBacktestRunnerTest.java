package com.autostock.backtest;

import com.autostock.common.event.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PortfolioBacktestRunner 검증 — 합성 2종목 시나리오로 슬리브 합산 로직(분산 효과, 날짜
 * 불일치 carry-forward)이 규약대로 동작하는지 확인한다.
 *
 * <p>실제 {@link WalkForwardRunner}/전략을 쓰지 않고, {@link PortfolioBacktestRunner.SleeveRunner}를
 * "정해진 일별 수익률 시계열을 그대로 돌려주는" 가짜(fake)로 대체한다 — 이 테스트가 검증하려는
 * 것은 walk-forward 로직 자체가 아니라 "여러 슬리브의 OOS 결과를 날짜로 합치는 로직"이므로,
 * 입력(수익률 시계열)을 직접 통제할 수 있어야 계산이 맞는지 손으로 검증할 수 있다.
 */
class PortfolioBacktestRunnerTest {

    private final PerformanceCalculator performanceCalculator = new PerformanceCalculator();
    private final PortfolioBacktestRunner runner = new PortfolioBacktestRunner();

    @Test
    void 서로_반대로_움직이는_두_종목의_포트폴리오_MDD는_개별_MDD보다_작다() {
        int trainSize = 1;
        int testSize = 6;

        // A: 3일 상승(+5%) 후 3일 하락(-10%) — 후반부에 큰 낙폭
        List<Double> returnsA = List.of(0.05, 0.05, 0.05, -0.10, -0.10, -0.10);
        // B: A와 정반대 패턴(3일 하락 -10% 후 3일 상승 +5%) — 초반부에 큰 낙폭
        List<Double> returnsB = List.of(-0.10, -0.10, -0.10, 0.05, 0.05, 0.05);

        List<Candle> candlesA = syntheticCandles("A", LocalDate.of(2024, 1, 1), trainSize + testSize);
        List<Candle> candlesB = syntheticCandles("B", LocalDate.of(2024, 1, 1), trainSize + testSize);

        Map<String, List<Candle>> candlesBySymbol = new LinkedHashMap<>();
        candlesBySymbol.put("A", candlesA);
        candlesBySymbol.put("B", candlesB);

        PortfolioBacktestRunner.SleeveRunner sleeveRunner = fakeSleeveRunner(Map.of(
                "A", returnsA,
                "B", returnsB
        ));

        BigDecimal totalCapital = new BigDecimal("2000000");
        PortfolioBacktestRunner.PortfolioResult result =
                runner.run(candlesBySymbol, sleeveRunner, totalCapital, trainSize, testSize);

        // 개별 슬리브 MDD를 별도로 계산(같은 PerformanceCalculator로, 슬리브 자본과 무관하게 비율만 봄)
        double mddA = performanceCalculator.mdd(returnsA);
        double mddB = performanceCalculator.mdd(returnsB);

        assertTrue(result.mdd() < mddA,
                "포트폴리오 MDD(" + result.mdd() + ")는 A 단독 MDD(" + mddA + ")보다 작아야 함(분산 효과)");
        assertTrue(result.mdd() < mddB,
                "포트폴리오 MDD(" + result.mdd() + ")는 B 단독 MDD(" + mddB + ")보다 작아야 함(분산 효과)");

        // 개별 슬리브 결과도 그대로 노출되는지 확인
        assertEquals(2, result.sleeveResults().size());
    }

    @Test
    void 종목별_휴장으로_날짜가_어긋나면_마지막_관측치를_이월한다() {
        int trainSize = 2;
        int testSize = 5;

        // A: 훈련 2봉(1/1,1/2) + OOS 5봉 연속(1/3~1/7)
        List<Candle> candlesA = syntheticCandles("A", LocalDate.of(2024, 1, 1), trainSize + testSize);
        // B: 훈련 2봉(1/1,1/2) + OOS 5봉인데 1/5을 건너뛰고 1/8까지 밀림(그 종목만의 "휴장")
        List<Candle> candlesB = syntheticCandlesWithGap("B", LocalDate.of(2024, 1, 1), trainSize,
                LocalDate.of(2024, 1, 5));

        List<Double> returnsA = List.of(0.01, 0.01, 0.01, 0.01, 0.01);
        List<Double> returnsB = List.of(0.02, 0.02, 0.02, 0.02, 0.02);

        Map<String, List<Candle>> candlesBySymbol = new LinkedHashMap<>();
        candlesBySymbol.put("A", candlesA);
        candlesBySymbol.put("B", candlesB);

        PortfolioBacktestRunner.SleeveRunner sleeveRunner = fakeSleeveRunner(Map.of(
                "A", returnsA,
                "B", returnsB
        ));

        BigDecimal totalCapital = new BigDecimal("2000000");
        PortfolioBacktestRunner.PortfolioResult result =
                runner.run(candlesBySymbol, sleeveRunner, totalCapital, trainSize, testSize);

        // ── 기대값을 이 테스트 안에서 독립적으로 재구성(합집합 날짜 + carry-forward) ──
        BigDecimal sleeveCapital = new BigDecimal("1000000"); // 2,000,000 / 2종목
        List<LocalDate> datesA = candlesA.subList(trainSize, candlesA.size()).stream().map(Candle::date).toList();
        List<LocalDate> datesB = candlesB.subList(trainSize, candlesB.size()).stream().map(Candle::date).toList();

        Map<LocalDate, BigDecimal> equityA = buildEquityCurve(sleeveCapital, datesA, returnsA);
        Map<LocalDate, BigDecimal> equityB = buildEquityCurve(sleeveCapital, datesB, returnsB);

        TreeSet<LocalDate> union = new TreeSet<>();
        union.addAll(datesA);
        union.addAll(datesB);

        BigDecimal lastA = sleeveCapital;
        BigDecimal lastB = sleeveCapital;
        BigDecimal prevTotal = sleeveCapital.add(sleeveCapital);
        List<Double> expectedReturns = new ArrayList<>();
        for (LocalDate d : union) {
            if (equityA.containsKey(d)) {
                lastA = equityA.get(d);
            }
            if (equityB.containsKey(d)) {
                lastB = equityB.get(d);
            }
            BigDecimal total = lastA.add(lastB);
            double r = total.subtract(prevTotal).divide(prevTotal, 12, RoundingMode.HALF_UP).doubleValue();
            expectedReturns.add(r);
            prevTotal = total;
        }

        assertEquals(expectedReturns.size(), result.dailyReturns().size(),
                "합집합 날짜 수(carry-forward 포함) = 포트폴리오 일별수익률 길이");
        for (int i = 0; i < expectedReturns.size(); i++) {
            assertEquals(expectedReturns.get(i), result.dailyReturns().get(i), 1e-9,
                    "인덱스 " + i + " 수익률이 독립 재계산값과 달라야 함(carry-forward 로직 검증)");
        }
    }

    /** date → 순서대로 (1+return)을 복리 적용한 자산가치. */
    private Map<LocalDate, BigDecimal> buildEquityCurve(BigDecimal startCapital, List<LocalDate> dates, List<Double> returns) {
        Map<LocalDate, BigDecimal> curve = new LinkedHashMap<>();
        BigDecimal equity = startCapital;
        for (int i = 0; i < dates.size(); i++) {
            equity = equity.multiply(BigDecimal.valueOf(1 + returns.get(i)));
            curve.put(dates.get(i), equity);
        }
        return curve;
    }

    /** symbolToReturns에 등록된 심볼이면 그 수익률 시계열로 BacktestResult를 조립해 돌려주는 가짜 SleeveRunner. */
    private PortfolioBacktestRunner.SleeveRunner fakeSleeveRunner(Map<String, List<Double>> symbolToReturns) {
        return (candles, sleeveCapital) -> {
            String symbol = candles.get(0).symbol();
            List<Double> returns = symbolToReturns.get(symbol);
            BigDecimal finalEquity = compound(sleeveCapital, returns);
            BacktestResult oos = performanceCalculator.calculate(returns, sleeveCapital, finalEquity, 0, 1, 0.0);
            return new WalkForwardResult<Double>(List.of(), oos, 1);
        };
    }

    private BigDecimal compound(BigDecimal initialCapital, List<Double> returns) {
        double multiplier = 1.0;
        for (double r : returns) {
            multiplier *= (1 + r);
        }
        return initialCapital.multiply(BigDecimal.valueOf(multiplier));
    }

    /** 연속된 날짜(공백 없음)의 더미 캔들 목록. OHLC 값 자체는 이 테스트에서 쓰이지 않는다(날짜만 씀). */
    private List<Candle> syntheticCandles(String symbol, LocalDate startDate, int count) {
        List<Candle> candles = new ArrayList<>();
        LocalDate date = startDate;
        for (int i = 0; i < count; i++) {
            candles.add(dummyCandle(symbol, date));
            date = date.plusDays(1);
        }
        return candles;
    }

    /**
     * 훈련 구간(trainSize개)은 연속 날짜로 채우고, 그 뒤 OOS 구간에서는 skipDate 하루를
     * 건너뛰어(그 종목만의 "휴장") 날짜를 하루씩 밀리게 만든 더미 캔들 목록. count는 항상
     * trainSize + 5(이 테스트가 쓰는 testSize) 만큼 생성한다.
     */
    private List<Candle> syntheticCandlesWithGap(String symbol, LocalDate startDate, int trainSize, LocalDate skipDate) {
        List<Candle> candles = new ArrayList<>();
        LocalDate date = startDate;
        int total = trainSize + 5;
        while (candles.size() < total) {
            if (candles.size() >= trainSize && date.equals(skipDate)) {
                date = date.plusDays(1); // 이 날짜는 건너뛴다(휴장)
            }
            candles.add(dummyCandle(symbol, date));
            date = date.plusDays(1);
        }
        return candles;
    }

    private Candle dummyCandle(String symbol, LocalDate date) {
        BigDecimal price = new BigDecimal("10000");
        return new Candle(symbol, date, price, price, price, price, 1000L);
    }
}
