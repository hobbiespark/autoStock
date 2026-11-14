package com.autostock.strategy;

import com.autostock.common.event.Side;
import com.autostock.common.event.Signal;
import com.autostock.common.event.SignalDecision;
import com.autostock.common.event.StrategyRunCompleted;
import com.autostock.common.util.StockCode;
import com.autostock.market.MarketCalendarService;
import com.autostock.market.MarketDataPort;
import com.autostock.monitor.TradingSystemManager;
import com.autostock.monitor.TradingSystemStatus;
import com.autostock.portfolio.StrategyBook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 지수 보유 전략(경로 C, ADR-16): 미보유면 매수 신호 하나, 보유 중이면 신호 없음, 스스로 팔지 않음. */
class IndexHoldStrategyTest {

    /** 2026-10-07(수) 09:05 KST. */
    private static final Clock WED_0905 = Clock.fixed(Instant.parse("2026-10-07T00:05:00Z"), ZoneOffset.UTC);
    private static final StockCode KODEX = new StockCode("069500");

    private final List<Object> published = new ArrayList<>();
    private final MarketDataPort marketData = mock(MarketDataPort.class);
    private final StrategyBook book = mock(StrategyBook.class);
    private final MarketCalendarService calendar = mock(MarketCalendarService.class);
    private final TradingSystemManager system = mock(TradingSystemManager.class);

    @BeforeEach
    void setUp() {
        when(calendar.isTradingDay(any())).thenReturn(true);
        when(system.status()).thenReturn(TradingSystemStatus.RUNNING);
        when(marketData.bestQuote("069500")).thenReturn(new MarketDataPort.BestQuote(new BigDecimal("84300"), new BigDecimal("84250")));
    }

    private IndexHoldStrategy strategy(boolean enabled) {
        return new IndexHoldStrategy(new IndexHoldProperties(enabled, "069500"), marketData, book, published::add,
                calendar, system, WED_0905);
    }

    private List<Signal> signals() {
        return published.stream().filter(Signal.class::isInstance).map(Signal.class::cast).toList();
    }

    @Test
    void 미보유면_최우선_매도호가로_비중_1_매수_신호_하나() {
        when(book.holds(IndexHoldStrategy.STRATEGY_ID, KODEX)).thenReturn(false);

        strategy(true).run();

        List<Signal> s = signals();
        assertEquals(1, s.size());
        assertEquals(Side.BUY, s.get(0).side());
        assertEquals("KODEX-HOLD", s.get(0).strategyId());
        assertEquals(0, new BigDecimal("84300").compareTo(s.get(0).refPrice().value()));
        assertEquals(1.0, s.get(0).confidence(), 0.0);
        SignalDecision d = published.stream().filter(SignalDecision.class::isInstance).map(SignalDecision.class::cast).findFirst().orElseThrow();
        assertEquals("LONG", d.horizon());
        assertEquals("BUY", d.conclusion());
        assertTrue(published.stream().anyMatch(StrategyRunCompleted.class::isInstance), "09:15 확인용 실행 요약");
    }

    @Test
    void 보유_중이면_신호_없이_HOLD만_남기고_팔지_않는다() {
        when(book.holds(IndexHoldStrategy.STRATEGY_ID, KODEX)).thenReturn(true);

        strategy(true).run();

        assertTrue(signals().isEmpty());
        assertTrue(published.stream().filter(SignalDecision.class::isInstance).map(SignalDecision.class::cast)
                .allMatch(d -> d.conclusion().equals("HOLD")));
    }

    @Test
    void 꺼져_있거나_RUNNING이_아니거나_휴장일이면_아무것도_안_한다() {
        assertNull(strategy(false).execute());

        when(system.status()).thenReturn(TradingSystemStatus.STOPPED);
        assertNull(strategy(true).execute());

        when(system.status()).thenReturn(TradingSystemStatus.RUNNING);
        when(calendar.isTradingDay(any())).thenReturn(false);
        assertNull(strategy(true).execute());
        assertTrue(published.isEmpty());
    }

    @Test
    void 같은_날_따라잡기는_09시05분_판단_뒤_다시_돌지_않는다() {
        when(book.holds(IndexHoldStrategy.STRATEGY_ID, KODEX)).thenReturn(false);
        IndexHoldStrategy s = strategy(true);

        s.run();
        s.catchUp();

        assertEquals(1, signals().size());
    }
}
