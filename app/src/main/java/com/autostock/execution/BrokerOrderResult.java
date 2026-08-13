package com.autostock.execution;

/**
 * 브로커 주문 접수 결과 — {@link BrokerPort#placeOrder(com.autostock.common.event.OrderRequest)}의 반환값.
 *
 * @param brokerOrderId 브로커가 발급한 주문번호
 */
public record BrokerOrderResult(String brokerOrderId) {
}
