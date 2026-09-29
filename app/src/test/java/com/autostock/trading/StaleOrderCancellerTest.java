package com.autostock.trading;

import com.autostock.common.event.Side;
import com.autostock.execution.BrokerPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
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
        when(orderRepository.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));
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
        TradingProperties properties = new TradingProperties(TradingProperties.Mode.LIVE, Duration.ofMinutes(5));
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
        TradingProperties properties = new TradingProperties(TradingProperties.Mode.LIVE, Duration.ofMinutes(5));
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
    void 취소요청_저장이_충돌하고_그_사이_체결됐으면_취소하지_않는다() {
        TradingProperties properties = new TradingProperties(TradingProperties.Mode.LIVE, Duration.ofMinutes(5));
        StaleOrderCanceller canceller = new StaleOrderCanceller(orderRepository, brokerPort, properties, fixedClock);
        OrderEntity stale = submittedOrderUpdatedAt(NOW.minus(Duration.ofMinutes(6)));
        OrderEntity latest = submittedOrderUpdatedAt(NOW.minus(Duration.ofMinutes(6)));
        latest.applyFill(4); // 목록 조회 뒤 체결 통보가 먼저 저장됐다(R2)
        when(orderRepository.findByStatusIn(anyCollection())).thenReturn(List.of(stale));
        doThrow(new ObjectOptimisticLockingFailureException(OrderEntity.class, 1L))
                .when(orderRepository).save(stale);
        when(orderRepository.findById(any())).thenReturn(Optional.of(latest));

        canceller.cancelStaleOrders();

        assertEquals(OrderStatus.PARTIALLY_FILLED, latest.getStatus());
        verify(orderRepository, never()).save(latest);
        verify(brokerPort, never()).cancelOrder(anyString(), anyString(), anyLong());
    }

    @Test
    void SIM_모드에서는_동작하지_않는다() {
        TradingProperties properties = new TradingProperties(TradingProperties.Mode.SIM, Duration.ofMinutes(5));
        StaleOrderCanceller canceller = new StaleOrderCanceller(orderRepository, brokerPort, properties, fixedClock);

        canceller.cancelStaleOrders();

        verify(orderRepository, never()).findByStatusIn(anyCollection());
    }
}
