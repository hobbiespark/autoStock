package com.autostock.execution;

import com.autostock.common.event.Fill;
import com.autostock.common.event.OrderNotice;
import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * OrderNoticeHandler 단위테스트.
 * ExecutionServiceTest와 같은 스타일: Spring 컨텍스트 없이 직접 조립하고,
 * ApplicationEventPublisher는 List::add로 대체해 발행된 이벤트를 그대로 확인한다.
 */
class OrderNoticeHandlerTest {

    private static final String BROKER_ORDER_ID = "BROKER-1";

    private final List<Object> published = new ArrayList<>();
    private final ApplicationEventPublisher publisher = published::add;

    private ExecutionService executionService;
    private OrderNoticeHandler handler;

    @BeforeEach
    void setUp() {
        // 실제 REST 호출 없이 고정 주문번호만 돌려주는 가짜 KiwoomOrderService.
        // placeOrder()는 final이 아니라 오버라이드로 REST 의존성 없이 대체할 수 있다.
        KiwoomOrderService fakeOrderService = new KiwoomOrderService(null) {
            @Override
            public String placeOrder(OrderRequest request) {
                return BROKER_ORDER_ID;
            }
        };
        executionService = new ExecutionService(
                new ExecutionProperties(ExecutionProperties.Mode.LIVE), fakeOrderService, publisher);
        handler = new OrderNoticeHandler(executionService, publisher);
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
        executionService.onOrderRequest(order("key-1")); // brokerOrderId 매핑 생성

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
        // executionService.onOrderRequest를 호출하지 않았으므로 brokerOrderId 매핑이 없다
        handler.onOrderNotice(notice("체결", 10, new BigDecimal("70100")));

        assertEquals(0, published.size());
    }

    @Test
    void 체결_아닌_상태_통보는_무시() {
        executionService.onOrderRequest(order("key-1"));

        handler.onOrderNotice(notice("접수", 0, null));

        assertEquals(0, published.size());
    }

    @Test
    void 부분체결_2회_수신시_Fill_2회_발행() {
        executionService.onOrderRequest(order("key-2"));

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
}
