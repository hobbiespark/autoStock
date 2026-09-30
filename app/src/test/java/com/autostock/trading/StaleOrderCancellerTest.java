package com.autostock.trading;

import com.autostock.common.event.Side;
import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.Price;
import com.autostock.common.util.Quantity;
import com.autostock.common.util.StockCode;
import com.autostock.execution.BrokerPort;
import com.autostock.market.MarketSessionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StaleOrderCanceller 단위테스트 — Clock을 고정해 "5분 경과"를 결정론적으로 검증한다.
 *
 * <p>Phase 0.1(aiDoc/stale-cancel.md): 취소는 실제 {@link TradingService#requestCancel} 경로를 타므로
 * TradingService는 진짜 객체로 만들고, 저장소·브로커·대사만 모킹한다 — "취소 요청 → 브로커 취소 →
 * CANCELLED 확정"까지 한 번에 검증한다.
 */
class StaleOrderCancellerTest {

    private static final Instant NOW = Instant.parse("2026-08-13T10:00:00Z");
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    private OrderRepository orderRepository;
    private BrokerPort brokerPort;
    private ReconciliationService reconciliationService;
    private Clock fixedClock;
    private MarketSessionService marketSession;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        when(orderRepository.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        brokerPort = mock(BrokerPort.class);
        reconciliationService = mock(ReconciliationService.class);
        marketSession = mock(MarketSessionService.class);
        when(marketSession.isActive()).thenReturn(true);
        fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private StaleOrderCanceller canceller(TradingProperties.Mode mode) {
        TradingProperties properties = new TradingProperties(mode, TIMEOUT);
        TradingService tradingService = new TradingService(properties, brokerPort, orderRepository,
                reconciliationService, mock(ApplicationEventPublisher.class), new SimpleMeterRegistry(), fixedClock);
        return new StaleOrderCanceller(orderRepository, tradingService, properties, fixedClock, marketSession);
    }

    private static OrderEntity submittedOrder(String clientOrderId, String brokerOrderId, Instant updatedAt) {
        // 엔티티가 시각을 인자로 받으므로(A4 ③) 원하는 과거 시각에 접수된 주문을 그대로 만든다 — 예전에는 리플렉션으로 강제했다
        OrderEntity order = new OrderEntity(clientOrderId, new StockCode("005930"), Side.BUY, new Quantity(10),
                new Price(new BigDecimal("70000")), "BREAKOUT", updatedAt);
        order.transitionTo(OrderStatus.VALIDATED, updatedAt);
        order.transitionTo(OrderStatus.SUBMITTING, updatedAt);
        order.markSubmitted(new BrokerOrderId(brokerOrderId), updatedAt);
        return order;
    }

    private static OrderEntity submittedOrder(Instant updatedAt) {
        return submittedOrder("20260813-BREAKOUT-005930-BUY-001", "BROKER-1", updatedAt);
    }

    private static OrderEntity acceptedOrder(Instant updatedAt) {
        OrderEntity order = submittedOrder(updatedAt);
        order.transitionTo(OrderStatus.ACCEPTED, updatedAt); // WS "접수" 통보 반영 — LIVE에선 대부분 이 상태로 머문다
        return order;
    }

    private static OrderEntity partiallyFilledOrder(long filled, Instant lastFillAt) {
        OrderEntity order = acceptedOrder(lastFillAt);
        order.applyFill(new Quantity(filled), lastFillAt);
        return order;
    }

    /** 목록 조회 결과와 requestCancel의 최신 조회 결과를 같은 주문으로 맞춘다. */
    private void listAndLookup(OrderEntity... orders) {
        when(orderRepository.findByStatusIn(anyCollection())).thenReturn(List.of(orders));
        for (OrderEntity order : orders) {
            when(orderRepository.findByClientOrderId(order.getClientOrderId())).thenReturn(Optional.of(order));
        }
    }

    @Test
    void 대상_상태는_SUBMITTED_ACCEPTED_PARTIALLY_FILLED다() {
        when(orderRepository.findByStatusIn(anyCollection())).thenReturn(List.of());

        canceller(TradingProperties.Mode.LIVE).cancelStaleOrders();

        verify(orderRepository).findByStatusIn(
                List.of(OrderStatus.SUBMITTED, OrderStatus.ACCEPTED, OrderStatus.PARTIALLY_FILLED));
    }

    @Test
    void 타임아웃_경과한_SUBMITTED_주문은_취소되어_CANCELLED로_확정된다() {
        OrderEntity stale = submittedOrder(NOW.minus(Duration.ofMinutes(6))); // 5분 타임아웃을 넘겼다
        listAndLookup(stale);

        canceller(TradingProperties.Mode.LIVE).cancelStaleOrders();

        assertEquals(OrderStatus.CANCELLED, stale.getStatus());
        verify(brokerPort).cancelOrder(new BrokerOrderId("BROKER-1"), new StockCode("005930"), 0L); // 0 = 잔량 전량
    }

    @Test
    void 타임아웃_경과한_ACCEPTED_주문도_취소된다() {
        // 감사 BE-P0-3: 예전에는 SUBMITTED만 봐서 WS 접수 통보를 받은 주문은 영영 취소되지 않았다
        OrderEntity stale = acceptedOrder(NOW.minus(Duration.ofMinutes(6)));
        listAndLookup(stale);

        canceller(TradingProperties.Mode.LIVE).cancelStaleOrders();

        assertEquals(OrderStatus.CANCELLED, stale.getStatus());
        verify(brokerPort).cancelOrder(new BrokerOrderId("BROKER-1"), new StockCode("005930"), 0L);
    }

    @Test
    void 마지막_체결_후_정체된_부분체결_주문은_잔량만_취소되고_체결분은_보존된다() {
        OrderEntity stale = partiallyFilledOrder(4, NOW.minus(Duration.ofMinutes(6)));
        listAndLookup(stale);

        canceller(TradingProperties.Mode.LIVE).cancelStaleOrders();

        assertEquals(OrderStatus.CANCELLED, stale.getStatus());
        assertEquals(4L, stale.getFilledQuantity());
        verify(brokerPort).cancelOrder(new BrokerOrderId("BROKER-1"), new StockCode("005930"), 0L);
    }

    @Test
    void 타임아웃_미경과_주문은_그대로_둔다() {
        OrderEntity fresh = acceptedOrder(NOW.minus(Duration.ofMinutes(1))); // 아직 5분 안 지났다
        listAndLookup(fresh);

        canceller(TradingProperties.Mode.LIVE).cancelStaleOrders();

        assertEquals(OrderStatus.ACCEPTED, fresh.getStatus());
        verify(orderRepository, never()).save(any(OrderEntity.class));
        verify(orderRepository, never()).findByClientOrderId(anyString());
        verify(brokerPort, never()).cancelOrder(any(), any(), anyLong());
    }

    @Test
    void 목록_조회_뒤_체결이_먼저_반영됐으면_취소하지_않는다() {
        OrderEntity listed = acceptedOrder(NOW.minus(Duration.ofMinutes(6)));
        OrderEntity latest = partiallyFilledOrder(4, NOW.minus(Duration.ofSeconds(10))); // 방금 체결 — 더 이상 정체 아님
        when(orderRepository.findByStatusIn(anyCollection())).thenReturn(List.of(listed));
        when(orderRepository.findByClientOrderId(listed.getClientOrderId())).thenReturn(Optional.of(latest));

        canceller(TradingProperties.Mode.LIVE).cancelStaleOrders();

        assertEquals(OrderStatus.PARTIALLY_FILLED, latest.getStatus());
        verify(orderRepository, never()).save(any(OrderEntity.class));
        verify(brokerPort, never()).cancelOrder(any(), any(), anyLong());
    }

    @Test
    void 취소요청_저장이_충돌하면_최신_상태로_다시_판정해_체결이_반영됐으면_취소하지_않는다() {
        OrderEntity stale = submittedOrder(NOW.minus(Duration.ofMinutes(6)));
        OrderEntity latest = partiallyFilledOrder(4, NOW.minus(Duration.ofSeconds(10))); // 저장 직전 체결 통보가 먼저 저장됐다(R2)
        when(orderRepository.findByStatusIn(anyCollection())).thenReturn(List.of(stale));
        when(orderRepository.findByClientOrderId(stale.getClientOrderId()))
                .thenReturn(Optional.of(stale), Optional.of(latest));
        doThrow(new ObjectOptimisticLockingFailureException(OrderEntity.class, 1L))
                .when(orderRepository).save(stale);

        canceller(TradingProperties.Mode.LIVE).cancelStaleOrders();

        assertEquals(OrderStatus.PARTIALLY_FILLED, latest.getStatus());
        verify(orderRepository, never()).save(latest);
        verify(brokerPort, never()).cancelOrder(any(), any(), anyLong());
    }

    @Test
    void 브로커_취소가_실패하면_UNKNOWN으로_남기고_대사를_요청한다() {
        OrderEntity stale = acceptedOrder(NOW.minus(Duration.ofMinutes(6)));
        listAndLookup(stale);
        doThrow(new RuntimeException("네트워크 오류")).when(brokerPort).cancelOrder(any(), any(), anyLong());

        canceller(TradingProperties.Mode.LIVE).cancelStaleOrders();

        assertEquals(OrderStatus.UNKNOWN, stale.getStatus());
        verify(reconciliationService).requestReconcile(stale.getClientOrderId());
    }

    @Test
    void 한_건이_실패해도_나머지_주문은_계속_점검한다() {
        OrderEntity broken = submittedOrder("20260813-BREAKOUT-005930-BUY-001", "BROKER-1", NOW.minus(Duration.ofMinutes(6)));
        OrderEntity next = submittedOrder("20260813-BREAKOUT-005930-BUY-002", "BROKER-2", NOW.minus(Duration.ofMinutes(6)));
        when(orderRepository.findByStatusIn(anyCollection())).thenReturn(List.of(broken, next));
        when(orderRepository.findByClientOrderId(broken.getClientOrderId())).thenThrow(new RuntimeException("DB 일시 오류"));
        when(orderRepository.findByClientOrderId(next.getClientOrderId())).thenReturn(Optional.of(next));

        canceller(TradingProperties.Mode.LIVE).cancelStaleOrders();

        assertEquals(OrderStatus.SUBMITTED, broken.getStatus());
        assertEquals(OrderStatus.CANCELLED, next.getStatus());
        verify(brokerPort).cancelOrder(new BrokerOrderId("BROKER-2"), new StockCode("005930"), 0L);
    }

    @Test
    void SIM_모드에서는_동작하지_않는다() {
        canceller(TradingProperties.Mode.SIM).cancelStaleOrders();

        verify(orderRepository, never()).findByStatusIn(anyCollection());
    }

    @Test
    void 장외_대기중에는_미체결_취소_점검을_쉰다() {
        when(marketSession.isActive()).thenReturn(false);

        canceller(TradingProperties.Mode.LIVE).cancelStaleOrders();

        verify(orderRepository, never()).findByStatusIn(anyCollection());
        verify(brokerPort, never()).cancelOrder(any(), any(), anyLong());
    }
}
