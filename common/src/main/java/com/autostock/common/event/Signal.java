package com.autostock.common.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 전략이 발행하는 매매 시그널. (스키마 v1)
 * 시그널은 주문이 아니다 — risk 모듈 게이트 통과 후에만 OrderRequest가 된다.
 */
public record Signal(
        String strategyId,
        String symbol,
        Side side,
        BigDecimal refPrice,
        double confidence,
        Instant timestamp
) {
}
