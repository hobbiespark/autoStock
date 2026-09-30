package com.autostock.execution;

import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.Side;
import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.StockCode;
import com.autostock.kiwoom.KiwoomApiException;
import com.autostock.kiwoom.KiwoomRestClient;
import com.autostock.kiwoom.KiwoomTimeoutException;
import com.autostock.kiwoom.TrId;
import com.autostock.common.util.Price;
import com.autostock.common.util.Quantity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * KiwoomBrokerAdapter — 키움 주문번호 문자열을 BrokerOrderId로 번역하는 경계(ACL). 주문번호가 없는 응답을
 * 어떻게 다루는지 고정한다.
 */
class KiwoomBrokerAdapterTest {

    private final KiwoomRestClient client = mock(KiwoomRestClient.class);
    private final KiwoomBrokerAdapter adapter = new KiwoomBrokerAdapter(client);

    private static OrderRequest buy() {
        return new OrderRequest("20260930-C3-005930-BUY-001", "C3", new StockCode("005930"), Side.BUY,
                new Quantity(1), new Price(new BigDecimal("258000")), Instant.parse("2026-09-30T01:00:00Z"));
    }

    @Test
    void 주문_응답의_주문번호를_BrokerOrderId로_돌려준다() {
        when(client.call(eq(TrId.ORDER_BUY), anyString(), anyMap()))
                .thenReturn(Map.of("return_code", 0, "ord_no", "0119433"));

        assertEquals(new BrokerOrderId("0119433"), adapter.placeOrder(buy()).brokerOrderId());
    }

    @Test
    void 주문_응답_시간_초과는_거부가_아니라_결과_불명으로_던진다() {
        // Phase 0.6: 타임아웃을 BrokerRejectedException으로 번역하면 TradingService가 REJECTED로 종결하고 대사하지 않는다
        // — 실제로 접수된 주문이 추적에서 빠진다. 그대로 던져 UNKNOWN + 대사로 확정하게 한다.
        when(client.call(eq(TrId.ORDER_BUY), anyString(), anyMap()))
                .thenThrow(new KiwoomTimeoutException("키움 API 응답 없음 [kt10000] 15초 내 응답 없음"));

        assertThrows(KiwoomTimeoutException.class, () -> adapter.placeOrder(buy()));
    }

    @Test
    void 응답을_받은_명시_오류는_브로커_거부로_번역한다() {
        when(client.call(eq(TrId.ORDER_BUY), anyString(), anyMap()))
                .thenThrow(new KiwoomApiException("키움 API 논리 오류 [kt10000] [RC4027:가격제한폭 초과]"));

        assertThrows(BrokerRejectedException.class, () -> adapter.placeOrder(buy()));
    }

    @Test
    void 주문번호가_빈_주문_응답은_결과_불명으로_던진다() {
        // 접수 여부를 단정할 수 없다 — TradingService가 UNKNOWN + 대사로 처리한다
        when(client.call(eq(TrId.ORDER_BUY), anyString(), anyMap()))
                .thenReturn(Map.of("return_code", 0, "ord_no", " "));

        assertThrows(KiwoomApiException.class, () -> adapter.placeOrder(buy()));
    }

    @Test
    void 미체결_목록에서_주문번호_없는_행은_건너뛰고_나머지는_돌려준다() {
        // 필드명이 실측 미확정(ka10075)이라 기형 행이 섞여도 목록 전체를 실패시키지 않는다 — 대사가 매 주기 실패하지 않게
        Map<String, Object> noOrderNo = new HashMap<>(Map.of("stk_cd", "A000660", "ord_qty", "3"));
        when(client.call(eq(TrId.OUTSTANDING_ORDERS), anyString(), any())).thenReturn(Map.of("oso", List.of(
                Map.of("ord_no", "0119433", "stk_cd", "A005930", "ord_qty", "10", "un_qty", "4"),
                noOrderNo)));

        List<BrokerOutstandingOrder> orders = adapter.outstandingOrders();

        assertEquals(1, orders.size());
        assertEquals(new BrokerOrderId("0119433"), orders.get(0).brokerOrderId());
        assertEquals(new StockCode("005930"), orders.get(0).symbol()); // 키움 "A" 접두사를 벗긴다
        assertEquals(4, orders.get(0).remainingQuantity());
    }

    @Test
    void 미체결_목록에서_종목코드_형식이_틀린_행은_건너뛴다() {
        when(client.call(eq(TrId.OUTSTANDING_ORDERS), anyString(), any())).thenReturn(Map.of("oso", List.of(
                Map.of("ord_no", "0119433", "stk_cd", "A005930", "ord_qty", "10"),
                Map.of("ord_no", "0119434", "stk_cd", "", "ord_qty", "1"))));

        List<BrokerOutstandingOrder> orders = adapter.outstandingOrders();

        assertEquals(1, orders.size());
        assertEquals(new BrokerOrderId("0119433"), orders.get(0).brokerOrderId());
    }
}
