package com.autostock.execution;

import com.autostock.common.event.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StaleOrderCanceller 단위테스트 — Clock을 고정해 "5분 경과"를 결정론적으로 검증한다.
 */
class StaleOrderCancellerTest {

    private static final Instant NOW = Instant.parse("2026-08-13T10:00:00Z");

    private OrderRepository orderRepository;
    private BrokerPort brokerPort;
    private Clock fixedClock;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        brokerPort = mock(BrokerPort.class);
        fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private OrderEntity submittedOrderUpdatedAt(Instant updatedAt) {
        OrderEntity order = new OrderEntity("20260813-BREAKOUT-005930-BUY-001", "005930", Side.BUY, 10,
                new BigDecimal("70000"), "BREAKOUT");
        order.transitionTo(OrderStatus.VALIDATED);
        order.transitionTo(OrderStatus.SUBMITTING);
        order.markSubmitted("BROKER-1"); // updatedAt = Instant.now() (실제 시각)
        setUpdatedAtViaFill(order, updatedAt);
        return order;
    }

    /** 테스트 전용: updatedAt을 원하는 과거 시각으로 강제하기 위해 리플렉션 대신 재구성한다. */
    private void setUpdatedAtViaFill(OrderEntity order, Instant updatedAt) {
        try {
            var field = OrderEntity.class.getDeclaredField("updatedAt");
            field.setAccessible(true);
            field.set(order, updatedAt);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void 타임아웃_경과한_SUBMITTED_주문은_취소요청된다() {
        ExecutionProperties properties = new ExecutionProperties(ExecutionProperties.Mode.LIVE, Duration.ofMinutes(5));
        StaleOrderCanceller canceller = new StaleOrderCanceller(orderRepository, brokerPort, properties, fixedClock);
        // 6분 전 갱신 — 5분 타임아웃을 넘겼다
        OrderEntity stale = submittedOrderUpdatedAt(NOW.minus(Duration.ofMinutes(6)));
        when(orderRepository.findByStatusIn(anyCollection())).thenReturn(List.of(stale));

        canceller.cancelStaleOrders();

        assertEquals(OrderStatus.CANCEL_REQUESTED, stale.getStatus());
        verify(orderRepository, times(1)).save(stale);
        verify(brokerPort, times(1)).cancelOrder(anyString(), anyString(), anyLong());
    }

    @Test
    void 타임아웃_미경과_주문은_그대로_둔다() {
        ExecutionProperties properties = new ExecutionProperties(ExecutionProperties.Mode.LIVE, Duration.ofMinutes(5));
        StaleOrderCanceller canceller = new StaleOrderCanceller(orderRepository, brokerPort, properties, fixedClock);
        // 1분 전 갱신 — 아직 5분 안 지났다
        OrderEntity fresh = submittedOrderUpdatedAt(NOW.minus(Duration.ofMinutes(1)));
        when(orderRepository.findByStatusIn(anyCollection())).thenReturn(List.of(fresh));

        canceller.cancelStaleOrders();

        assertEquals(OrderStatus.SUBMITTED, fresh.getStatus());
        verify(orderRepository, never()).save(fresh);
        verify(brokerPort, never()).cancelOrder(anyString(), anyString(), anyLong());
    }

    @Test
    void SIM_모드에서는_동작하지_않는다() {
        ExecutionProperties properties = new ExecutionProperties(ExecutionProperties.Mode.SIM, Duration.ofMinutes(5));
        StaleOrderCanceller canceller = new StaleOrderCanceller(orderRepository, brokerPort, properties, fixedClock);

        canceller.cancelStaleOrders();

        verify(orderRepository, never()).findByStatusIn(anyCollection());
    }
}
