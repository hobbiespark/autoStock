package com.autostock.strategy;

import com.autostock.common.event.Candle;
import com.autostock.common.event.Fill;
import com.autostock.common.event.MarketTick;
import com.autostock.common.event.Side;
import com.autostock.common.event.Signal;
import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.ClientOrderId;
import com.autostock.common.util.Price;
import com.autostock.common.util.Quantity;
import com.autostock.common.util.StockCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static com.autostock.strategy.StrategyTestSupport.CLOCK;
import static com.autostock.strategy.StrategyTestSupport.TODAY;
import static com.autostock.strategy.StrategyTestSupport.candle;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 3분할·스윙 이월 흐름(2026-10-30, aiDoc/split-buy-swing-carry-2026-10-30.md) — 단타 진입은 1차 분할, 청산 시각에 비용 후
 * 수익이 아니면 이월, 다음 거래일 09:02 판단으로 2차 매수, 체결로 차수 증가, 팔리면 이월 끝.
 */
class SwingCarryFlowTest {

    private StrategyTestSupport t;
    private LiveMarket market;
    private StrategyGuard guard;
    private StrategyCarryRepository repository;
    private SwingCarryManager carry;
    private DayBreakoutStrategy vb;
    private OvernightCloseStrategy on;
    private int seq = 100;

    @BeforeEach
    void setUp() {
        t = new StrategyTestSupport();
        market = mock(LiveMarket.class);
        guard = mock(StrategyGuard.class);
        when(guard.active(anyBoolean())).thenReturn(true);
        when(guard.today()).thenReturn(TODAY);
        when(guard.now()).thenReturn(LocalTime.of(9, 30));
        repository = mock(StrategyCarryRepository.class);
        when(repository.findAll()).thenReturn(List.of());
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        SwingCarryProperties props = new SwingCarryProperties(true, List.of("VB-DAY", "ON-CLOSE"), 0.0023, 0.07, 0.05,
                20, 60, 10, 0, 0.005, 0.01);
        carry = new SwingCarryManager(props, repository, t.book, market, t.outbox, guard, t.events::add, CLOCK);
        when(market.completedBars("005930")).thenReturn(StrategyTestSupport.flatThen("005930", 19, "100000",
                candle("005930", TODAY.minusDays(1), "99000", "104000", "99000", "102000")));
        when(market.knownOpen("005930")).thenReturn(new BigDecimal("101000"));
        vb = new DayBreakoutStrategy(new DayBreakoutProperties(true, List.of("005930"), 0.5, 20, 0.018, 0.07,
                LocalTime.of(14, 30), LocalTime.of(15, 15), 3, 6, 0.005, 0.01), market, t.book, t.outbox, guard,
                t.events::add, CLOCK, carry);
        vb.prepareToday();
        on = new OvernightCloseStrategy(new OvernightCloseProperties(true, 3, 5, 20, 0.97, 0.05, 0.02, 20,
                List.of("16", "4", "5", "11")), market, t.book, t.outbox, guard,
                t.events::add, CLOCK, carry);
    }

    private static MarketTick tick(String symbol, String price) {
        return new MarketTick(new StockCode(symbol), new Price(new BigDecimal(price)), 1, CLOCK.instant(),
                MarketTick.Source.LIVE);
    }

    private Fill fill(String strategyId, String symbol, Side side, long qty, String price) {
        String id = ClientOrderId.generate(TODAY, strategyId, symbol, side, ++seq).value();
        return new Fill(id, new BrokerOrderId("B" + seq), new StockCode(symbol), side, new Quantity(qty),
                new Price(new BigDecimal(price)), CLOCK.instant());
    }

