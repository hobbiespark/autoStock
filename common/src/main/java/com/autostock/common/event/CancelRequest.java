package com.autostock.common.event;

import java.time.Instant;

/**
 * 주문 취소 요청 이벤트 (운영 1일차 ⑤, kt10003 실측 확정 2026-09-11 근거).
 *
 * <p>대시보드(monitor)가 "매매 흐름 개입은 이벤트로만" 원칙에 따라 발행하고,
 * trading.TradingService가 수신해 BrokerPort.cancelOrder를 호출한다 —
 * monitor가 BrokerPort를 직접 부르지 않는다(모듈 경계).
 *
 * @param clientOrderId 취소할 주문의 멱등키
 * @param requestedBy   요청 주체(감사용 — "dashboard" 등)
 */
public record CancelRequest(String clientOrderId, String requestedBy, Instant timestamp) {
}
