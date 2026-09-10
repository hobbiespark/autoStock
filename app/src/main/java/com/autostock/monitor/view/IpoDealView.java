package com.autostock.monitor.view;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 공모주 딜 View DTO — {@code GET /api/ipo}의 목록 원소(FE-3, PLAN.md ADR-9 트랙 E2).
 * CQRS Lite 원칙(ARCHITECTURE.md 10절) — FE는 {@code ipo.IpoDealEntity}를 직접 받지 않는다.
 *
 * @param offerPriceLow  공모가 밴드 하단 — DART가 구조화 제공하지 않아 대부분 null(한계, DartClient Javadoc)
 * @param offerPriceHigh 공모가 밴드 상단 — 위와 동일 이유로 대부분 null
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
