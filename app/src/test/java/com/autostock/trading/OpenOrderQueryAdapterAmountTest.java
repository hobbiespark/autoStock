package com.autostock.trading;

import com.autostock.common.event.Side;
import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.Price;
import com.autostock.common.util.Quantity;
import com.autostock.common.util.StockCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 미체결 매수 남은 금액(2026-10-21, 운용 자금 한도) — 지정가 × (주문 수량 − 체결 수량)의 합. */
class OpenOrderQueryAdapterAmountTest {

    private static OrderEntity submittedBuy(String symbol, long qty, String price) {
        Instant now = Instant.now();
        OrderEntity order = new OrderEntity("20261022-TEST-" + symbol + "-BUY-001", new StockCode(symbol), Side.BUY,
                new Quantity(qty), new Price(new BigDecimal(price)), "TEST", now);
        order.transitionTo(OrderStatus.VALIDATED, now);
        order.transitionTo(OrderStatus.SUBMITTING, now);
        order.markSubmitted(new BrokerOrderId("B" + symbol), now);
        return order;
    }

    @Test
    void 미체결_매수_금액은_지정가_곱하기_남은_수량의_합이다() {
        OrderEntity partial = submittedBuy("T30001", 10, "1000");
        partial.applyFill(new Quantity(3), Instant.now());          // 남은 7주 × 1,000 = 7,000
        OrderEntity open = submittedBuy("T30002", 5, "2000");        // 5주 × 2,000 = 10,000
        OrderRepository repository = mock(OrderRepository.class);
        when(repository.findBySideAndStatusIn(eq(Side.BUY), any())).thenReturn(List.of(partial, open));

        BigDecimal amount = new OpenOrderQueryAdapter(repository).openBuyAmount();

        assertEquals(0, new BigDecimal("17000").compareTo(amount), amount.toPlainString());
    }
}
