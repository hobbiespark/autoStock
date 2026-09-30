package com.autostock.trading;

import java.time.Instant;
import com.autostock.common.event.Side;
import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.Price;
import com.autostock.common.util.Quantity;
import com.autostock.common.util.StockCode;
import com.autostock.execution.BrokerOutstandingOrder;
import com.autostock.execution.BrokerPort;
import com.autostock.market.MarketSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
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
    private MarketSessionService marketSession;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        when(orderRepository.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        brokerPort = mock(BrokerPort.class);
        marketSession = mock(MarketSessionService.class);
        when(marketSession.isActive()).thenReturn(true);
        TradingProperties liveProperties = new TradingProperties(TradingProperties.Mode.LIVE, Duration.ofMinutes(5));
        service = new ReconciliationService(orderRepository, brokerPort, liveProperties, marketSession, Clock.systemUTC());
    }

    private OrderEntity submittedEntity(String clientOrderId, String brokerOrderId) {
        OrderEntity entity = new OrderEntity(clientOrderId, new StockCode("005930"), Side.BUY, new Quantity(10),
                new Price(new BigDecimal("70000")), "BREAKOUT", Instant.now());
        entity.transitionTo(OrderStatus.VALIDATED, Instant.now());
        entity.transitionTo(OrderStatus.SUBMITTING, Instant.now());
        entity.markSubmitted(new BrokerOrderId(brokerOrderId), Instant.now());
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
        order.transitionTo(OrderStatus.UNKNOWN, Instant.now()); // 타임아웃으로 UNKNOWN이 됐다고 가정
        when(orderRepository.findByStatusIn(anyCollection())).thenReturn(List.of(order));
        when(brokerPort.outstandingOrders()).thenReturn(
                List.of(new BrokerOutstandingOrder(new BrokerOrderId("BROKER-1"), new StockCode("005930"), Side.BUY, 10, 10)));

        service.reconcile();

        assertEquals(OrderStatus.SUBMITTED, order.getStatus());
        verify(orderRepository, times(1)).save(order);
    }

    @Test
    void 이미_SUBMITTED면_브로커에_존재해도_상태변화_없음() {
        OrderEntity order = submittedEntity("key-2", "BROKER-2");
        when(orderRepository.findByStatusIn(anyCollection())).thenReturn(List.of(order));
        when(brokerPort.outstandingOrders()).thenReturn(
                List.of(new BrokerOutstandingOrder(new BrokerOrderId("BROKER-2"), new StockCode("005930"), Side.BUY, 10, 10)));

        service.reconcile();

        assertEquals(OrderStatus.SUBMITTED, order.getStatus());
        verify(orderRepository, never()).save(order);
    }

    @Test
    void 브로커에_없으면_체결조회_TR_미구현으로_UNKNOWN_유지() {
        OrderEntity order = submittedEntity("key-3", "BROKER-3");
        order.transitionTo(OrderStatus.UNKNOWN, Instant.now());
        when(orderRepository.findByStatusIn(anyCollection())).thenReturn(List.of(order));
        when(brokerPort.outstandingOrders()).thenReturn(List.of()); // 브로커 미체결 목록에 없음

        service.reconcile();

        // probeFillStatus가 항상 Optional.empty()인 스텁이므로 자동으로 REJECTED 단정하지 않는다
        assertEquals(OrderStatus.UNKNOWN, order.getStatus());
        verify(orderRepository, never()).save(order);
    }

    @Test
    void brokerOrderId_없는_UNKNOWN_주문은_스킵된다() {
        OrderEntity order = new OrderEntity("key-4", new StockCode("005930"), Side.BUY, new Quantity(10),
                new Price(new BigDecimal("70000")), "BREAKOUT", Instant.now());
        order.transitionTo(OrderStatus.VALIDATED, Instant.now());
        order.transitionTo(OrderStatus.SUBMITTING, Instant.now());
        order.transitionTo(OrderStatus.UNKNOWN, Instant.now()); // SUBMITTING 단계에서 타임아웃 — brokerOrderId 없음
        when(orderRepository.findByStatusIn(anyCollection())).thenReturn(List.of(order));
        when(brokerPort.outstandingOrders()).thenReturn(List.of());

        service.reconcile();

        assertEquals(OrderStatus.UNKNOWN, order.getStatus());
        verify(orderRepository, never()).save(any(OrderEntity.class));
    }

    @Test
    void requestReconcile은_단건만_조회해_대사한다() {
        OrderEntity order = submittedEntity("key-5", "BROKER-5");
        order.transitionTo(OrderStatus.UNKNOWN, Instant.now());
        when(orderRepository.findByClientOrderId("key-5")).thenReturn(Optional.of(order));
        when(brokerPort.outstandingOrders()).thenReturn(
                List.of(new BrokerOutstandingOrder(new BrokerOrderId("BROKER-5"), new StockCode("005930"), Side.BUY, 10, 10)));

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
        ReconciliationService simService = new ReconciliationService(orderRepository, brokerPort, simProperties, marketSession, Clock.systemUTC());

        simService.onStartup();
        simService.scheduledReconcile();

        verify(orderRepository, never()).findByStatusIn(anyCollection());
    }

    @Test
    void 장외_대기중에는_주기_대사를_쉰다() {
        when(marketSession.isActive()).thenReturn(false);

        service.scheduledReconcile();

        verify(orderRepository, never()).findByStatusIn(anyCollection());
        verify(brokerPort, never()).outstandingOrders();
    }

    @Test
    void 장외_대기중에도_기동_대사는_수행한다() {
        when(marketSession.isActive()).thenReturn(false);

        service.onStartup(); // 재시작 복구는 시각과 무관하게 우선

        verify(orderRepository, times(1)).findByStatusIn(anyCollection());
    }

    // ---- Phase 0.1: CANCEL_REQUESTED 대사 (aiDoc/stale-cancel.md) ----

    private OrderEntity cancelRequestedEntity(String clientOrderId, String brokerOrderId, long filled, Instant requestedAt) {
        OrderEntity entity = submittedEntity(clientOrderId, brokerOrderId);
        if (filled > 0) {
            entity.applyFill(new Quantity(filled), requestedAt);
        }
        entity.transitionTo(OrderStatus.CANCEL_REQUESTED, requestedAt);
        return entity;
    }

    private static BrokerOutstandingOrder outstanding(String brokerOrderId, long remaining) {
        return new BrokerOutstandingOrder(new BrokerOrderId(brokerOrderId), new StockCode("005930"), Side.BUY, 10, remaining);
    }

    @Test
    void 전체_대사_대상에_CANCEL_REQUESTED가_포함된다() {
        when(orderRepository.findByStatusIn(anyCollection())).thenReturn(List.of());

        service.reconcile();

        verify(orderRepository).findByStatusIn(
                List.of(OrderStatus.UNKNOWN, OrderStatus.SUBMITTED, OrderStatus.CANCEL_REQUESTED));
    }

    @Test
    void 취소요청_후_유예가_지나도_브로커_미체결에_남아있으면_SUBMITTED로_되돌린다() {
        OrderEntity order = cancelRequestedEntity("key-c1", "BROKER-C1", 0, Instant.now().minus(Duration.ofMinutes(2)));
        when(orderRepository.findByStatusIn(anyCollection())).thenReturn(List.of(order));
        when(brokerPort.outstandingOrders()).thenReturn(List.of(outstanding("BROKER-C1", 10)));

        service.reconcile();

        // 취소 미반영(요청 저장 직후 앱 중단 등) — 미체결로 되돌려 타임아웃 취소가 다시 취소하게 한다
        assertEquals(OrderStatus.SUBMITTED, order.getStatus());
        verify(orderRepository, times(1)).save(order);
    }

    @Test
    void 부분체결_주문의_취소가_미반영이면_PARTIALLY_FILLED로_되돌리고_체결분은_보존한다() {
        OrderEntity order = cancelRequestedEntity("key-c2", "BROKER-C2", 4, Instant.now().minus(Duration.ofMinutes(2)));
        when(orderRepository.findByStatusIn(anyCollection())).thenReturn(List.of(order));
        when(brokerPort.outstandingOrders()).thenReturn(List.of(outstanding("BROKER-C2", 6)));

        service.reconcile();

        assertEquals(OrderStatus.PARTIALLY_FILLED, order.getStatus());
        assertEquals(4L, order.getFilledQuantity());
    }

    @Test
    void 취소요청_직후에는_브로커_미체결에_남아있어도_건드리지_않는다() {
        // 진행 중인 취소(브로커 호출 수 초)와 겹치지 않게 유예 1분 안에는 판단하지 않는다
        OrderEntity order = cancelRequestedEntity("key-c3", "BROKER-C3", 0, Instant.now());
        when(orderRepository.findByStatusIn(anyCollection())).thenReturn(List.of(order));
        when(brokerPort.outstandingOrders()).thenReturn(List.of(outstanding("BROKER-C3", 10)));

        service.reconcile();

        assertEquals(OrderStatus.CANCEL_REQUESTED, order.getStatus());
        verify(orderRepository, never()).save(order);
    }

    @Test
    void 취소요청_주문이_브로커_미체결에_없으면_단정하지_않고_CANCEL_REQUESTED를_유지한다() {
        OrderEntity order = cancelRequestedEntity("key-c4", "BROKER-C4", 0, Instant.now().minus(Duration.ofMinutes(2)));
        when(orderRepository.findByStatusIn(anyCollection())).thenReturn(List.of(order));
        when(brokerPort.outstandingOrders()).thenReturn(List.of());

        service.reconcile();

        // 취소됐는지 체결됐는지는 체결내역 조회 TR 없이는 모른다 — CANCELLED로 단정하지 않는다(1.6에서 확장)
        assertEquals(OrderStatus.CANCEL_REQUESTED, order.getStatus());
        verify(orderRepository, never()).save(order);
    }
}
