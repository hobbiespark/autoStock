package com.autostock.common.util;

import com.autostock.common.event.Side;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientOrderIdTest {

    @Test
    void 생성_포맷_확인() {
        ClientOrderId id = ClientOrderId.generate(
                LocalDate.of(2026, 8, 13), "breakout", "005930", Side.BUY, 5);

        assertEquals("20260813-BREAKOUT-005930-BUY-005", id.value());
    }

    @Test
    void 전략ID는_대문자로_정규화() {
        ClientOrderId id = ClientOrderId.generate(
                LocalDate.of(2026, 8, 13), "momentum", "005930", Side.SELL, 1);

        assertEquals("MOMENTUM", id.strategyId());
    }

    @Test
    void 파싱하면_원래_구성요소를_되돌려준다() {
        ClientOrderId id = ClientOrderId.parse("20260813-BREAKOUT-005930-BUY-001");

        assertEquals(LocalDate.of(2026, 8, 13), id.date());
        assertEquals("BREAKOUT", id.strategyId());
        assertEquals("005930", id.symbol());
        assertEquals(Side.BUY, id.side());
        assertEquals(1, id.sequence());
    }

    @Test
    void 전략ID에_하이픈이_있어도_파싱된다() {
        ClientOrderId id = ClientOrderId.generate(
                LocalDate.of(2026, 8, 13), "test-strategy", "005930", Side.BUY, 42);

        assertEquals("TEST-STRATEGY", id.strategyId());
        assertEquals("005930", id.symbol());
        assertEquals(42, id.sequence());
    }

    @Test
    void 잘못된_포맷은_예외() {
        assertThrows(IllegalArgumentException.class, () -> ClientOrderId.parse("not-a-valid-id"));
    }

    @Test
    void 잘못된_방향_문자열은_예외() {
        assertThrows(IllegalArgumentException.class,
                () -> ClientOrderId.parse("20260813-BREAKOUT-005930-HOLD-001"));
    }

    @Test
    void 종목코드_길이_위반시_예외() {
        assertThrows(IllegalArgumentException.class,
                () -> ClientOrderId.generate(LocalDate.of(2026, 8, 13), "breakout", "12345", Side.BUY, 1));
    }

    @Test
    void 일련번호_범위_위반시_예외() {
        assertThrows(IllegalArgumentException.class,
                () -> ClientOrderId.generate(LocalDate.of(2026, 8, 13), "breakout", "005930", Side.BUY, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ClientOrderId.generate(LocalDate.of(2026, 8, 13), "breakout", "005930", Side.BUY, 1000));
    }
}
