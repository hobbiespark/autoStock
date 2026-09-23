package com.autostock.monitor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code POST /api/ipo/{id}/metrics} 요청 본문 — 기관경쟁률·의무보유확약비율 수동 입력(FE-3).
 * DART가 두 지표를 구조화 제공하지 않아(실측 확인, {@code ipo.DartClient} Javadoc) 열어 둔
 * 경로다. 입력 즉시 필터·상태가 재평가된다. 세 필드 모두 선택값 — null이면 기존 값을 유지한다
 * (부분 갱신, {@code /record}와 동일 규약).
 *
 * @param institutionalCompetitionRate 기관경쟁률(단위: "N:1"의 N)
 * @param lockupCommitRate             의무보유확약비율(0~1)
 * @param listingDate                  상장(예정)일 — DART estkRs.json이 제공하지 않아(DartClient Javadoc)
 *                                     KIND/주관사 공지에서 보고 수동 입력(2026-09-23 추가). 입력하면
 *                                     상장일 도래 시 상태가 LISTED로 바뀐다.
 */
public record IpoMetricsRequest(
        BigDecimal institutionalCompetitionRate,
        BigDecimal lockupCommitRate,
        LocalDate listingDate
) {
}
