package com.autostock.trading;

import com.autostock.common.event.Side;
import com.autostock.execution.BrokerOutstandingOrder;
import com.autostock.execution.BrokerPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReconciliationService 단위테스트 — mock BrokerPort/OrderRepository로 대사 로직만 검증한다.
 */
class ReconciliationServiceTest {

    private OrderRepository orderRepository;
    private BrokerPort brokerPort;
    private ReconciliationService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        brokerPort = mock(BrokerPort.class);
        TradingProperties liveProperties = new TradingProperties(TradingProperties.Mode.LIVE, Duration.ofMinutes(5));
        service = new ReconciliationService(orderRepository, brokerPort, liveProperties);
    }

    private OrderEntity submittedEntity(String clientOrderId, String brokerOrderId) {
        OrderEntity entity = new OrderEntity(clientOrderId, "005930", Side.BUY, 10,
                new BigDecimal("70000"), "BREAKOUT");
        entity.transitionTo(OrderStatus.VALIDATED);
        entity.transitionTo(OrderStatus.SUBMITTING);
        entity.markSubmitted(brokerOrderId);
        return entity;
    }

    @Test
    void 대상이_없으면_브로커_호출_생략() {
        when(orderRepository.findByStatusIn(anyCollection())).thenReturn(List.of());

        service.reconcile();

        verify(brokerPort, never()).outstandingOrders();
    }

    @Test
    void 브로커에_존재하면_UNKNOWN에서_SUBMITTED로_확정() {
        OrderEntity order = submittedEntity("key-1", "BROKER-1");
        order.transitionTo(OrderStatus.UNKNOWN); // 타임아웃으로 UNKNOWN이 됐다고 가정
        when(orderRepository.findByStatusIn(anyCollection())).thenReturn(List.of(order));
        when(brokerPort.outstandingOrders()).thenReturn(
                List.of(new BrokerOutstandingOrder("BROKER-1", "005930", Side.BUY, 10, 10)));

        service.reconcile();

        assertEquals(OrderStatus.SUBMITTED, order.getStatus());
        verify(orderRepository, times(1)).save(order);
    }

    @Test
    void 이미_SUBMITTED면_브로커에_존재해도_상태변화_없음() {
        OrderEntity order = submittedEntity("key-2", "BROKER-2");
        when(orderRepository.findByStatusIn(anyCollection())).thenReturn(List.of(order));
        when(brokerPort.outstandingOrders()).thenReturn(
                List.of(new BrokerOutstandingOrder("BROKER-2", "005930", Side.BUY, 10, 10)));

        service.reconcile();

        assertEquals(OrderStatus.SUBMITTED, order.getStatus());
        verify(orderRepository, never()).save(order);
    }

    @Test
    void 브로커에_없으면_체결조회_TR_미구현으로_UNKNOWN_유지() {
        OrderEntity order = submittedEntity("key-3", "BROKER-3");
        order.transitionTo(OrderStatus.UNKNOWN);
        when(orderRepository.findByStatusIn(anyCollection())).thenReturn(List.of(order));
        when(brokerPort.outstandingOrders()).thenReturn(List.of()); // 브로커 미체결 목록에 없음

        service.reconcile();

        // probeFillStatus가 항상 Optional.empty()인 스텁이므로 자동으로 REJECTED 단정하지 않는다
        assertEquals(OrderStatus.UNKNOWN, order.getStatus());
        verify(orderRepository, never()).save(order);
    }

    @Test
    void brokerOrderId_없는_UNKNOWN_주문은_스킵된다() {
        OrderEntity order = new OrderEntity("key-4", "005930", Side.BUY, 10,
                new BigDecimal("70000"), "BREAKOUT");
        order.transitionTo(OrderStatus.VALIDATED);
        order.transitionTo(OrderStatus.SUBMITTING);
        order.transitionTo(OrderStatus.UNKNOWN); // SUBMITTING 단계에서 타임아웃 — brokerOrderId 없음
        when(orderRepository.findByStatusIn(anyCollection())).thenReturn(List.of(order));
        when(brokerPort.outstandingOrders()).thenReturn(List.of());

        service.reconcile();

        assertEquals(OrderStatus.UNKNOWN, order.getStatus());
        verify(orderRepository, never()).save(any(OrderEntity.class));
    }

    @Test
    void requestReconcile은_단건만_조회해_대사한다() {
        OrderEntity order = submittedEntity("key-5", "BROKER-5");
        order.transitionTo(OrderStatus.UNKNOWN);
        when(orderRepository.findByClientOrderId("key-5")).thenReturn(Optional.of(order));
        when(brokerPort.outstandingOrders()).thenReturn(
                List.of(new BrokerOutstandingOrder("BROKER-5", "005930", Side.BUY, 10, 10)));

        service.requestReconcile("key-5");

        assertEquals(OrderStatus.SUBMITTED, order.getStatus());
        verify(orderRepository, never()).findByStatusIn(anyCollection()); // 전체 대사가 아니라 단건만
    }

    @Test
    void requestReconcile_대상없으면_예외없이_무시() {
        when(orderRepository.findByClientOrderId("missing")).thenReturn(Optional.empty());

        service.requestReconcile("missing"); // 예외 없이 조용히 리턴돼야 한다

        verify(brokerPort, never()).outstandingOrders();
    }

    @Test
    void SIM_모드에서는_startup_이벤트로_대사하지_않는다() {
        TradingProperties simProperties = new TradingProperties(TradingProperties.Mode.SIM, Duration.ofMinutes(5));
        ReconciliationService simService = new ReconciliationService(orderRepository, brokerPort, simProperties);

        simService.onStartup();
        simService.scheduledReconcile();

        verify(orderRepository, never()).findByStatusIn(anyCollection());
    }
}
