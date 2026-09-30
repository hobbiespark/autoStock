package com.autostock.common.event;

import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.Quantity;
import com.autostock.common.util.StockCode;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 체결 이벤트. (스키마 v1 — brokerOrderId·symbol·filledQuantity는 값 객체지만 JSON에서는 문자열로 직렬화되어 저장 형식이 같다)
 */
public record Fill(
        String orderIdempotencyKey,
        BrokerOrderId brokerOrderId,
        StockCode symbol,
        Side side,
        Quantity filledQuantity,
        BigDecimal fillPrice,
        Instant timestamp
) {
}
