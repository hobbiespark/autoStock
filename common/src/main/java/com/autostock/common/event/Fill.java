package com.autostock.common.event;

import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.Price;
import com.autostock.common.util.Quantity;
import com.autostock.common.util.StockCode;

import java.time.Instant;

/**
 * 체결 이벤트. (스키마 v1 — brokerOrderId·symbol·filledQuantity·fillPrice는 값 객체지만 JSON에서는 예전과 같은 문자열·숫자로
 * 직렬화되어 저장 형식이 같다)
 * fillPrice는 항상 있다 — 체결가를 모르는 LIVE 통보는 trading이 지정가로 근사하거나 Fill을 만들지 않는다.
 */
public record Fill(
        String orderIdempotencyKey,
        BrokerOrderId brokerOrderId,
        StockCode symbol,
        Side side,
        Quantity filledQuantity,
        Price fillPrice,
        Instant timestamp
) {
}
