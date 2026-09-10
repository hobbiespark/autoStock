package com.autostock.monitor;

import com.autostock.common.event.Side;
import com.autostock.trading.OrderEntity;
import com.autostock.trading.OrderRepository;
import com.autostock.trading.OrderStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderHistoryController — days 기간 필터 보정(기본 7·최대 90)과 View DTO 변환을 검증한다
 * (FE-1, PLAN.md ADR-10 확장표). Domain Entity({@link OrderEntity})가 응답에 그대로
 * 새어나가지 않고 {@code OrderHistoryItemView}로 옮겨 담기는지가 핵심.
 */
class OrderHistoryControllerTest {

    private final OrderRepository repository = mock(OrderRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-11T00:00:00Z"), ZoneOffset.UTC);
    private final OrderHistoryController controller = new OrderHistoryController(repository, clock);

    @Test
    void days_생략시_기본값_7일로_조회한다() {
        when(repository.findBySubmittedAtGreaterThanEqualOrderBySubmittedAtDesc(any())).thenReturn(List.of());

        controller.orders(null);

        verify(repository).findBySubmittedAtGreaterThanEqualOrderBySubmittedAtDesc(
                Instant.parse("2026-09-11T00:00:00Z").minus(7, ChronoUnit.DAYS));
    }

    @Test
    void days_90_초과_요청은_90일로_보정한다() {
        when(repository.findBySubmittedAtGreaterThanEqualOrderBySubmittedAtDesc(any())).thenReturn(List.of());

        controller.orders(365);

        verify(repository).findBySubmittedAtGreaterThanEqualOrderBySubmittedAtDesc(
                Instant.parse("2026-09-11T00:00:00Z").minus(90, ChronoUnit.DAYS));
    }

    @Test
    void 음수_days는_기본값으로_보정한다() {
        when(repository.findBySubmittedAtGreaterThanEqualOrderBySubmittedAtDesc(any())).thenReturn(List.of());

        controller.orders(-5);

        verify(repository).findBySubmittedAtGreaterThanEqualOrderBySubmittedAtDesc(
                Instant.parse("2026-09-11T00:00:00Z").minus(7, ChronoUnit.DAYS));
    }

    @Test
    void OrderEntity를_View_DTO로_변환해서_반환한다() {
        OrderEntity order = new OrderEntity("20260911-C3-005930-BUY-001", "005930", Side.BUY, 10,
                new BigDecimal("70000"), "C3");
        order.transitionTo(OrderStatus.VALIDATED);
        order.transitionTo(OrderStatus.SUBMITTING);
        order.markSubmitted("broker-1");
        when(repository.findBySubmittedAtGreaterThanEqualOrderBySubmittedAtDesc(any()))
                .thenReturn(List.of(order));

        var result = controller.orders(30);

        assertEquals(1, result.orders().size());
        var item = result.orders().get(0);
        assertEquals("20260911-C3-005930-BUY-001", item.clientOrderId());
        assertEquals("005930", item.symbol());
        assertEquals("BUY", item.side());
        assertEquals(10, item.quantity());
        assertEquals(0, item.filledQuantity());
        assertEquals("SUBMITTED", item.status());
        assertEquals("C3", item.strategyId());
        assertTrue(item.limitPrice().compareTo(new BigDecimal("70000")) == 0);
    }
}