    /** 오른 일봉 80개(마지막 = 오늘 전날) — 종가 > SMA20. */
    private static List<Candle> rising(String symbol, LocalDate yesterday) {
        List<Candle> list = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            String c = String.valueOf(95000 + i * 100);
            list.add(candle(symbol, yesterday.minusDays(79 - i), c, c, c, c));
        }
        return list;
    }

    @Test
    void 단타_진입은_1차_분할이다() {
        vb.onTick(tick("005930", "103000"));   // 목표가 101,000 + 0.5 × 5,000 = 103,500 아래
        vb.onTick(tick("005930", "103600"));
        List<Signal> signals = t.signals();
        assertEquals(1, signals.size());
        assertEquals(Integer.valueOf(1), signals.get(0).tranche());
    }

    @Test
    void 시간_청산에_수익이면_팔고_아니면_스윙으로_넘긴다() {
        t.filled("VB-DAY", "005930", Side.BUY, 1, "100000");
        when(market.bestBid("005930")).thenReturn(new BigDecimal("99000"));   // −1% — 비용 후 수익 아님
        vb.timeExit();
        assertTrue(t.signals().isEmpty(), "넘기고 팔지 않는다");
        assertTrue(carry.isCarried("VB-DAY", new StockCode("005930")));
        assertTrue(t.decisions().stream().anyMatch(d -> d.conclusion().equals("CARRY")));
        vb.timeExitRetry();
        vb.onTick(tick("005930", "98000"));   // 넘긴 보유는 실시간 손절·익절을 보지 않는다
        assertTrue(t.signals().isEmpty());
    }

    @Test
    void 시간_청산에_비용_후_수익이면_판다() {
        t.filled("VB-DAY", "005930", Side.BUY, 1, "100000");
        when(market.bestBid("005930")).thenReturn(new BigDecimal("100500"));   // +0.5% ≥ 왕복 0.23%
        vb.timeExit();
        assertEquals(1, t.signals().size());
        assertEquals(Side.SELL, t.signals().get(0).side());
    }

    @Test
    void 종가_익일_아침에_갭_하락이면_팔지_않고_넘긴다() {
        t.filled("ON-CLOSE", "441270", Side.BUY, 85, "11150");
        t.filled("ON-CLOSE", "007810", Side.BUY, 3, "75300");
        when(market.bestBid("441270")).thenReturn(new BigDecimal("10470"));   // −6.1%(10/26 실측)
        when(market.bestBid("007810")).thenReturn(new BigDecimal("79700"));   // +5.8%(10/28 실측)
        on.morningExit();
        List<Signal> signals = t.signals();
        assertEquals(1, signals.size());
        assertEquals("007810", signals.get(0).symbol().value());
        assertTrue(carry.isCarried("ON-CLOSE", new StockCode("441270")));
        on.retryExit();   // 아직 체결 통보 전인 007810은 다시 낸다(기존 동작) — 넘긴 441270은 내지 않는다
        assertTrue(t.signals().stream().noneMatch(s -> s.symbol().value().equals("441270")),
                "넘긴 보유는 재시도에서도 팔지 않는다");
    }

    @Test
    void 이월_당일은_판단하지_않고_다음_거래일에_2차를_산다() {
        t.filled("VB-DAY", "005930", Side.BUY, 1, "100000");
        when(market.bestBid("005930")).thenReturn(new BigDecimal("99000"));
        vb.timeExit();
        String sameDay = carry.evaluateToday();
        assertTrue(sameDay.contains("이월 당일"), sameDay);
        assertTrue(t.signals().isEmpty());

        LocalDate next = TODAY.plusDays(3);
        when(guard.today()).thenReturn(next);
        when(market.completedBars("005930")).thenReturn(rising("005930", next.minusDays(1)));
        when(market.bestAsk(anyString())).thenReturn(new BigDecimal("102900"));
        String line = carry.evaluateToday();
        assertTrue(line.contains("추가 매수 1"), line);
        List<Signal> signals = t.signals();
        assertEquals(1, signals.size());
        assertEquals(Side.BUY, signals.get(0).side());
        assertEquals(Integer.valueOf(2), signals.get(0).tranche());
    }

    @Test
    void 추가_매수_체결은_하루에_한_번만_차수를_올린다() {
        t.filled("VB-DAY", "005930", Side.BUY, 1, "100000");
        when(market.bestBid("005930")).thenReturn(new BigDecimal("99000"));
        vb.timeExit();
        LocalDate next = TODAY.plusDays(1);
        when(guard.today()).thenReturn(next);
        carry.onFill(fill("VB-DAY", "005930", Side.BUY, 1, "100000"));
        carry.onFill(fill("VB-DAY", "005930", Side.BUY, 1, "100000"));   // 부분 체결
        StrategyCarryEntity saved = captured();
        assertEquals(2, saved.getTranches());
        assertEquals(SwingCarryMath.Stage.SWING, saved.getStage());
        when(guard.today()).thenReturn(next.plusDays(1));
        carry.onFill(fill("VB-DAY", "005930", Side.BUY, 1, "100000"));
        assertEquals(3, captured().getTranches());
        assertEquals(SwingCarryMath.Stage.MID, captured().getStage());
    }

    @Test
    void 보유가_팔리면_이월을_끝낸다() {
        t.filled("VB-DAY", "005930", Side.BUY, 1, "100000");
        when(market.bestBid("005930")).thenReturn(new BigDecimal("99000"));
        vb.timeExit();
        t.filled("VB-DAY", "005930", Side.SELL, 1, "99000");
        when(guard.today()).thenReturn(TODAY.plusDays(1));
        assertNull(carry.evaluateToday());
        assertTrue(carry.carriedSymbols("VB-DAY").isEmpty());
    }

    private StrategyCarryEntity captured() {
        org.mockito.ArgumentCaptor<StrategyCarryEntity> captor = org.mockito.ArgumentCaptor.forClass(StrategyCarryEntity.class);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        List<StrategyCarryEntity> all = captor.getAllValues();
        return all.get(all.size() - 1);
    }
}
