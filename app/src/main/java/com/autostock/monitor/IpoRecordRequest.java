package com.autostock.monitor;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code POST /api/ipo/{id}/record} 요청 본문 — 내 청약/배정/매도 기록(FE-3). 필드는 전부
 * 선택값이다 — null이면 기존 값을 유지한다(부분 갱신, {@code ipo.IpoDealEntity.applyRecord}).
 */
public record IpoRecordRequest(
        @PositiveOrZero Integer appliedQty,
        @PositiveOrZero @Digits(integer = 14, fraction = 2) BigDecimal deposit,       // NUMERIC(16,2)
        @PositiveOrZero Integer allocatedQty,
        @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal sellPrice,     // NUMERIC(14,2)
        LocalDate sellDate,
        @Size(max = 2000) String memo
) {
}
