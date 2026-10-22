package com.autostock.portfolio;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/** 전략별 체결 기록(V20). */
public interface StrategyFillRepository extends JpaRepository<StrategyFillEntity, Long> {

    /** [from, to) 구간의 체결 — 일일 리포트의 전략별 줄. */
    List<StrategyFillEntity> findByFilledAtGreaterThanEqualAndFilledAtLessThan(Instant from, Instant to);
}
