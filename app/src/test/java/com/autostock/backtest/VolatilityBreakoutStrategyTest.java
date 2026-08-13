package com.autostock.backtest;

import com.autostock.common.event.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VolatilityBreakoutStrategy 검증.
 *
 * <p>전반부는 {@link VolatilityBreakoutStrategy#onCandle}을 직접 호출해 "어떤 TradeIntent를
 * 반환하는가"만 확인하고(전략 판단 단위 테스트), 후반부는 {@link BacktestRunner}와 함께
 * 돌려 "실제로 진입/청산까지 이어지는가"를 합성 캔들 몇 개로 확인한다(통합 시나리오 테스트).
 */
class VolatilityBreakoutStrategyTest {

    private Candle candle(String date, String open, String high, String low, String close) {
        return new Candle("TEST", LocalDate.parse(date), new BigDecimal(open), new BigDecimal(high),
                new BigDecimal(low), new BigDecimal(close), 1000L);
    }

    private BacktestStrategy.PortfolioState notHolding() {
        return new BacktestStrategy.PortfolioState(0, BigDecimal.ZERO, new BigDecimal("1000000"));
    }

    private BacktestStrategy.PortfolioState holding() {
        return new BacktestStrategy.PortfolioState(10, new BigDecimal("10000"), new BigDecimal("100000"));
    }

    // ── 판단 단위 테스트 ─────────────────────────────────────────────────────

    @Test
    void 첫_캔들은_전일_데이터가_없어_아무_인텐트도_내지_않는다() {
        VolatilityBreakoutStrategy strategy = new VolatilityBreakoutStrategy(0.5, -0.03);
        Candle day0 = candle("2026-01-02", "10000", "10200", "9900", "10100");

        List<TradeIntent> intents = strategy.onCandle(day0, notHolding());

        assertTrue(intents.isEmpty(), "전일 데이터가 없는 첫 캔들에서는 목표가를 계산할 수 없어야 함");
    }

    @Test
    void 미보유_둘째날에는_전일_고가저가로_계산한_목표가로_buyStop과_sellStop을_함께_건다() {
        VolatilityBreakoutStrategy strategy = new VolatilityBreakoutStrategy(0.5, -0.03);
        Candle day0 = candle("2026-01-02", "10000", "10200", "9900", "10100"); // 전일 변동폭 300
        strategy.onCandle(day0, notHolding()); // 전일 데이터로만 기억시킨다(빈 인텐트 반환)

        Candle day1 = candle("2026-01-03", "10100", "10300", "10000", "10200");
        List<TradeIntent> intents = strategy.onCandle(day1, notHolding());

        // target = 10100(오늘 시가) + 0.5 * (10200-9900) = 10100 + 150 = 10250
        BigDecimal expectedTarget = new BigDecimal("10250.0");
        // stop = target * (1 - 0.03) = 10250 * 0.97 = 9942.5
        BigDecimal expectedStop = new BigDecimal("9942.500");

        assertEquals(2, intents.size());
        assertEquals(TradeIntent.Kind.BUY_STOP, intents.get(0).kind());
        assertEquals(0, expectedTarget.compareTo(intents.get(0).price()), "buyStop 트리거가 목표가와 같아야 함");
        assertEquals(TradeIntent.Kind.SELL_STOP, intents.get(1).kind());
        assertEquals(0, expectedStop.compareTo(intents.get(1).price()), "sellStop이 목표가 대비 -3% 손절가여야 함");
    }

    @Test
    void 손절_설정이_없으면_buyStop만_반환한다() {
        VolatilityBreakoutStrategy strategy = new VolatilityBreakoutStrategy(0.5, null);
        Candle day0 = candle("2026-01-02", "10000", "10200", "9900", "10100");
        strategy.onCandle(day0, notHolding());

        Candle day1 = candle("2026-01-03", "10100", "10300", "10000", "10200");
        List<TradeIntent> intents = strategy.onCandle(day1, notHolding());

        assertEquals(1, intents.size());
        assertEquals(TradeIntent.Kind.BUY_STOP, intents.get(0).kind());
    }

    @Test
    void 보유_중이면_익일_시가_매도만_반환한다() {
        VolatilityBreakoutStrategy strategy = new VolatilityBreakoutStrategy(0.5, -0.03);
        Candle today = candle("2026-01-05", "10500", "10600", "10400", "10550");

        List<TradeIntent> intents = strategy.onCandle(today, holding());

        assertEquals(1, intents.size());
        assertEquals(TradeIntent.Kind.SELL_NEXT_OPEN, intents.get(0).kind());
    }

    // ── BacktestRunner와 함께 도는 통합 시나리오 ────────────────────────────────

    @Test
    void 돌파_진입_후_익일_시가_청산으로_이익이_난다() {
        List<Candle> candles = List.of(
                candle("2026-01-02", "10000", "10200", "9900", "10100"),  // 0: 전일 데이터용(변동폭 300)
                candle("2026-01-03", "10100", "10300", "10000", "10200"), // 1: target=10250 돌파 → 매수
                candle("2026-01-04", "10400", "10450", "10350", "10420"), // 2: 보유 → 오늘 시가(10400)에 매도
                candle("2026-01-05", "10500", "10520", "10480", "10510")  // 3: 목표가(target) 미도달 → 재진입 없음
        );

        BacktestRunner runner = new BacktestRunner(CostModel.defaults());
        BacktestResult result = runner.run(candles, new VolatilityBreakoutStrategy(0.5, -0.03),
                new BigDecimal("1000000"));

        assertEquals(2, result.tradeCount(), "매수 1건(1일차) + 매도 1건(2일차)만 체결되어야 함");
        assertTrue(result.totalReturn() > 0, "돌파 진입가(~10250)보다 매도가(10400)가 높으므로 이익이어야 함");
    }

    @Test
    void 진입_당일_손절선까지_닿으면_같은_날_손절로_손실이_난다() {
        List<Candle> candles = List.of(
                candle("2026-01-02", "10000", "10200", "9900", "10100"),  // 0: 전일 데이터용(변동폭 300)
                candle("2026-01-03", "10100", "10300", "9900", "10000")   // 1: target=10250 돌파, 저가(9900)가 손절선(9942.5) 아래
        );

        BacktestRunner runner = new BacktestRunner(CostModel.defaults());
        BacktestResult result = runner.run(candles, new VolatilityBreakoutStrategy(0.5, -0.03),
                new BigDecimal("1000000"));

        assertEquals(2, result.tradeCount(), "매수 직후 같은 날 손절 매도까지 체결되어야 함");
        assertTrue(result.totalReturn() < 0, "손절가가 진입가보다 낮으므로 손실이어야 함");
    }
}
