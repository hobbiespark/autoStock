package com.autostock.backtest;

import com.autostock.common.event.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VolatilityTargetingStrategy 검증 — fraction 계산식(수작업 값과 비교),
 * 관측 부족 시 fraction=1.0, 목표 변동성보다 실현 변동성이 낮을 때 1.0으로 캡되는지를 확인한다.
 *
 * <h2>수작업 검증값 유도 — ±2% 교대 수익률 20개</h2>
 * 가격을 1.02, 0.98을 번갈아 곱해 만들면(등비이므로 가격 수준과 무관하게) 일간수익률이
 * 정확히 +0.02, -0.02가 교대로 20개 나온다(10개씩). 평균=0, 분산=0.02^2=0.0004,
 * 표준편차=0.02, realizedVol = 0.02 × sqrt(252) ≈ 0.31749016,
 * fraction = min(1, 0.20 / 0.31749016) ≈ 0.62994079.
 */
class VolatilityTargetingStrategyTest {

    private static final LocalDate D0 = LocalDate.of(2026, 1, 2);

    /** buyStop(today.open())을 매번 반환하는 단순 스텁 내부 전략 — 보유 상태와 무관하게 항상 진입 시도. */
    private static final class AlwaysBuyStrategy implements BacktestStrategy {
        @Override
        public List<TradeIntent> onCandle(Candle today, PortfolioState state) {
            return List.of(TradeIntent.buyStop(today.open()));
        }
    }

    private BacktestStrategy.PortfolioState notHolding() {
        return new BacktestStrategy.PortfolioState(0, BigDecimal.ZERO, new BigDecimal("1000000"));
    }

    private Candle candle(int dayOffset, BigDecimal close) {
        return new Candle("TEST", D0.plusDays(dayOffset), close, close, close, close, 1000L);
    }

    /**
     * index 0..20(21개) 종가가 등비 교대(+2%/-2%)로 움직이는 캔들 목록을 만들고, index21에
     * "오늘"(임의 종가) 캔들 하나를 더 붙인다 — 총 22개. index21에서 계산되는 fraction이
     * 정확히 index0..20의 21개 종가(=20개 수익률)로부터 나온 값이다.
     */
    private List<Candle> candlesWithAlternatingReturns(double magnitude) {
        List<Candle> candles = new ArrayList<>();
        BigDecimal price = new BigDecimal("10000");
        candles.add(candle(0, price));
        BigDecimal up = BigDecimal.valueOf(1 + magnitude);
        BigDecimal down = BigDecimal.valueOf(1 - magnitude);
        for (int i = 1; i <= 20; i++) {
            price = price.multiply(i % 2 == 1 ? up : down);
            candles.add(candle(i, price));
        }
        candles.add(candle(21, price)); // "오늘" — 이 캔들 자신의 종가는 이번 판단에 쓰이지 않음
        return candles;
    }

    private TradeIntent lastFraction(List<Candle> candles, VolatilityTargetingStrategy strategy) {
        TradeIntent result = null;
        for (Candle c : candles) {
            List<TradeIntent> intents = strategy.onCandle(c, notHolding());
            result = intents.get(0);
        }
        return result;
    }

    @Test
    void 이십봉_수익률_표준편차로부터_fraction을_계산한다_수작업값_비교() {
        List<Candle> candles = candlesWithAlternatingReturns(0.02);
        VolatilityTargetingStrategy strategy = new VolatilityTargetingStrategy(new AlwaysBuyStrategy(), 0.20);

        TradeIntent last = lastFraction(candles, strategy);

        assertEquals(TradeIntent.Kind.BUY_STOP, last.kind());
        assertEquals(0.629940788348712, last.fraction(), 1e-6,
                "표준편차 0.02 → realizedVol≈0.31749 → fraction=min(1, 0.20/0.31749)≈0.62994이어야 함");
    }

    @Test
    void 관측치가_이십개_미만이면_fraction은_1_0이다() {
        List<Candle> fewCandles = new ArrayList<>();
        BigDecimal price = new BigDecimal("10000");
        for (int i = 0; i < 5; i++) {
            fewCandles.add(candle(i, price));
            price = price.multiply(new BigDecimal("1.05")); // 변동성이 커도 관측치 자체가 부족
        }
        VolatilityTargetingStrategy strategy = new VolatilityTargetingStrategy(new AlwaysBuyStrategy(), 0.20);

        TradeIntent last = lastFraction(fewCandles, strategy);

        assertEquals(1.0, last.fraction(), 1e-12, "20개 미만의 일간수익률만 관측됐으면 fraction=1.0이어야 함");
    }

    @Test
    void 실현변동성이_목표보다_낮으면_fraction은_1_0으로_캡된다() {
        // 표준편차 0.001짜리 교대 수익률 → realizedVol ≈ 0.0159, targetVol(0.20)/realizedVol ≈ 12.6 → 1.0으로 캡
        List<Candle> candles = candlesWithAlternatingReturns(0.001);
        VolatilityTargetingStrategy strategy = new VolatilityTargetingStrategy(new AlwaysBuyStrategy(), 0.20);

        TradeIntent last = lastFraction(candles, strategy);

        assertEquals(1.0, last.fraction(), 1e-9, "realizedVol이 targetVol보다 훨씬 낮으면 fraction은 1.0으로 캡돼야 함");
    }

    @Test
    void 변동이_전혀_없으면_realizedVol_0이라_fraction은_1_0이다() {
        List<Candle> candles = new ArrayList<>();
        BigDecimal flat = new BigDecimal("10000");
        for (int i = 0; i <= 21; i++) {
            candles.add(candle(i, flat));
        }
        VolatilityTargetingStrategy strategy = new VolatilityTargetingStrategy(new AlwaysBuyStrategy(), 0.20);

        TradeIntent last = lastFraction(candles, strategy);

        assertEquals(1.0, last.fraction(), 1e-12, "변동이 전혀 없어 realizedVol=0이면 제한할 이유가 없으므로 fraction=1.0");
    }

    @Test
    void 매도_인텐트는_fraction_조정_대상이_아니다() {
        BacktestStrategy sellStrategy = (today, state) -> List.of(TradeIntent.sellNextOpen());
        VolatilityTargetingStrategy strategy = new VolatilityTargetingStrategy(sellStrategy, 0.20);
        BacktestStrategy.PortfolioState holding =
                new BacktestStrategy.PortfolioState(10, new BigDecimal("9000"), new BigDecimal("1000"));

        List<TradeIntent> intents = strategy.onCandle(candle(0, new BigDecimal("10000")), holding);

        assertTrue(intents.size() == 1 && intents.get(0).kind() == TradeIntent.Kind.SELL_NEXT_OPEN);
        assertEquals(1.0, intents.get(0).fraction(), 1e-12, "매도 인텐트의 fraction은 항상 1.0 그대로여야 함(청산은 전량)");
    }
}
