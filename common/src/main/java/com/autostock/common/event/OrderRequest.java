package com.autostock.common.event;

import com.autostock.common.util.StockCode;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * risk 게이트를 통과한 주문 요청. (스키마 v1 — 필드 자체는 유지, 값의 "형식"만 진화)
 * idempotencyKey: 중복 주문 방지 멱등키 — execution은 동일 키 재수신 시 무시.
 * PLAN.md ADR-6 7절부터는 UUID 대신 {@code com.autostock.common.util.ClientOrderId}
 * 포맷 문자열("20260813-BREAKOUT-005930-BUY-001")이 담긴다 — 스키마(필드 이름·타입)는
 * 바뀌지 않았으므로 이벤트 계약 버전은 그대로 v1이다.
 * symbol은 {@link StockCode}다(ARCHITECTURE.md 3절) — JSON에서는 여전히 문자열("005930")로 직렬화되어
 * 저장 형식이 같으므로 역시 v1이다(app config.JacksonConfig).
 */
public record OrderRequest(
        String idempotencyKey,
        String strategyId,
        StockCode symbol,
        Side side,
        long quantity,
        BigDecimal limitPrice,
        Instant timestamp
) {
}
