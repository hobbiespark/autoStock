package com.autostock.monitor.view;

import java.time.Instant;
import java.util.Map;

/**
 * 종목 선정 이유 View DTO — {@code GET /api/decisions}의 목록 원소(FE-6, PLAN.md ADR-10
 * 확장표). 서버는 지평(horizon) → 종목(symbol) 오름차순 평평한 목록만 반환한다 — 지평별
 * 탭 그룹핑은 FE 책임이다(CQRS Lite View DTO 원칙, ARCHITECTURE.md 10절 — FE가 도메인
 * 엔티티를 직접 받지 않는다).
 *
 * @param decidedAt  판단 시각
 * @param horizon    보유기간 지평(ADR-11): MID/DAY/SWING/LONG (+ 아직 지평 미편입 시 TEST)
 * @param strategyId 판단을 내린 전략 ID
 * @param symbol     종목코드
 * @param conclusion BUY/SELL/HOLD/SKIP/REJECTED
 * @param reason     결론 사유 한 줄
 * @param metrics    판단 지표값(모멘텀 N일 수익률·국면 ON/OFF·볼타겟 비중 등)
 */
public record SignalDecisionView(
        Instant decidedAt,
        String horizon,
        String strategyId,
        String symbol,
        String conclusion,
        String reason,
        Map<String, String> metrics
) {
}
