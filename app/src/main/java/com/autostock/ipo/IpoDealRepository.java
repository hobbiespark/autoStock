package com.autostock.ipo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 공모주 딜 영속화(PLAN.md ADR-9). Repository는 Aggregate 단위로만 둔다
 * (ARCHITECTURE.md 12절, {@code monitor.SignalDecisionRepository}와 동일 원칙).
 *
 * <p>monitor 모듈이 이 리포지토리를 직접 참조해 {@code GET /api/ipo}를 제공한다
 * ({@code monitor.OrderHistoryController}가 {@code trading.OrderRepository}를 직접 쓰는
 * 기존 패턴, package-info.java 참고).
 */
public interface IpoDealRepository extends JpaRepository<IpoDealEntity, Long> {

    Optional<IpoDealEntity> findByRceptNo(String rceptNo);

    List<IpoDealEntity> findByStatusOrderBySubscriptionStartAsc(IpoStatus status);

    List<IpoDealEntity> findAllByOrderBySubscriptionStartDesc();

    /** IpoSyncScheduler가 매 배치 상태·D-day 알림 대상을 다시 계산하기 위해 전체를 훑는다. */
    List<IpoDealEntity> findBySubscriptionStartGreaterThanEqualOrSubscriptionStartIsNull(LocalDate since);
}
