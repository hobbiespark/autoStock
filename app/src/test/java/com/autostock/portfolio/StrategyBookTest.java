package com.autostock.portfolio;

import com.autostock.common.event.Fill;
import com.autostock.common.event.Side;
import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.ClientOrderId;
import com.autostock.common.util.Price;
import com.autostock.common.util.Quantity;
import com.autostock.common.util.StockCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 전략별 장부 — 주문번호의 전략 ID로 (전략, 종목)별 보유·평단·실현손익을 센다(2026-10-16).
 */
class StrategyBookTest {

    private static final Instant NOW = Instant.parse("2026-10-19T01:00:00Z");   // 10:00 KST
    private static final StockCode SAMSUNG = new StockCode("005930");

    private StrategyPositionRepository positions;
    private StrategyFillRepository fills;
    private StrategyBook book;
    private int seq;

    @BeforeEach
    void setUp() {
        positions = mock(StrategyPositionRepository.class);
        fills = mock(StrategyFillRepository.class);
        when(positions.findAll()).thenReturn(List.of());
        book = new StrategyBook(positions, fills, Clock.fixed(NOW, ZoneOffset.UTC), 0.00015, 0.0020);
    }

    private Fill fill(String strategyId, Side side, long qty, String price) {
        String id = ClientOrderId.generate(LocalDate.of(2026, 10, 19), strategyId, "005930", side, ++seq).value();
        return new Fill(id, new BrokerOrderId("B" + seq), SAMSUNG, side, new Quantity(qty), new Price(new BigDecimal(price)), NOW);
    }

    @Test
    void 같은_종목을_전략마다_따로_센다() {
        book.onFill(fill("VB-DAY", Side.BUY, 10, "216000"));
        book.onFill(fill("TA-H1", Side.BUY, 3, "217000"));

        assertEquals(10, book.position("VB-DAY", SAMSUNG).orElseThrow().quantity());
        assertEquals(3, book.position("TA-H1", SAMSUNG).orElseThrow().quantity());
        assertEquals(1, book.openPositionCount("VB-DAY"));
        assertEquals(java.util.Map.of("TA-H1", 3L, "VB-DAY", 10L), book.holdersOf(SAMSUNG));
    }

    @Test
    void 추가_매수는_가중_평단_매도는_실현손익_비용_차감() {
        book.onFill(fill("VB-DAY", Side.BUY, 10, "100000"));
        book.onFill(fill("VB-DAY", Side.BUY, 10, "102000"));
        assertEquals(0, new BigDecimal("101000").compareTo(book.position("VB-DAY", SAMSUNG).orElseThrow().avgPrice()));

        book.onFill(fill("VB-DAY", Side.SELL, 20, "103000"));

        assertFalse(book.holds("VB-DAY", SAMSUNG), "전량 매도면 장부에서 빠진다");
        ArgumentCaptor<StrategyFillEntity> captor = ArgumentCaptor.forClass(StrategyFillEntity.class);
        verify(fills, atLeastOnce()).save(captor.capture());
        StrategyFillEntity sell = captor.getAllValues().get(2);
        assertEquals("SELL", sell.getSide());
        // (103,000 − 101,000) × 20 = 40,000 − 매수 수수료 303 − 매도 수수료 309 − 거래세 4,120 = 35,268
        assertEquals(0, new BigDecimal("35268.00").compareTo(sell.getRealizedPnl()));
        verify(positions).deleteById(new StrategyPositionEntity.Key("VB-DAY", "005930"));
    }

    @Test
    void 전략_ID를_읽을_수_없는_체결은_넣지_않는다() {
        book.onFill(new Fill("probe-1", new BrokerOrderId("X"), SAMSUNG, Side.BUY, new Quantity(1),
                new Price(new BigDecimal("1000")), NOW));
        assertTrue(book.positions("probe-1").isEmpty());
        assertNull(StrategyBook.strategyIdOf("not-a-client-order-id"));
        assertEquals("DASHBOARD-MANUAL", StrategyBook.strategyIdOf(
                ClientOrderId.generate(LocalDate.of(2026, 10, 19), "dashboard-manual", "005930", Side.BUY, 1).value()));
    }

    @Test
    void 재기동하면_표에서_보유를_읽는다() {
        when(positions.findAll()).thenReturn(List.of(new StrategyPositionEntity("ON-CLOSE", "005930", 7,
                new BigDecimal("215000"), NOW, NOW)));
        StrategyBook restored = new StrategyBook(positions, fills, Clock.fixed(NOW, ZoneOffset.UTC), 0.00015, 0.0020);
        restored.loadOnStartup();
        assertEquals(7, restored.position("ON-CLOSE", SAMSUNG).orElseThrow().quantity());
    }

    @Test
    void 저장이_실패해도_메모리_장부는_갱신한다() {
        when(positions.save(any())).thenThrow(new RuntimeException("db down"));
        book.onFill(fill("ON-CLOSE", Side.BUY, 5, "50000"));
        assertEquals(5, book.position("ON-CLOSE", SAMSUNG).orElseThrow().quantity());
    }

    @Test
    void 보유보다_많이_팔린_체결은_장부_수량까지만_줄인다() {
        book.onFill(fill("TA-H2", Side.BUY, 3, "10000"));
        book.onFill(fill("TA-H2", Side.SELL, 5, "11000"));
        assertFalse(book.holds("TA-H2", SAMSUNG));
    }

    @Test
    void 하루_요약은_체결_표로_센다() {
        StrategyFillEntity win = new StrategyFillEntity("VB-DAY", "005930", "SELL", 10, new BigDecimal("102000"), "x",
                new BigDecimal("100000"), new BigDecimal("16000"), NOW);
        StrategyFillEntity loss = new StrategyFillEntity("VB-DAY", "000660", "SELL", 1, new BigDecimal("990000"), "y",
                new BigDecimal("1000000"), new BigDecimal("-12000"), NOW);
        StrategyFillEntity buy = new StrategyFillEntity("VB-DAY", "005930", "BUY", 10, new BigDecimal("100000"), "z",
                null, null, NOW);
        when(fills.findByFilledAtGreaterThanEqualAndFilledAtLessThan(any(), any())).thenReturn(List.of(buy, win, loss));

        List<StrategyBook.DaySummary> summaries = book.daySummaries(LocalDate.of(2026, 10, 19));

        assertEquals(1, summaries.size());
        StrategyBook.DaySummary s = summaries.get(0);
        assertEquals(1, s.buyFills());
        assertEquals(2, s.sellFills());
        assertEquals(0, new BigDecimal("4000").compareTo(s.realizedPnl()));
        assertEquals(1, s.wins());
        assertEquals(1, s.losses());
    }
}
