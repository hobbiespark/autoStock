package com.autostock.risk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 공시 블랙리스트 영속화(PLAN.md ADR-14, 트랙 G1). Repository는 Aggregate 단위로만 둔다
 * ({@code ipo.IpoDealRepository}와 동일 원칙).
 */
public interface DisclosureBlacklistRepository extends JpaRepository<DisclosureBlacklistEntity, Long> {

    /** (symbol, rceptNo) 멱등 체크 — DisclosureBlacklistSyncScheduler 재처리 시 중복 등록·중복 알림 방지. */
    boolean existsBySymbolAndRceptNo(String symbol, String rceptNo);

    /** 기동 시 메모리 캐시 재구성, 그리고 대시보드 활성 건수 조회에 쓰인다. */
    List<DisclosureBlacklistEntity> findByExpiresOnGreaterThanEqual(LocalDate date);

    /**
     * 만료 자동 해제(ADR-14) — DisclosureBlacklistExpiryScheduler가 매일 호출.
     * <p>{@code @Transactional} 필수(Phase 0.3, aiDoc/disclosure-blacklist-tx.md): 파생 삭제는 대상 엔티티를 읽어
     * {@code remove}하는데, 호출 경로(배치·수동 해제)에 트랜잭션이 없어 "No EntityManager with actual transaction"
     * 예외로 한 건도 지워지지 않았다(DisclosureBlacklistRepositoryDbTest로 확인). 이 메서드가 자기 트랜잭션에서 커밋한다.
     */
    @Transactional
    long deleteByExpiresOnBefore(LocalDate date);

    /** 운영자 수동 remove() 시 이 종목의 공시 이력 전체를 지운다(재시작해도 되살아나지 않도록). 트랜잭션은 위와 같은 이유. */
    @Transactional
    long deleteBySymbol(String symbol);
}
