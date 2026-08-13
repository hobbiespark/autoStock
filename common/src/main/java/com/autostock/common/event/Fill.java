package com.autostock.common.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 체결 이벤트. (스키마 v1)
 */
public record Fill(
        String orderIdempotencyKey,
        String brokerOrderId,
        String symbol,
        Side side,
        long filledQuantity,
        BigDecimal fillPrice,
        Instant timestamp
) {
}
