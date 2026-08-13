package com.autostock.common.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * risk 게이트를 통과한 주문 요청. (스키마 v1)
 * idempotencyKey: 중복 주문 방지 멱등키 — execution은 동일 키 재수신 시 무시.
 */
public record OrderRequest(
        String idempotencyKey,
        String strategyId,
        String symbol,
        Side side,
        long quantity,
        BigDecimal limitPrice,
        Instant timestamp
) {
}
