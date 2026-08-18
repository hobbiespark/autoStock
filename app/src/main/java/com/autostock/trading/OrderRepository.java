package com.autostock.trading;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 주문 Aggregate 영속화. Repository는 Aggregate 단위로만 둔다(ARCHITECTURE.md 12절 —
 * Repository 남용 금지 원칙).
 */
public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

    /** 멱등키로 조회 — 재수신 여부 판단, DB UNIQUE 위반 전 사전 체크에 사용. */
    Optional<OrderEntity> findByClientOrderId(String clientOrderId);

    /** 브로커 주문번호로 조회 — 체결통보(brokerOrderId만 옴) 처리 시 원 주문을 찾는다. */
    Optional<OrderEntity> findByBrokerOrderId(String brokerOrderId);

    /** 특정 상태 집합에 속한 주문 전체 — Reconciliation·StaleOrderCanceller가 대상 선별에 사용. */
    List<OrderEntity> findByStatusIn(Collection<OrderStatus> statuses);
}
