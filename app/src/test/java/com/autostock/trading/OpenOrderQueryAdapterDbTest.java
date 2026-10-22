package com.autostock.trading;

import com.autostock.common.event.Side;
import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.Price;
import com.autostock.common.util.Quantity;
import com.autostock.common.util.StockCode;
import com.autostock.support.PostgresDataJpaTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 미체결 매수 조회(실행 계획 1.2) — 실제 PostgreSQL에서 방향·상태 파생 쿼리와 상태 선별을 확인한다.
 * 시험 종목은 운영 코드와 겹치지 않는 T1000x를 쓴다(외부 시험 DB에 다른 행이 있어도 판정이 흔들리지 않게).
 */
class OpenOrderQueryAdapterDbTest extends PostgresDataJpaTest {

    @Autowired
    OrderRepository orders;

    @Test
    void 결과가_확정되지_않은_매수_주문의_종목만_돌려준다() {
        save("T10001", Side.BUY, OrderStatus.SUBMITTED);
        save("T10002", Side.BUY, OrderStatus.UNKNOWN);
        save("T10003", Side.BUY, OrderStatus.CANCEL_REQUESTED);
        save("T10004", Side.BUY, OrderStatus.PARTIALLY_FILLED);
        save("T10005", Side.BUY, OrderStatus.SUBMITTING);
        save("T10011", Side.BUY, OrderStatus.FILLED);
        save("T10012", Side.BUY, OrderStatus.REJECTED);
        save("T10013", Side.BUY, OrderStatus.VALIDATED);    // 전송 전 — 브로커에 없다
        save("T10014", Side.SELL, OrderStatus.SUBMITTED);   // 매도는 보유 예정이 아니다

        Set<StockCode> open = new OpenOrderQueryAdapter(orders).symbolsWithOpenBuy();

        for (String symbol : new String[]{"T10001", "T10002", "T10003", "T10004", "T10005"}) {
            assertTrue(open.contains(new StockCode(symbol)), symbol + " 포함: " + open);
        }
        for (String symbol : new String[]{"T10011", "T10012", "T10013", "T10014"}) {
            assertFalse(open.contains(new StockCode(symbol)), symbol + " 제외: " + open);
        }
    }

    @Test
    void 전략별로_미체결_주문을_거른다_대소문자_무시() {
        // 다전략 슬리브(2026-10-16): 주문 표에는 신호의 전략 ID가 그대로 남는다
        save("T20001", Side.SELL, OrderStatus.SUBMITTED, "VB-DAY");
        save("T20002", Side.SELL, OrderStatus.SUBMITTED, "TA-H1");
        save("T20003", Side.BUY, OrderStatus.SUBMITTED, "vb-day");
        save("T20004", Side.SELL, OrderStatus.FILLED, "VB-DAY");

        OpenOrderQueryAdapter adapter = new OpenOrderQueryAdapter(orders);
        Set<StockCode> sells = adapter.symbolsWithOpenOrder("VB-DAY", Side.SELL);
        Set<StockCode> buys = adapter.symbolsWithOpenOrder("VB-DAY", Side.BUY);

        assertTrue(sells.contains(new StockCode("T20001")));
        assertFalse(sells.contains(new StockCode("T20002")), "다른 전략");
        assertFalse(sells.contains(new StockCode("T20004")), "체결 끝난 주문");
        assertTrue(buys.contains(new StockCode("T20003")), "대소문자 무시");
    }

    private void save(String symbol, Side side, OrderStatus target) {
        save(symbol, side, target, "TEST");
    }

    private void save(String symbol, Side side, OrderStatus target, String strategyId) {
        Instant now = Instant.now();
        OrderEntity order = new OrderEntity("20261002-TEST-" + symbol + "-" + side + "-001", new StockCode(symbol), side,
                new Quantity(10), new Price(new BigDecimal("1000")), strategyId, now);
        order.transitionTo(OrderStatus.VALIDATED, now);
        if (target != OrderStatus.VALIDATED) {
            order.transitionTo(OrderStatus.SUBMITTING, now);
        }
        switch (target) {
            case VALIDATED, SUBMITTING -> { }
            case UNKNOWN, REJECTED -> order.transitionTo(target, now);
            case SUBMITTED -> order.markSubmitted(new BrokerOrderId("B" + symbol), now);
            case CANCEL_REQUESTED -> {
                order.markSubmitted(new BrokerOrderId("B" + symbol), now);
                order.transitionTo(OrderStatus.CANCEL_REQUESTED, now);
            }
            case PARTIALLY_FILLED -> {
                order.markSubmitted(new BrokerOrderId("B" + symbol), now);
                order.applyFill(new Quantity(3), now);
            }
            case FILLED -> {
                order.markSubmitted(new BrokerOrderId("B" + symbol), now);
                order.applyFill(new Quantity(10), now);
            }
            default -> throw new IllegalArgumentException(target.name());
        }
        orders.saveAndFlush(order);
    }
}
