package com.autostock.backtest;

import com.autostock.common.event.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MarketRegime 검증 — 200봉 미만 중립(ON) 처리, SMA/전일종가 비교 판정,
 * 그리고 "오늘" 캔들 데이터가 판정에 전혀 쓰이지 않는지(룩어헤드 없음)를 확인한다.
 */
class MarketRegimeTest {

    private static final LocalDate D0 = LocalDate.of(2020, 1, 1);

    /** 시가=고가=저가=종가로 단순화한 합성 캔들. */
    private Candle candle(int dayOffset, String close) {
        BigDecimal c = new BigDecimal(close);
        return new Candle("IDX", D0.plusDays(dayOffset), c, c, c, c, 1000L);
    }

    /** index 0..199(200개)를 모두 종가 10000으로 채운 평평한 워밍업 구간. */
    private List<Candle> flatWarmup200() {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            candles.add(candle(i, "10000"));
        }
        return candles;
    }

    @Test
    void 이백봉_미만_구간은_ON_중립_처리() {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 199; i++) {
            candles.add(candle(i, String.valueOf(20000 - i * 100))); // 하락 추세라도
        }

        Map<LocalDate, Boolean> regime = MarketRegime.compute(candles);

        assertEquals(199, regime.size());
        for (Boolean on : regime.values()) {
            assertTrue(on, "200봉이 쌓이기 전에는 하락 추세라도 ON(중립) 처리해야 함");
        }
    }

    @Test
    void 이백봉째부터_전일종가와_SMA200을_비교해_상승국면이면_ON() {
        List<Candle> candles = flatWarmup200(); // index 0..199, SMA200(index0..199) = 10000
        candles.add(candle(200, "11000")); // index200 — 상승일(자신의 종가는 그날 판정에 안 쓰임)

        // index201의 SMA200은 closes[1..200] 평균, 전일종가는 closes[200]=11000
        // 평균 = (199*10000 + 11000)/200 = 10005 → 전일종가(11000) > SMA200(10005) → ON
        candles.add(candle(201, "11500"));

        Map<LocalDate, Boolean> regime = MarketRegime.compute(candles);

        assertTrue(regime.get(D0.plusDays(201)), "전일 종가가 SMA200을 초과했으므로 ON(상승국면)이어야 함");
    }

    @Test
    void 이백봉째부터_전일종가와_SMA200을_비교해_하락국면이면_OFF() {
        List<Candle> candles = flatWarmup200(); // index 0..199, SMA200(index0..199) = 10000
        candles.add(candle(200, "9000")); // index200 — 하락일

        // index201의 SMA200 = (199*10000 + 9000)/200 = 9995, 전일종가(9000) < SMA200(9995) → OFF
        candles.add(candle(201, "8900"));

        Map<LocalDate, Boolean> regime = MarketRegime.compute(candles);

        assertFalse(regime.get(D0.plusDays(201)), "전일 종가가 SMA200 이하였으므로 OFF(하락국면)이어야 함");
    }

    @Test
    void 오늘_자신의_고가_저가_종가는_오늘_판정에_영향을_주지_않는다_룩어헤드_없음_검증() {
        List<Candle> baseCandles = flatWarmup200();
        baseCandles.add(candle(200, "9000")); // index200까지는 두 시나리오 동일

        List<Candle> scenarioA = new ArrayList<>(baseCandles);
        scenarioA.add(candle(201, "8900")); // index201(오늘) 종가 — 평이한 값

        List<Candle> scenarioB = new ArrayList<>(baseCandles);
        scenarioB.add(candle(201, "99999")); // index201(오늘) 종가 — 극단적으로 다른 값

        Map<LocalDate, Boolean> regimeA = MarketRegime.compute(scenarioA);
        Map<LocalDate, Boolean> regimeB = MarketRegime.compute(scenarioB);

        assertEquals(regimeA.get(D0.plusDays(201)), regimeB.get(D0.plusDays(201)),
                "index201 자신의 종가가 달라도(전일까지 데이터는 동일) index201의 국면 판정은 같아야 함");
    }
}
