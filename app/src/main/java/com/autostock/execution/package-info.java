/**
 * 주문 실행 모듈: OrderRequest → 주문 상태기계(OrderStatus, 11상태) → BrokerPort(키움 어댑터)
 * → Fill 발행. 멱등성(client_order_id 중복 거부), Reconciliation(브로커 대사), 미체결
 * 타임아웃 취소가 핵심 책임 (PLAN.md ADR-6).
 *
 * <p>Hexagonal 경계: 이 모듈은 {@link com.autostock.execution.BrokerPort}만 의존한다.
 * 키움 REST 구체 타입(KiwoomRestClient, TrId 등)은 {@link com.autostock.execution.KiwoomBrokerAdapter}
 * 안에서만 등장한다.
 */
@org.springframework.modulith.ApplicationModule(displayName = "execution")
package com.autostock.execution;
