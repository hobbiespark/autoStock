package com.autostock.monitor.view;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 공모주 딜 View DTO — {@code GET /api/ipo}의 목록 원소(FE-3, PLAN.md ADR-9 트랙 E2).
 * CQRS Lite 원칙(ARCHITECTURE.md 10절) — FE는 {@code ipo.IpoDealEntity}를 직접 받지 않는다.
 *
 * @param offerPriceLow  희망 공모가 하단 — [발행조건확정] 원본 문장에서 읽는다(2026-10-13). 확정 전·문장이 없으면 null
 * @param offerPriceHigh 희망 공모가 상단 — 위와 같다
 * @param offerPriceConfirmed 주요정보 모집가액(slprc) — 수요예측 전 희망가 하단이고 확정 뒤에도 그대로다(이름과 달리 확정가가 아니다)
 * @param offerPriceFinal 확정 공모가 — [발행조건확정] 원본에서 읽는다(2026-10-13, V18). 확정 전 null
 * @param stockCode      상장 뒤 종목코드 — 키움 종목정보 리스트에서 자동으로 맞춘다(2026-10-13, V18). 상장 전 null
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
        BigDecimal offerPriceFinal,
        LocalDate subscriptionStart,
        LocalDate subscriptionEnd,
        LocalDate refundDate,
        LocalDate listingDate,
        String stockCode,
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
