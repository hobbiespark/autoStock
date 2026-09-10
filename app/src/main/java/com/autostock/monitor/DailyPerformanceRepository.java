package com.autostock.monitor;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 일별 성과 스냅샷 영속화(FE-2). Repository는 Aggregate 단위로만 둔다
 * (ARCHITECTURE.md 12절 — Repository 남용 금지 원칙).
 */
public interface DailyPerformanceRepository extends JpaRepository<DailyPerformanceEntity, Long> {

    /** upsert 판단(있으면 갱신, 없으면 생성)에 사용 — trade_date UNIQUE 제약과 짝을 이룬다. */
    Optional<DailyPerformanceEntity> findByTradeDate(LocalDate tradeDate);

    /** 기간 필터 조회 — GET /api/performance/daily?days=N이 날짜 오름차순으로 반환한다. */
    List<DailyPerformanceEntity> findByTradeDateGreaterThanEqualOrderByTradeDateAsc(LocalDate since);
}
