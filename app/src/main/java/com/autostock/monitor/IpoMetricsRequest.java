package com.autostock.monitor;

import java.math.BigDecimal;

/**
 * {@code POST /api/ipo/{id}/metrics} 요청 본문 — 기관경쟁률·의무보유확약비율 수동 입력(FE-3).
 * DART가 두 지표를 구조화 제공하지 않아(실측 확인, {@code ipo.DartClient} Javadoc) 열어 둔
 * 경로다. 값을 입력하면 다음 배치({@code IpoSyncScheduler})에서 필터가 재평가된다.
 *
 * @param institutionalCompetitionRate 기관경쟁률(단위: "N:1"의 N)
 * @param lockupCommitRate             의무보유확약비율(0~1)
 */
public record IpoMetricsRequest(
        BigDecimal institutionalCompetitionRate,
        BigDecimal lockupCommitRate
) {
}
