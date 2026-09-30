package com.autostock.portfolio;

import com.autostock.common.event.Fill;
import com.autostock.common.event.PositionRestored;
import com.autostock.common.event.Side;
import com.autostock.common.util.StockCode;
import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.Price;
import com.autostock.common.util.Quantity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PositionBookTest {

    private final PositionBook book = new PositionBook();

    private Fill fill(Side side, long qty, String price) {
        return new Fill("k", new BrokerOrderId("b"), new StockCode("005930"), side, new Quantity(qty), new Price(new BigDecimal(price)), Instant.now());
    }

    @Test
    void 매수_체결로_포지션_생성() {
        book.onFill(fill(Side.BUY, 10, "70000"));

        assertTrue(book.holds(new StockCode("005930")));
        assertEquals(10, book.get(new StockCode("005930")).quantity());
        assertEquals(1, book.openPositionCount());
    }

    @Test
    void 추가_매수시_평균단가_갱신() {
        book.onFill(fill(Side.BUY, 10, "70000"));
        book.onFill(fill(Side.BUY, 10, "72000"));

        assertEquals(20, book.get(new StockCode("005930")).quantity());
        assertEquals(new BigDecimal("71000.00"), book.get(new StockCode("005930")).avgPrice());
    }

    @Test
    void 전량_매도시_포지션_제거() {
        book.onFill(fill(Side.BUY, 10, "70000"));
        book.onFill(fill(Side.SELL, 10, "71000"));

        assertFalse(book.holds(new StockCode("005930")));
        assertEquals(0, book.openPositionCount());
    }

    @Test
    void 잔고_복원_이벤트로_포지션을_시드하고_체결_포지션은_덮어쓰지_않는다() {
        book.onPositionRestored(new PositionRestored(new StockCode("000660"), 3, new Price(new BigDecimal("200000"))));
        book.onFill(fill(Side.BUY, 10, "70000"));
        book.onPositionRestored(new PositionRestored(new StockCode("005930"), 19, new Price(new BigDecimal("259974"))));

        assertEquals(3, book.get(new StockCode("000660")).quantity());
        assertEquals(10, book.get(new StockCode("005930")).quantity());
        assertEquals(2, book.openPositionCount());
    }

    @Test
    void 평단_미상으로_복원된_포지션은_추가_매수해도_평단_미상이고_매도는_수량만_줄인다() {
        // 잔고에서 매입가를 못 찾으면 0 대신 null(평단 모름) — 사용자 결정 2026-09-30(조각 19)
        book.onPositionRestored(new PositionRestored(new StockCode("005930"), 19, null));
        book.onFill(fill(Side.BUY, 1, "70000"));
        book.onFill(fill(Side.SELL, 5, "71000"));

        PositionBook.Position position = book.get(new StockCode("005930"));
        assertEquals(15, position.quantity());
        assertNull(position.avgPrice());
        assertEquals("평단 미상", position.avgPriceText());
    }
}
