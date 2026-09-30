package com.autostock.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** BrokerOrderId·Quantity 생성 시 검증 경계값. */
class OrderValueObjectsTest {

    @Test
    void 브로커_주문번호는_빈값과_공백을_거부한다() {
        assertThrows(IllegalArgumentException.class, () -> new BrokerOrderId(null));
        assertThrows(IllegalArgumentException.class, () -> new BrokerOrderId(""));
        assertThrows(IllegalArgumentException.class, () -> new BrokerOrderId("   "));
        assertThrows(IllegalArgumentException.class, () -> new BrokerOrderId("0119 433"));
    }

    @Test
    void 브로커_주문번호는_값으로_같고_문자열은_값_그대로다() {
        assertEquals(new BrokerOrderId("0119433"), new BrokerOrderId("0119433"));
        assertEquals("SIM-1", new BrokerOrderId("SIM-1").toString());
    }

    @Test
    void 수량은_1부터_허용하고_0과_음수는_거부한다() {
        assertThrows(IllegalArgumentException.class, () -> new Quantity(0));
        assertThrows(IllegalArgumentException.class, () -> new Quantity(-1));
        assertEquals(1, new Quantity(1).value());
        assertEquals("10", new Quantity(10).toString());
    }
}
