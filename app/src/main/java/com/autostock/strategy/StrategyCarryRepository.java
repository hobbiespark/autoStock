package com.autostock.strategy;

import org.springframework.data.jpa.repository.JpaRepository;

/** 이월 상태 저장소(V31) — {@link SwingCarryManager}만 쓴다. 키는 {@link StrategyStateEntity.Key}와 같은 (전략, 종목). */
public interface StrategyCarryRepository extends JpaRepository<StrategyCarryEntity, StrategyStateEntity.Key> {
}
