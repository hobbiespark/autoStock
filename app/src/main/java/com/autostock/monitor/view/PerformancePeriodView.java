package com.autostock.monitor.view;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 전략 변경 구간 성과(2026-11-06) — {@code GET /api/performance/periods} 응답 원소. 비율은 퍼센트(1.23 = 1.23%)이고 값을 셀 수
 * 없으면 null이다.
 *
 * @param from                시작일(KST)
 * @param to                  끝일(KST) — 다음 구간 전날, 지금 구간이면 오늘
 * @param current             지금 구간인가
 * @param capitalKrw          그 구간 운용 자금(원), 모르면 0
 * @param tradingDays         일별 성과 기록이 있는 거래일 수
 * @param capitalReturnPct    실현손익 ÷ 운용 자금
 * @param dailyAvgReturnPct   운용 자금 대비 수익률 ÷ 거래일 수
 * @param tradeReturnPct      실현손익 ÷ 매도 원가(거래당 평균 수익률, 금액 규모와 무관)
 * @param winRatePct          이긴 청산 ÷ 청산
 * @param strategies          전략별 같은 지표(운용 자금 대비는 구간 전체 운용 자금 기준)
 */
public record PerformancePeriodView(
        String label,
        LocalDate from,
        LocalDate to,
        boolean current,
        long capitalKrw,
        int tradingDays,
        BigDecimal realizedPnl,
        BigDecimal capitalReturnPct,
        BigDecimal dailyAvgReturnPct,
        int exits,
        int wins,
        BigDecimal winRatePct,
        BigDecimal tradeReturnPct,
        List<StrategyRow> strategies
) {

    public record StrategyRow(String strategyId, int exits, int wins, BigDecimal winRatePct, BigDecimal realizedPnl,
                              BigDecimal capitalReturnPct, BigDecimal tradeReturnPct) {
    }
}
