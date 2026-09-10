package com.autostock.monitor.view;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 일별 성과 스냅샷 View DTO — {@code GET /api/performance/daily}의 목록 원소
 * (FE-2, PLAN.md ADR-10 확장표). 날짜 오름차순으로 반환되어 FE가 그대로 차트에 꽂을 수 있다.
 *
 * @param tradeDate         거래일(KST)
 * @param realizedPnl       당일 실현손익
 * @param orderCount        당일 주문 수
 * @param fillCount         당일 체결 건수
 * @param avgSlippageBps    당일 평균 슬리피지(bps, 양수=불리)
 * @param maxSlippageBps    당일 최대 슬리피지(bps)
 * @param conservativeMode  보수 모드(거시 국면) 여부
 * @param killSwitchEngaged 킬스위치 작동 여부
 */
public record DailyPerformanceView(
        LocalDate tradeDate,
        BigDecimal realizedPnl,
        int orderCount,
        int fillCount,
        double avgSlippageBps,
        double maxSlippageBps,
        boolean conservativeMode,
        boolean killSwitchEngaged
) {
}
