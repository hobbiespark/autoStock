package com.autostock.trading;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * trading 모듈 게이지 (실행 계획 1.7, aiDoc/observability.md).
 *
 * <ul>
 *   <li>{@code orders.unknown.count} — 결과 불명(UNKNOWN) 주문 수. 0이 아니면 대사가 확정하지 못한 주문이 있다.</li>
 *   <li>{@code orders.cancel_requested.count} — 취소 요청 후 확정되지 않은 주문 수.</li>
 * </ul>
 * 값은 조회할 때마다 DB에서 센다(지표를 읽을 때만 — 수집기가 없어 부하가 없다).
 */
@Component
class TradingMetrics implements MeterBinder {

    private final OrderRepository orderRepository;

    TradingMetrics(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("orders.unknown.count", orderRepository, r -> r.countByStatus(OrderStatus.UNKNOWN))
                .description("결과 불명(UNKNOWN) 주문 수")
                .strongReference(true)
                .register(registry);
        Gauge.builder("orders.cancel_requested.count", orderRepository, r -> r.countByStatus(OrderStatus.CANCEL_REQUESTED))
                .description("취소 요청 후 미확정(CANCEL_REQUESTED) 주문 수")
                .strongReference(true)
                .register(registry);
    }
}
