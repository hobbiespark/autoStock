package com.autostock.strategy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 전략 판단 상태 저장소(V13) — {@link StrategyStateStore}만 쓴다. */
public interface StrategyStateRepository extends JpaRepository<StrategyStateEntity, StrategyStateEntity.Key> {

    List<StrategyStateEntity> findByStrategyId(String strategyId);
}
