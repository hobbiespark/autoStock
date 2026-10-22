package com.autostock.common.event;

import java.time.Instant;

/**
 * 주문 경로 실측(TrProbeJob, 실행 계획 1.6)이 낸 주문 번호. (스키마 v1 — 2026-10-15 신규)
 *
 * <p>실측 주문은 앱의 주문 장부(trading)를 거치지 않는다. 그래서 그 체결 통보가 오면 trading의 OrderNoticeHandler는 매핑을
 * 못 찾고 30초 보류한 뒤 "수동 주문 등으로 추정" WARN을 남겼다(10/15 09:30 실측 — 체결 왕복 매수·매도 2건). execution 모듈이
 * 주문을 낼 때마다 이 이벤트를 발행하고, trading이 받아 그 번호의 통보는 실측 주문으로 알아보고 조용히 넘긴다
 * (execution → trading 직접 의존은 금지 — ArchitectureRulesTest 규칙 5).
 *
 * @param brokerOrderId 키움 주문번호 원문(kt10000·kt10001·kt10003 응답 {@code ord_no}, 예: "0042135")
 * @param timestamp     주문 응답을 받은 시각
 */
public record ProbeOrderPlaced(
        String brokerOrderId,
        Instant timestamp
) {
}
