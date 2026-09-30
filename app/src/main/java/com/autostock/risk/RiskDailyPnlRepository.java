package com.autostock.risk;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;

/** 일별 실현손익 누계(V9) — {@link RiskStateStore}만 쓴다. */
public interface RiskDailyPnlRepository extends JpaRepository<RiskDailyPnlEntity, LocalDate> {
}
