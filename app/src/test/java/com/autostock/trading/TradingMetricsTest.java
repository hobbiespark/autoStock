package com.autostock.trading;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 게이지 orders.unknown.count·orders.cancel_requested.count(실행 계획 1.7) — 읽을 때마다 상태별 수를 센다. */
class TradingMetricsTest {

    @Test
    void 결과_불명과_취소_요청_주문_수를_게이지로_낸다() {
        OrderRepository repository = mock(OrderRepository.class);
        when(repository.countByStatus(OrderStatus.UNKNOWN)).thenReturn(2L, 0L);
        when(repository.countByStatus(OrderStatus.CANCEL_REQUESTED)).thenReturn(1L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new TradingMetrics(repository).bindTo(registry);

        assertEquals(2.0, registry.get("orders.unknown.count").gauge().value());
        assertEquals(0.0, registry.get("orders.unknown.count").gauge().value(), "읽을 때마다 다시 센다");
        assertEquals(1.0, registry.get("orders.cancel_requested.count").gauge().value());
    }
}
