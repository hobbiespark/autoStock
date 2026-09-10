package com.autostock.monitor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code POST /api/ipo/{id}/record} 요청 본문 — 내 청약/배정/매도 기록(FE-3). 필드는 전부
 * 선택값이다 — null이면 기존 값을 유지한다(부분 갱신, {@code ipo.IpoDealEntity.applyRecord}).
 */
public record IpoRecordRequest(
        Integer appliedQty,
        BigDecimal deposit,
        Integer allocatedQty,
        BigDecimal sellPrice,
        LocalDate sellDate,
        String memo
) {
}
