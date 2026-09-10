package com.autostock.monitor;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

/**
 * 종목 선정 이유 판단 스냅샷 영속화(FE-6). Repository는 Aggregate 단위로만 둔다
 * (ARCHITECTURE.md 12절 — Repository 남용 금지 원칙, DailyPerformanceRepository와 동일 패턴).
 */
public interface SignalDecisionRepository extends JpaRepository<SignalDecisionEntity, Long> {

    /**
     * GET /api/decisions?date= 조회에 사용 — 서버는 그룹핑하지 않고 지평(horizon) →
     * 종목(symbol) 오름차순 평평한 목록만 반환한다(그룹핑은 FE 책임, PLAN.md ADR-10 확장표).
     */
    List<SignalDecisionEntity> findByTradeDateOrderByHorizonAscSymbolAsc(LocalDate tradeDate);
}
