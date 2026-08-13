package com.autostock.execution;

import com.autostock.common.event.Side;

/**
 * 브로커에 남아있는 미체결 주문 1건 — {@link BrokerPort#outstandingOrders()}의 원소.
 * Reconciliation이 내부 DB(orders 테이블)와 이 목록을 대사(matching)하는 기준 데이터다.
 *
 * @param brokerOrderId     브로커 주문번호
 * @param symbol            종목코드
 * @param side              매매 방향
 * @param quantity          원 주문 수량
 * @param remainingQuantity 미체결 잔량(원 주문 수량 - 누적 체결 수량)
 */
public record BrokerOutstandingOrder(
        String brokerOrderId,
        String symbol,
        Side side,
        long quantity,
        long remainingQuantity
) {
}
