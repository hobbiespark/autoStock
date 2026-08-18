/**
 * 주문 오케스트레이션 모듈 — 주문 생성·상태 관리·대사(Reconciliation)를 책임진다
 * (ADR-6 재편으로 execution에서 분리, ARCHITECTURE.md 2절). OrderRequest → 주문 상태기계
 * ({@link com.autostock.trading.OrderStatus}, 11상태) → {@link com.autostock.trading.TradingService}
 * (구 ExecutionService) → Fill 발행까지가 이 모듈의 범위다. 멱등성(client_order_id 중복 거부),
 * Reconciliation(브로커 대사), 미체결 타임아웃 취소가 핵심 책임 (PLAN.md ADR-6).
 *
 * <p><b>의존 방향(단방향)</b>: trading → execution({@link com.autostock.execution.BrokerPort}만
 * 참조) — 브로커 REST/WS 구체 타입은 전혀 모르고, {@code BrokerPort} 인터페이스와
 * {@code BrokerOrderResult}/{@code BrokerOutstandingOrder} 같은 도메인 record만 사용한다.
 * execution은 trading을 참조하지 않으므로(반대 방향 의존 없음) 순환은 없다
 * (ModularityTests로 검증).
 *
 * <p>{@code TradingProperties}(구 ExecutionProperties)의 {@code @ConfigurationProperties}
 * prefix는 yml 호환성을 위해 {@code execution}을 그대로 유지한다 — 클래스는 이동했지만
 * application.yml의 {@code execution.mode}/{@code execution.stale-order-timeout} 키는
 * 바꾸지 않았다.
 */
@org.springframework.modulith.ApplicationModule(displayName = "trading")
package com.autostock.trading;
