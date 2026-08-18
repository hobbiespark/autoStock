package com.autostock.trading;

import com.autostock.common.event.Fill;
import com.autostock.common.event.OrderNotice;
import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.Side;
import com.autostock.execution.BrokerOrderResult;
import com.autostock.execution.BrokerPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OrderNoticeHandler 단위테스트.
 * TradingServiceTest와 같은 스타일: Spring 컨텍스트 없이 직접 조립하고,
 * ApplicationEventPublisher는 List::add로 대체해 발행된 이벤트를 그대로 확인한다.
 * BrokerPort/OrderRepository/ReconciliationService는 Mockito 목으로 대체한다.
 */
class OrderNoticeHandlerTest {

    private static final String BROKER_ORDER_ID = "BROKER-1";

    private final List<Object> published = new ArrayList<>();
    private final ApplicationEventPublisher publisher = published::add;

    private OrderRepository orderRepository;
    private TradingService tradingService;
    private OrderNoticeHandler handler;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        BrokerPort brokerPort = mock(BrokerPort.class);
        when(brokerPort.placeOrder(any(OrderRequest.class))).thenReturn(new BrokerOrderResult(BROKER_ORDER_ID));
        ReconciliationService reconciliationService = mock(ReconciliationService.class);

        tradingService = new TradingService(
                new TradingProperties(TradingProperties.Mode.LIVE, Duration.ofMinutes(5)),
                brokerPort, orderRepository, reconciliationService, publisher, new SimpleMeterRegistry());
        handler = new OrderNoticeHandler(tradingService, orderRepository, publisher);
    }

    private OrderRequest order(String idempotencyKey) {
        return new OrderRequest(idempotencyKey, "test-strategy", "005930", Side.BUY,
                10, new BigDecimal("70000"), Instant.now());
    }

    private OrderNotice notice(String status, long filledQuantity, BigDecimal fillPrice) {
        return new OrderNotice(BROKER_ORDER_ID, "005930", status, filledQuantity, fillPrice, "00", Instant.now());
    }

    @Test
    void 체결_통보_수신시_Fill_발행() {
        tradingService.onOrderRequest(order("key-1")); // brokerOrderId 매핑 생성(인메모리)

        handler.onOrderNotice(notice("체결", 10, new BigDecimal("70100")));

        assertEquals(1, published.size());
        Fill fill = (Fill) published.get(0);
        assertEquals("key-1", fill.orderIdempotencyKey());
        assertEquals(BROKER_ORDER_ID, fill.brokerOrderId());
        assertEquals("005930", fill.symbol());
        assertEquals(Side.BUY, fill.side());
        assertEquals(10, fill.filledQuantity());
        assertEquals(new BigDecimal("70100"), fill.fillPrice());
    }

    @Test
    void 매핑_없는_통보는_무시() {
        // tradingService.onOrderRequest를 호출하지 않았고, DB 폴백도 비어있으므로(mock 기본값)
        // 인메모리·DB 둘 다 매핑이 없다
        handler.onOrderNotice(notice("체결", 10, new BigDecimal("70100")));

        assertEquals(0, published.size());
    }

    @Test
    void 체결_아닌_상태_통보는_무시() {
        tradingService.onOrderRequest(order("key-1"));

        handler.onOrderNotice(notice("접수", 0, null));

        assertEquals(0, published.size());
    }

    @Test
    void 부분체결_2회_수신시_Fill_2회_발행() {
        tradingService.onOrderRequest(order("key-2"));

        handler.onOrderNotice(notice("부분체결", 4, new BigDecimal("70000")));
        handler.onOrderNotice(notice("체결", 6, new BigDecimal("70050")));

        assertEquals(2, published.size());
        Fill first = (Fill) published.get(0);
        Fill second = (Fill) published.get(1);
        assertEquals(4, first.filledQuantity());
        assertEquals(6, second.filledQuantity());
        // 두 통보 모두 같은 원 주문(key-2)에 연결돼야 한다
        assertEquals("key-2", first.orderIdempotencyKey());
        assertEquals("key-2", second.orderIdempotencyKey());
    }

    @Test
    void 인메모리_매핑_유실시_DB_폴백으로_체결통보_처리() {
        // 재시작 시나리오 시뮬레이션: tradingService.onOrderRequest를 호출하지 않아
        // 인메모리 맵은 비어있지만, DB에는 SUBMITTED 상태 주문이 남아있다고 가정한다.
        OrderEntity entity = new OrderEntity("20260813-BREAKOUT-005930-BUY-001", "005930", Side.SELL,
                10, new BigDecimal("70000"), "BREAKOUT");
        entity.transitionTo(OrderStatus.VALIDATED);
        entity.transitionTo(OrderStatus.SUBMITTING);
        entity.markSubmitted(BROKER_ORDER_ID);
        when(orderRepository.findByBrokerOrderId(BROKER_ORDER_ID)).thenReturn(Optional.of(entity));

        handler.onOrderNotice(notice("체결", 10, new BigDecimal("70100")));

        assertEquals(1, published.size());
        Fill fill = (Fill) published.get(0);
        assertEquals("20260813-BREAKOUT-005930-BUY-001", fill.orderIdempotencyKey());
        assertEquals(Side.SELL, fill.side()); // DB 엔티티의 side를 물려받는다
        assertEquals(OrderStatus.FILLED, entity.getStatus()); // applyFill로 상태도 갱신됐다
    }
}
