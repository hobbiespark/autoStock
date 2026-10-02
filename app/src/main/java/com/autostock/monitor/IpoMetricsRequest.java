package com.autostock.monitor;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code POST /api/ipo/{id}/metrics} 요청 본문 — 기관경쟁률·의무보유확약비율 수동 입력(FE-3).
 * 두 지표는 2026-10-02부터 [발행조건확정] 수요예측 결과에서 자동으로 채워지고(aiDoc/ipo-demand-forecast.md),
 * 이 경로로 넣은 값은 자동 입력이 덮지 않는다(출처 MANUAL). 입력 즉시 필터·상태가 재평가된다.
 * 세 필드 모두 선택값 — null이면 기존 값을 유지한다(부분 갱신, {@code /record}와 동일 규약).
 *
 * @param institutionalCompetitionRate 기관경쟁률(단위: "N:1"의 N)
 * @param lockupCommitRate             의무보유확약비율(0~1, 수량 기준 — 확약 신청 수량 ÷ 전체 신청 수량)
 * @param listingDate                  상장(예정)일 — DART estkRs.json이 제공하지 않아(DartClient Javadoc)
 *                                     KIND/주관사 공지에서 보고 수동 입력(2026-09-23 추가). 입력하면
 *                                     상장일 도래 시 상태가 LISTED로 바뀐다.
 */
public record IpoMetricsRequest(
        @PositiveOrZero @Digits(integer = 8, fraction = 2) BigDecimal institutionalCompetitionRate, // NUMERIC(10,2)
        @DecimalMin("0") @DecimalMax("1") @Digits(integer = 2, fraction = 4) BigDecimal lockupCommitRate, // NUMERIC(6,4)
        LocalDate listingDate
) {
}
