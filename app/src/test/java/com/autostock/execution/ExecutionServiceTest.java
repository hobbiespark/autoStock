package com.autostock.execution;

import com.autostock.common.event.Fill;
import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.Side;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ExecutionService 단위테스트 — Spring 컨텍스트 없이 순수 단위로 조립한다.
 * BrokerPort/OrderRepository/ReconciliationService는 Mockito 목으로 대체한다.
 */
class ExecutionServiceTest {

    private final List<Object> published = new ArrayList<>();
    private final ApplicationEventPublisher publisher = published::add;

    private OrderRepository orderRepository;
    private BrokerPort brokerPort;
    private ReconciliationService reconciliationService;

    private ExecutionService simService;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        brokerPort = mock(BrokerPort.class);
        reconciliationService = mock(ReconciliationService.class);

        simService = new ExecutionService(
                new ExecutionProperties(ExecutionProperties.Mode.SIM, Duration.ofMinutes(5)),
                brokerPort, orderRepository, reconciliationService, publisher, new SimpleMeterRegistry());
    }

    private OrderRequest order(String idempotencyKey) {
        return new OrderRequest(idempotencyKey, "test-strategy", "005930", Side.BUY,
                10, new BigDecimal("70000"), Instant.now());
    }

    @Test
    void SIM_모드는_즉시_체결_Fill_발행() {
        simService.onOrderRequest(order("key-1"));

        assertEquals(1, published.size());
        Fill fill = (Fill) published.get(0);
        assertEquals("key-1", fill.orderIdempotencyKey());
        assertEquals(10, fill.filledQuantity());
    }

    @Test
    void SIM_모드도_OrderEntity를_FILLED로_저장한다() {
        simService.onOrderRequest(order("key-1"));

        verify(orderRepository, times(1)).save(any(OrderEntity.class));
    }

    @Test
    void 동일_멱등키_재수신은_무시() {
        simService.onOrderRequest(order("key-1"));
        simService.onOrderRequest(order("key-1"));

        assertEquals(1, published.size());
    }

    @Test
    void LIVE_모드_정상_주문은_SUBMITTED로_저장되고_Fill은_아직_없다() {
        ExecutionService liveService = new ExecutionService(
                new ExecutionProperties(ExecutionProperties.Mode.LIVE, Duration.ofMinutes(5)),
                brokerPort, orderRepository, reconciliationService, publisher, new SimpleMeterRegistry());
        when(brokerPort.placeOrder(any(OrderRequest.class))).thenReturn(new BrokerOrderResult("BROKER-1"));

        liveService.onOrderRequest(order("key-live-1"));

        // LIVE는 이 시점에 Fill을 만들지 않는다 — 체결은 OrderNoticeHandler가 별도로 처리
        assertTrue(published.isEmpty());
        verify(brokerPort, times(1)).placeOrder(any(OrderRequest.class));
        // VALIDATED 저장 → SUBMITTING 저장 → SUBMITTED(markSubmitted) 저장, 최소 3회 이상
        verify(orderRepository, times(3)).save(any(OrderEntity.class));
    }

    @Test
    void LIVE_모드_전송_타임아웃시_UNKNOWN_전이및_reconcile_요청() {
        ExecutionService liveService = new ExecutionService(
                new ExecutionProperties(ExecutionProperties.Mode.LIVE, Duration.ofMinutes(5)),
                brokerPort, orderRepository, reconciliationService, publisher, new SimpleMeterRegistry());
        when(brokerPort.placeOrder(any(OrderRequest.class)))
                .thenThrow(new RuntimeException("simulated timeout"));

        liveService.onOrderRequest(order("key-timeout-1"));

        // 재시도 없이 즉시 UNKNOWN 처리 + 단건 reconcile 요청이 이뤄져야 한다
        verify(brokerPort, times(1)).placeOrder(any(OrderRequest.class));
        verify(reconciliationService, times(1)).requestReconcile("key-timeout-1");
        assertTrue(published.isEmpty());
    }
}
