package com.autostock.monitor.view;

import com.autostock.monitor.TradingSystemStatus;

import java.math.BigDecimal;

/**
 * 매매 상태 View DTO — "지금 매매를 해도 되는 상태인가"를 한 번에 보여준다.
 *
 * @param status            운영 상태기계 현재 값(STOPPED/STARTING/RUNNING/STOPPING/DEGRADED/ERROR)
 * @param killSwitchEngaged 킬스위치 작동 여부(status가 DEGRADED인 이유를 바로 알 수 있게 별도로도 노출)
 * @param todayOrderCount   오늘 낸 주문 수(risk.DailyLimitTracker)
 * @param todayRealizedPnl  오늘 누적 실현손익(risk.DailyPnlTracker) — 미실현 손익은 미포함(TODO, 원본 Javadoc 참고)
 * @param conservativeMode  거시 국면 보수 모드 여부(risk.MacroGuard, PLAN 5절) — 킬스위치와 달리
 *                          신규 매수만 막고 청산은 허용하는 완화 단계다(킬스위치보다 약한 경계 신호).
 * @param disclosureBlacklistCount 지금 매수 배제 중인 종목 수(risk.DisclosureBlacklist, PLAN.md
 *                          ADR-14 트랙 G1 — 공시 자동 등록 + 운영자 수동 등록 합산). 과확장 금지
 *                          원칙(작업 지시)에 따라 카운트 한 줄만 노출하고 목록 화면은 두지 않는다.
 */
public record TradingStatusView(
        TradingSystemStatus status,
        boolean killSwitchEngaged,
        int todayOrderCount,
        BigDecimal todayRealizedPnl,
        boolean conservativeMode,
        int disclosureBlacklistCount
) {
}
