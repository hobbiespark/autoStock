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
}
