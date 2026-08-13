package com.autostock.execution;

import com.autostock.common.event.Fill;
import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutionServiceTest {

    private final List<Object> published = new ArrayList<>();
    private final ApplicationEventPublisher publisher = published::add;

    private ExecutionService service;

    @BeforeEach
    void setUp() {
        service = new ExecutionService(
                new ExecutionProperties(ExecutionProperties.Mode.SIM), null, publisher);
    }

    private OrderRequest order(String idempotencyKey) {
        return new OrderRequest(idempotencyKey, "test-strategy", "005930", Side.BUY,
                10, new BigDecimal("70000"), Instant.now());
    }

    @Test
    void SIM_모드는_즉시_체결_Fill_발행() {
        service.onOrderRequest(order("key-1"));

        assertEquals(1, published.size());
        Fill fill = (Fill) published.get(0);
        assertEquals("key-1", fill.orderIdempotencyKey());
        assertEquals(10, fill.filledQuantity());
    }

    @Test
    void 동일_멱등키_재수신은_무시() {
        service.onOrderRequest(order("key-1"));
        service.onOrderRequest(order("key-1"));

        assertEquals(1, published.size());
    }
}
