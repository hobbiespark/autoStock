package com.autostock.monitor.view;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 공모주 딜 View DTO — {@code GET /api/ipo}의 목록 원소(FE-3, PLAN.md ADR-9 트랙 E2).
 * CQRS Lite 원칙(ARCHITECTURE.md 10절) — FE는 {@code ipo.IpoDealEntity}를 직접 받지 않는다.
 *
 * @param offerPriceLow  공모가 밴드 하단 — DART가 구조화 제공하지 않아 대부분 null(한계, DartClient Javadoc)
 * @param offerPriceHigh 공모가 밴드 상단 — 위와 동일 이유로 대부분 null
 * @param lockupCommitRate 의무보유확약비율(0~1, 수량 기준)
 * @param metricsSource  지표 출처 — "DART"(수요예측 결과 자동 입력) | "MANUAL"(수동 입력) | null(아직 없음), 2026-10-02
 * @param metricsRceptNo 자동 입력에 쓴 [발행조건확정] 신고서 접수번호(DART일 때만)
 */
public record IpoDealView(
        Long id,
        String corpCode,
        String corpName,
        String rceptNo,
        BigDecimal offerPriceLow,
        BigDecimal offerPriceHigh,
        BigDecimal offerPriceConfirmed,
        LocalDate subscriptionStart,
        LocalDate subscriptionEnd,
        LocalDate refundDate,
        LocalDate listingDate,
        String leadManager,
        BigDecimal institutionalCompetitionRate,
        BigDecimal lockupCommitRate,
        String metricsSource,
        String metricsRceptNo,
        String status,
        String recommendation,
        String recommendReason,
        Integer appliedQty,
        BigDecimal deposit,
        Integer allocatedQty,
        BigDecimal sellPrice,
        LocalDate sellDate,
        String memo
) {
}
