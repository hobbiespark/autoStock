package com.autostock.risk;

import org.springframework.data.jpa.repository.JpaRepository;

/** 킬스위치 상태 단일 행(V9) — {@link RiskStateStore}만 쓴다. */
public interface RiskStateRepository extends JpaRepository<RiskStateEntity, Short> {
}
