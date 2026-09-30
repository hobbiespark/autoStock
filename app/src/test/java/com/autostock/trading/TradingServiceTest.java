package com.autostock.trading;

import com.autostock.common.event.CancelRequest;
import com.autostock.common.event.Fill;
import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.Side;
import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.Quantity;
import com.autostock.execution.BrokerOrderResult;
import com.autostock.execution.BrokerPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TradingService 단위테스트 — Spring 컨텍스트 없이 순수 단위로 조립한다.
 * BrokerPort/OrderRepository/ReconciliationService는 Mockito 목으로 대체한다.
 */
class TradingServiceTest {

    private final List<Object> published = new ArrayList<>();
    private final ApplicationEventPublisher publisher = published::add;

    private OrderRepository orderRepository;
    private BrokerPort brokerPort;
    private ReconciliationService reconciliationService;

    private TradingService simService;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        when(orderRepository.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        brokerPort = mock(BrokerPort.class);
        reconciliationService = mock(ReconciliationService.class);

        simService = new TradingService(
                new TradingProperties(TradingProperties.Mode.SIM, Duration.ofMinutes(5)),
                brokerPort, orderRepository, reconciliationService, publisher, new SimpleMeterRegistry(), Clock.systemUTC());
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
        TradingService liveService = new TradingService(
                new TradingProperties(TradingProperties.Mode.LIVE, Duration.ofMinutes(5)),
                brokerPort, orderRepository, reconciliationService, publisher, new SimpleMeterRegistry(), Clock.systemUTC());
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
        TradingService liveService = new TradingService(
                new TradingProperties(TradingProperties.Mode.LIVE, Duration.ofMinutes(5)),
                brokerPort, orderRepository, reconciliationService, publisher, new SimpleMeterRegistry(), Clock.systemUTC());
        when(brokerPort.placeOrder(any(OrderRequest.class)))
                .thenThrow(new RuntimeException("simulated timeout"));

        liveService.onOrderRequest(order("key-timeout-1"));

        // 재시도 없이 즉시 UNKNOWN 처리 + 단건 reconcile 요청이 이뤄져야 한다
        verify(brokerPort, times(1)).placeOrder(any(OrderRequest.class));
        verify(reconciliationService, times(1)).requestReconcile("key-timeout-1");
        assertTrue(published.isEmpty());
    }

    // ── 낙관적 잠금(R2) ─────────────────────────────────────────────────────

    private TradingService liveService() {
        return new TradingService(
                new TradingProperties(TradingProperties.Mode.LIVE, Duration.ofMinutes(5)),
                brokerPort, orderRepository, reconciliationService, publisher, new SimpleMeterRegistry(), Clock.systemUTC());
    }

    private static OrderEntity submitted(String clientOrderId, long quantity) {
        OrderEntity entity = new OrderEntity(clientOrderId, "005930", Side.BUY, new Quantity(quantity),
                new BigDecimal("70000"), "test-strategy");
        entity.transitionTo(OrderStatus.VALIDATED);
        entity.transitionTo(OrderStatus.SUBMITTING);
        entity.markSubmitted(new BrokerOrderId("BROKER-1"));
        return entity;
    }

    @Test
    void LIVE_주문은_버전이_오른_저장_반환본으로_이어서_저장한다() {
        // 실제 JPA의 save(merge)는 버전이 오른 새 사본을 돌려준다 — 옛 인스턴스를 다시 저장하면 충돌한다.
        List<OrderEntity> saveArgs = new ArrayList<>();
        List<OrderEntity> saveResults = new ArrayList<>();
        when(orderRepository.save(any(OrderEntity.class))).thenAnswer(inv -> {
            OrderEntity arg = inv.getArgument(0);
            saveArgs.add(arg);
            OrderEntity copy = copyOf(arg);
            saveResults.add(copy);
            return copy;
        });
        when(brokerPort.placeOrder(any(OrderRequest.class))).thenReturn(new BrokerOrderResult("BROKER-1"));

        liveService().onOrderRequest(order("key-live-copy"));

        assertEquals(3, saveArgs.size());
        assertSame(saveResults.get(1), saveArgs.get(2)); // SUBMITTING 저장 반환본에 markSubmitted
        assertEquals(OrderStatus.SUBMITTED, saveArgs.get(2).getStatus());
    }

