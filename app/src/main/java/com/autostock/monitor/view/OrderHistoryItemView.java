package com.autostock.monitor.view;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 주문 1건의 View DTO — {@code trading.OrderEntity}(Domain Entity)를 화면용으로 옮겨 담은 것.
 * FE는 Domain Entity를 직접 보지 않는다(ARCHITECTURE.md 13절 설계 규칙 13·14, CQRS Lite).
 *
 * @param clientOrderId  논리 주문 멱등키(모노스페이스로 표시)
 * @param symbol         종목코드
 * @param side           BUY | SELL
 * @param quantity       주문 수량
 * @param filledQuantity 누적 체결 수량
 * @param limitPrice     지정가(없을 수 있음 — 시장가 등)
 * @param status         주문 상태(11상태, OrderStatus.name())
 * @param strategyId     주문을 낸 전략 식별자
 * @param submittedAt    주문 접수 시각
 */
public record OrderHistoryItemView(
        String clientOrderId,
        String symbol,
        String side,
        long quantity,
        long filledQuantity,
        BigDecimal limitPrice,
        String status,
        String strategyId,
        Instant submittedAt
) {
}
