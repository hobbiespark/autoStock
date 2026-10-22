package com.autostock.strategy;

import com.autostock.common.event.MarketTick;
import com.autostock.common.event.Side;
import com.autostock.common.event.Signal;
import com.autostock.common.util.Price;
import com.autostock.common.util.StockCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

import static com.autostock.strategy.StrategyTestSupport.CLOCK;
import static com.autostock.strategy.StrategyTestSupport.TODAY;
import static com.autostock.strategy.StrategyTestSupport.candle;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 단타 변동성 돌파(VB-DAY) — 실시간 체결로 진입·익절·손절, 15:15 시간 청산. */
class DayBreakoutStrategyTest {

    private StrategyTestSupport t;
    private LiveMarket market;
    private StrategyGuard guard;
    private DayBreakoutStrategy strategy;

    private static DayBreakoutProperties props(List<String> symbols, int maxPositions) {
        return new DayBreakoutProperties(true, symbols, 0.5, 20, 0.018, 0.015, LocalTime.of(14, 30),
                LocalTime.of(15, 15), maxPositions, 6, 0.005, 0.01);
    }

    @BeforeEach
    void setUp() {
        t = new StrategyTestSupport();
        market = mock(LiveMarket.class);
        guard = mock(StrategyGuard.class);
        when(guard.active(anyBoolean())).thenReturn(true);
        when(guard.today()).thenReturn(TODAY);
        when(guard.now()).thenReturn(LocalTime.of(9, 30));
        // 005930: 전일 양봉(99,000→102,000, 고 104,000 저 99,000), SMA20 = 100,100
        when(market.completedBars("005930")).thenReturn(StrategyTestSupport.flatThen("005930", 19, "100000",
                candle("005930", TODAY.minusDays(1), "99000", "104000", "99000", "102000")));
        // 000660: 전일 음봉
        when(market.completedBars("000660")).thenReturn(StrategyTestSupport.flatThen("000660", 19, "100000",
                candle("000660", TODAY.minusDays(1), "103000", "104000", "99000", "100000")));
        when(market.knownOpen("005930")).thenReturn(new BigDecimal("101000"));
        when(market.knownOpen("000660")).thenReturn(new BigDecimal("101000"));
        strategy = new DayBreakoutStrategy(props(List.of("005930", "000660"), 3), market, t.book, t.outbox, guard,
                t.events::add, CLOCK);
        strategy.prepareToday();
    }

    private static MarketTick tick(String symbol, String price) {
        return new MarketTick(new StockCode(symbol), new Price(new BigDecimal(price)), 1, CLOCK.instant(),
                MarketTick.Source.LIVE);
    }

    @Test
    void 목표가를_넘는_체결이_오면_한_번만_산다() {
        // 목표가 = 101,000 + 0.5 × (104,000 − 99,000) = 103,500
        strategy.onTick(tick("005930", "103400"));
        assertTrue(t.signals().isEmpty(), "목표가 아래");

        strategy.onTick(tick("005930", "103500"));
        strategy.onTick(tick("005930", "103600"));

        List<Signal> signals = t.signals();
        assertEquals(1, signals.size(), "종목당 하루 한 번");
        assertEquals("VB-DAY", signals.get(0).strategyId());
        assertEquals(Side.BUY, signals.get(0).side());
        assertEquals(0, new BigDecimal("104018").compareTo(signals.get(0).refPrice().value()), "103,500 × 1.005");
        assertTrue(t.decisions().stream().anyMatch(d -> d.conclusion().equals("BUY") && d.horizon().equals("DAY")));
    }

    @Test
    void 전일_음봉이면_진입하지_않고_한_번만_기록한다() {
        strategy.onTick(tick("000660", "200000"));
        strategy.onTick(tick("000660", "200100"));
        assertTrue(t.signals().isEmpty());
        assertEquals(1, t.decisions().stream().filter(d -> d.conclusion().equals("SKIP")).count());
    }

    @Test
    void 보유_중이면_익절_손절을_실시간_체결로_본다() {
        t.filled("VB-DAY", "005930", Side.BUY, 10, "100000");

        strategy.onTick(tick("005930", "101700"));
        assertTrue(t.signals().isEmpty(), "익절 기준(101,800) 아래");

        strategy.onTick(tick("005930", "101800"));
        List<Signal> signals = t.signals();
        assertEquals(1, signals.size());
        assertEquals(Side.SELL, signals.get(0).side());
        assertEquals(0, new BigDecimal("100782").compareTo(signals.get(0).refPrice().value()), "101,800 × 0.99");

        strategy.onTick(tick("005930", "98000"));
        assertEquals(1, t.signals().size(), "같은 종목 청산은 1분에 한 번");
    }

    @Test
    void 손절() {
        t.filled("VB-DAY", "000660", Side.BUY, 10, "100000");
        strategy.onTick(tick("000660", "98500"));
        assertEquals(Side.SELL, t.signals().get(0).side());
    }

    @Test
    void 진입_마감_뒤에는_사지_않는다() {
        when(guard.now()).thenReturn(LocalTime.of(14, 30));
        strategy.onTick(tick("005930", "110000"));
        assertTrue(t.signals().isEmpty());
    }

    @Test
    void 동시_보유_한도면_돌파해도_사지_않는다() {
        DayBreakoutStrategy one = new DayBreakoutStrategy(props(List.of("005930", "000660"), 1), market, t.book,
                t.outbox, guard, t.events::add, CLOCK);
        one.prepareToday();
        t.filled("VB-DAY", "000660", Side.BUY, 1, "100000");
        one.onTick(tick("005930", "103500"));
        assertTrue(t.signals().isEmpty());
        assertTrue(t.decisions().stream().anyMatch(d -> d.reason().contains("한도")));
    }

    @Test
    void 시간_청산은_남은_보유를_매수호가로_판다() {
        t.filled("VB-DAY", "005930", Side.BUY, 10, "100000");
        when(market.bestBid(anyString())).thenReturn(new BigDecimal("100500"));
        strategy.timeExit();
        List<Signal> signals = t.signals();
        assertEquals(1, signals.size());
        assertEquals(0, new BigDecimal("99495").compareTo(signals.get(0).refPrice().value()), "100,500 × 0.99");
    }

    @Test
    void 비활성이면_아무것도_안_한다() {
        when(guard.active(anyBoolean())).thenReturn(false);
        DayBreakoutStrategy off = new DayBreakoutStrategy(props(List.of("005930"), 3), market, t.book, t.outbox, guard,
                t.events::add, CLOCK);
        off.prepare();
        off.onTick(tick("005930", "200000"));
        assertTrue(t.signals().isEmpty());
        assertTrue(t.runs().isEmpty());
    }
}
