package com.autostock.portfolio;

import org.springframework.data.jpa.repository.JpaRepository;

/** 전략별 현재 보유(V20) — 작은 표라 기동 때 전부 읽는다. */
public interface StrategyPositionRepository
        extends JpaRepository<StrategyPositionEntity, StrategyPositionEntity.Key> {
}
