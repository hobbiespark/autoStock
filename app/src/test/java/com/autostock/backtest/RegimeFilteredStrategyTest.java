package com.autostock.backtest;

import com.autostock.common.event.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RegimeFilteredStrategy 검증 — OFF일 때 강제 청산/진입금지(내부 전략 미호출),
 * ON일 때 내부 전략에 그대로 위임하는지를 확인한다.
 */
class RegimeFilteredStrategyTest {

    private static final LocalDate DATE = LocalDate.of(2026, 1, 2);

    private Candle candle(LocalDate date) {
        BigDecimal p = new BigDecimal("10000");
        return new Candle("TEST", date, p, p, p, p, 1000L);
    }

    private BacktestStrategy.PortfolioState holding() {
        return new BacktestStrategy.PortfolioState(10, new BigDecimal("9000"), new BigDecimal("100000"));
    }

    private BacktestStrategy.PortfolioState notHolding() {
        return new BacktestStrategy.PortfolioState(0, BigDecimal.ZERO, new BigDecimal("1000000"));
    }

    /** 호출 여부와 반환값을 기록하는 스텁 내부 전략. */
    private static final class RecordingStrategy implements BacktestStrategy {
        boolean called = false;
        final List<TradeIntent> toReturn;

        RecordingStrategy(List<TradeIntent> toReturn) {
            this.toReturn = toReturn;
        }

        @Override
        public List<TradeIntent> onCandle(Candle today, PortfolioState state) {
            called = true;
            return toReturn;
        }
    }

    private Map<LocalDate, Boolean> regimeMap(LocalDate date, boolean on) {
        Map<LocalDate, Boolean> map = new LinkedHashMap<>();
        map.put(date, on);
        return map;
    }

    @Test
    void OFF_이고_보유중이면_내부전략_호출없이_강제청산한다() {
        RecordingStrategy inner = new RecordingStrategy(List.of());
        RegimeFilteredStrategy strategy = new RegimeFilteredStrategy(inner, regimeMap(DATE, false));

        List<TradeIntent> intents = strategy.onCandle(candle(DATE), holding());

        assertFalse(inner.called, "OFF일 때는 내부 전략을 호출하지 않아야 함");
        assertEquals(1, intents.size());
        assertEquals(TradeIntent.Kind.SELL_NEXT_OPEN, intents.get(0).kind());
    }

    @Test
    void OFF_이고_미보유면_내부전략_호출없이_빈_리스트를_반환한다() {
        RecordingStrategy inner = new RecordingStrategy(List.of(TradeIntent.buyStop(new BigDecimal("10000"))));
        RegimeFilteredStrategy strategy = new RegimeFilteredStrategy(inner, regimeMap(DATE, false));

        List<TradeIntent> intents = strategy.onCandle(candle(DATE), notHolding());

        assertFalse(inner.called, "OFF일 때는 내부 전략을 호출하지 않아야 함(진입 금지)");
        assertTrue(intents.isEmpty(), "OFF + 미보유면 진입하지 않아야 함");
    }

    @Test
    void ON_이면_내부전략_판단을_그대로_위임한다() {
        List<TradeIntent> innerResult = List.of(TradeIntent.buyStop(new BigDecimal("10500")));
        RecordingStrategy inner = new RecordingStrategy(innerResult);
        RegimeFilteredStrategy strategy = new RegimeFilteredStrategy(inner, regimeMap(DATE, true));

        List<TradeIntent> intents = strategy.onCandle(candle(DATE), notHolding());

        assertTrue(inner.called, "ON일 때는 내부 전략을 호출해야 함");
        assertSame(innerResult, intents, "ON일 때는 내부 전략의 반환값을 그대로 넘겨야 함");
    }

    @Test
    void 국면맵에_없는_날짜는_보수적으로_ON으로_취급해_내부전략에_위임한다() {
        List<TradeIntent> innerResult = List.of(TradeIntent.buyStop(new BigDecimal("10500")));
        RecordingStrategy inner = new RecordingStrategy(innerResult);
        RegimeFilteredStrategy strategy = new RegimeFilteredStrategy(inner, new LinkedHashMap<>()); // 빈 맵

        List<TradeIntent> intents = strategy.onCandle(candle(DATE), notHolding());

        assertTrue(inner.called, "맵에 없는 날짜는 ON 기본값으로 내부 전략에 위임해야 함");
        assertSame(innerResult, intents);
    }
}
