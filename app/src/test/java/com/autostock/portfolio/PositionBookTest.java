package com.autostock.portfolio;

import com.autostock.common.event.Fill;
import com.autostock.common.event.PositionRestored;
import com.autostock.common.event.Side;
import com.autostock.common.util.StockCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PositionBookTest {

    private final PositionBook book = new PositionBook();

    private Fill fill(Side side, long qty, String price) {
        return new Fill("k", "b", new StockCode("005930"), side, qty, new BigDecimal(price), Instant.now());
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
        book.onPositionRestored(new PositionRestored(new StockCode("000660"), 3, new BigDecimal("200000")));
        book.onFill(fill(Side.BUY, 10, "70000"));
        book.onPositionRestored(new PositionRestored(new StockCode("005930"), 19, new BigDecimal("259974")));

        assertEquals(3, book.get(new StockCode("000660")).quantity());
        assertEquals(10, book.get(new StockCode("005930")).quantity());
        assertEquals(2, book.openPositionCount());
    }
}