    @Test
    void 취소_중_부분체결이_반영됐으면_체결분을_보존하고_잔량만_취소_확정한다() {
        OrderEntity beforeCancel = submitted("key-cancel", 10);
        // 브로커 취소 응답을 기다리는 동안 WS 체결 통보가 3주를 반영해 저장했다(R2 F1)
        OrderEntity latest = submitted("key-cancel", 10);
        latest.transitionTo(OrderStatus.CANCEL_REQUESTED);
        latest.applyFill(new Quantity(3));
        when(orderRepository.findByClientOrderId("key-cancel"))
                .thenReturn(Optional.of(beforeCancel), Optional.of(latest));

        liveService().onCancelRequest(new CancelRequest("key-cancel", "test", Instant.now()));

        assertEquals(OrderStatus.CANCELLED, latest.getStatus());
        assertEquals(3, latest.getFilledQuantity()); // 옛 사본(filled 0)으로 덮이지 않았다
        verify(orderRepository, times(1)).save(latest);
        verify(reconciliationService, never()).requestReconcile(any());
    }

    @Test
    void 취소_거부_시점에_이미_전량체결됐으면_FILLED를_유지하고_대사를_요청한다() {
        OrderEntity beforeCancel = submitted("key-filled", 10);
        OrderEntity latest = submitted("key-filled", 10);
        latest.applyFill(new Quantity(10));
        when(orderRepository.findByClientOrderId("key-filled"))
                .thenReturn(Optional.of(beforeCancel), Optional.of(latest));
        doThrow(new RuntimeException("이미 체결된 주문")).when(brokerPort).cancelOrder(any(), any(), anyLong());

        liveService().onCancelRequest(new CancelRequest("key-filled", "test", Instant.now()));

        assertEquals(OrderStatus.FILLED, latest.getStatus()); // UNKNOWN으로 되돌리지 않는다
        assertEquals(10, latest.getFilledQuantity());
        verify(orderRepository, never()).save(latest);
        verify(reconciliationService, times(1)).requestReconcile("key-filled");
    }

    @Test
    void 취소요청_저장이_충돌하면_최신_상태로_다시_판정한다() {
        OrderEntity stale = submitted("key-race", 10);
        OrderEntity latest = submitted("key-race", 10);
        latest.applyFill(new Quantity(10)); // 그 사이 전량 체결 → 취소 불가
        when(orderRepository.findByClientOrderId("key-race"))
                .thenReturn(Optional.of(stale), Optional.of(latest));
        doThrow(new ObjectOptimisticLockingFailureException(OrderEntity.class, 1L))
                .when(orderRepository).save(stale);

        liveService().onCancelRequest(new CancelRequest("key-race", "test", Instant.now()));

        verify(brokerPort, never()).cancelOrder(any(), any(), anyLong());
        assertEquals(OrderStatus.FILLED, latest.getStatus());
    }

    /** 저장 반환본 흉내 — 같은 값·상태를 가진 다른 인스턴스. */
    private static OrderEntity copyOf(OrderEntity source) {
        OrderEntity copy = new OrderEntity(source.getClientOrderId(), source.getSymbol(), source.getSide(),
                new Quantity(source.getQuantity()), source.getLimitPrice(), source.getStrategyId());
        OrderStatus status = source.getStatus();
        if (status == OrderStatus.CREATED) {
            return copy;
        }
        copy.transitionTo(OrderStatus.VALIDATED);
        if (status == OrderStatus.VALIDATED) {
            return copy;
        }
        copy.transitionTo(OrderStatus.SUBMITTING);
        if (status == OrderStatus.SUBMITTING) {
            return copy;
        }
        copy.markSubmitted(source.getBrokerOrderId());
        return copy;
    }
}
