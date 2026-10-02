package com.autostock.trading;

import java.time.Clock;
import java.time.Instant;
import com.autostock.common.event.Side;
import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.Price;
import com.autostock.common.util.Quantity;
import com.autostock.common.util.StockCode;
import com.autostock.support.PostgresDataJpaTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 PostgreSQL에서 주문 저장소 검증 — 값 객체 컨버터(A1 조각 1·4), 파생 쿼리, 낙관적 잠금(R2).
 * 지금까지 목 저장소로만 검증해 "미검증"으로 남아 있던 항목이다(aiDoc/value-objects.md, order-concurrency.md).
 */
class OrderRepositoryDbTest extends PostgresDataJpaTest {

    @Autowired
    OrderRepository orders;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager txManager;

    private static OrderEntity newOrder(String clientOrderId) {
        return new OrderEntity(clientOrderId, new StockCode("005930"), Side.BUY,
                new Quantity(10), new Price(new BigDecimal("259500")), "C3-MOMENTUM", Instant.now());
    }

    @Test
    void 값_객체는_기존_문자열_컬럼으로_저장되고_주문번호로_조회된다() {
        OrderEntity order = newOrder("20260930-C3-005930-BUY-001");
        order.transitionTo(OrderStatus.VALIDATED, Instant.now());
        order.transitionTo(OrderStatus.SUBMITTING, Instant.now());
        order.markSubmitted(new BrokerOrderId("0119433"), Instant.now());
        orders.saveAndFlush(order);

        Map<String, Object> row = jdbc.queryForMap(
                "select symbol, broker_order_id, limit_price, status from orders where client_order_id = ?",
                "20260930-C3-005930-BUY-001");
        assertEquals("005930", row.get("symbol"));
        assertEquals("0119433", row.get("broker_order_id"));
        assertEquals(0, new BigDecimal("259500").compareTo((BigDecimal) row.get("limit_price")));
        assertEquals("SUBMITTED", row.get("status"));

        OrderEntity found = orders.findByBrokerOrderId(new BrokerOrderId("0119433")).orElseThrow();
        assertEquals(new StockCode("005930"), found.getSymbol());
        assertTrue(orders.findByBrokerOrderId(new BrokerOrderId("9999999")).isEmpty());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)   // 트랜잭션을 나눠야 동시 갱신을 재현할 수 있다
    void 같은_주문을_두_곳에서_읽어_고치면_나중_저장이_낙관적_잠금으로_거부된다() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        String key = "20260930-C3-005930-BUY-LOCK";
        try {
            Long id = tx.execute(s -> orders.saveAndFlush(newOrder(key)).getId());
            OrderEntity first = tx.execute(s -> orders.findById(id).orElseThrow());
            OrderEntity second = tx.execute(s -> orders.findById(id).orElseThrow());

            second.transitionTo(OrderStatus.VALIDATED, Instant.now());
            tx.executeWithoutResult(s -> orders.saveAndFlush(second));

            first.transitionTo(OrderStatus.VALIDATED, Instant.now());
            assertThrows(ObjectOptimisticLockingFailureException.class,
                    () -> tx.executeWithoutResult(s -> orders.saveAndFlush(first)));
        } finally {
            jdbc.update("delete from orders where client_order_id = ?", key);
        }
    }
    @Test
    void 상태별_수와_KST_하루_구간의_상태별_수를_센다() {
        // 게이지 orders.unknown.count·일별 OTR(실행 계획 1.7) — 경계: [10/6 00:00 KST, 10/7 00:00 KST)
        save("T20001", "2026-10-05T14:59:59Z", OrderStatus.UNKNOWN);     // 10/5 23:59:59 KST — 밖
        save("T20002", "2026-10-05T15:00:00Z", OrderStatus.UNKNOWN);     // 10/6 00:00 KST — 안
        save("T20003", "2026-10-06T06:00:00Z", OrderStatus.CANCELLED);   // 10/6 15:00 KST — 안
        save("T20004", "2026-10-06T06:00:00Z", OrderStatus.VALIDATED);   // 전송 전 — 상태로 빠진다
        save("T20005", "2026-10-06T15:00:00Z", OrderStatus.CANCELLED);   // 10/7 00:00 KST — 밖
        orders.flush();
        Instant from = Instant.parse("2026-10-05T15:00:00Z");
        Instant to = Instant.parse("2026-10-06T15:00:00Z");

        long unknown = orders.countByStatus(OrderStatus.UNKNOWN);
        long submitted = orders.countBySubmittedAtGreaterThanEqualAndSubmittedAtLessThanAndStatusIn(from, to,
                java.util.EnumSet.complementOf(java.util.EnumSet.of(OrderStatus.CREATED, OrderStatus.VALIDATED)));
        long cancelled = orders.countBySubmittedAtGreaterThanEqualAndSubmittedAtLessThanAndStatusIn(from, to,
                java.util.EnumSet.of(OrderStatus.CANCELLED, OrderStatus.CANCEL_REQUESTED));

        assertTrue(unknown >= 2, "외부 시험 DB에 다른 행이 있어도 최소 2건: " + unknown);
        assertEquals(2, submitted);   // T20002(UNKNOWN), T20003(CANCELLED)
        assertEquals(1, cancelled);   // T20003
    }

    private void save(String symbol, String submittedAt, OrderStatus target) {
        Instant at = Instant.parse(submittedAt);
        OrderEntity order = new OrderEntity("20261006-OTR-" + symbol + "-BUY-001", new StockCode(symbol), Side.BUY,
                new Quantity(1), new Price(new BigDecimal("1000")), "OTR-TEST", at);
        order.transitionTo(OrderStatus.VALIDATED, at);
        if (target != OrderStatus.VALIDATED) {
            order.transitionTo(OrderStatus.SUBMITTING, at);
            order.markSubmitted(new BrokerOrderId("B" + symbol), at);
            if (target == OrderStatus.UNKNOWN) {
                order.transitionTo(OrderStatus.UNKNOWN, at);
            } else if (target == OrderStatus.CANCELLED) {
                order.transitionTo(OrderStatus.CANCEL_REQUESTED, at);
                order.transitionTo(OrderStatus.CANCELLED, at);
            }
        }
        orders.save(order);
    }
}
