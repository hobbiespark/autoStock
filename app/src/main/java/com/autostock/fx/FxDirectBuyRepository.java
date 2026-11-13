package com.autostock.fx;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

/** 외화 직접매수 기록(V34). */
public interface FxDirectBuyRepository extends JpaRepository<FxDirectBuyEntity, Long> {

    /** 그 기간의 직접매수(취소 포함 — 상태로 거른다). */
    List<FxDirectBuyEntity> findByBoughtOnBetweenOrderByBoughtOnAscIdAsc(LocalDate from, LocalDate to);

    /** 이번 달 그 통화의 직접매수가 이미 있는가(설계 6장 C3). */
    boolean existsByCurrencyAndStatusAndBoughtOnBetween(FxCurrency currency, FxDirectBuyEntity.Status status,
                                                        LocalDate from, LocalDate to);
}
