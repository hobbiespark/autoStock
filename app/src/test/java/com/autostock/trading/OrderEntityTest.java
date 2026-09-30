package com.autostock.trading;

import java.time.Clock;
import java.time.Instant;
import com.autostock.common.event.Side;
import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.Price;
import com.autostock.common.util.Quantity;
import com.autostock.common.util.StockCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderEntityTest {

    private OrderEntity order;

    @BeforeEach
    void setUp() {
        order = new OrderEntity("20260813-BREAKOUT-005930-BUY-001", new StockCode("005930"), Side.BUY,
                new Quantity(10), new Price(new BigDecimal("70000")), "BREAKOUT", Instant.now());
    }

    @Test
    void 생성_직후_상태는_CREATED() {
        assertEquals(OrderStatus.CREATED, order.getStatus());
        assertEquals(0, order.getFilledQuantity());
    }

    @Test
    void transitionTo로_합법_전이_수행() {
        order.transitionTo(OrderStatus.VALIDATED, Instant.now());
        order.transitionTo(OrderStatus.SUBMITTING, Instant.now());
        order.transitionTo(OrderStatus.SUBMITTED, Instant.now());

        assertEquals(OrderStatus.SUBMITTED, order.getStatus());
    }

    @Test
    void transitionTo로_불법_전이_시도하면_예외() {
        // CREATED에서 바로 SUBMITTED로 건너뛰는 건 불법
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> order.transitionTo(OrderStatus.SUBMITTED, Instant.now()));

        assertEquals(true, ex.getMessage().contains("CREATED"));
        assertEquals(true, ex.getMessage().contains("SUBMITTED"));
    }

    @Test
    void markSubmitted는_brokerOrderId를_채우고_SUBMITTED로_전이() {
        order.transitionTo(OrderStatus.VALIDATED, Instant.now());
        order.transitionTo(OrderStatus.SUBMITTING, Instant.now());

        order.markSubmitted(new BrokerOrderId("BROKER-1"), Instant.now());

        assertEquals(new BrokerOrderId("BROKER-1"), order.getBrokerOrderId());
        assertEquals(OrderStatus.SUBMITTED, order.getStatus());
    }

    @Test
    void applyFill_부분체결후_PARTIALLY_FILLED() {
        submitOrder();

        order.applyFill(new Quantity(4), Instant.now());

        assertEquals(4, order.getFilledQuantity());
        assertEquals(OrderStatus.PARTIALLY_FILLED, order.getStatus());
    }

    @Test
    void applyFill_누적체결이_수량에_도달하면_FILLED() {
        submitOrder();

        order.applyFill(new Quantity(4), Instant.now());
        order.applyFill(new Quantity(6), Instant.now());

        assertEquals(10, order.getFilledQuantity());
        assertEquals(OrderStatus.FILLED, order.getStatus());
    }

    @Test
    void applyFill_0이하_수량은_예외() {
        submitOrder();

        assertThrows(IllegalArgumentException.class, () -> order.applyFill(new Quantity(0), Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> order.applyFill(new Quantity(-1), Instant.now()));
    }

    @Test
    void 종결_상태에서_추가_체결_시도하면_예외() {
        submitOrder();
        order.applyFill(new Quantity(10), Instant.now()); // FILLED로 종결

        assertThrows(IllegalStateException.class, () -> order.applyFill(new Quantity(1), Instant.now()));
    }

    private void submitOrder() {
        order.transitionTo(OrderStatus.VALIDATED, Instant.now());
        order.transitionTo(OrderStatus.SUBMITTING, Instant.now());
        order.markSubmitted(new BrokerOrderId("BROKER-1"), Instant.now());
    }
}
