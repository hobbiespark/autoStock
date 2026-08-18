/**
 * 브로커 전달 모듈 — 주문/조회를 실제 브로커(키움)로 전달하는 어댑터 경계만 책임진다
 * (ADR-6 재편으로 execution/trading 분리, ARCHITECTURE.md 2절). 이 모듈은 <b>주문 생성·상태
 * 관리·대사(Reconciliation)를 전혀 모른다</b> — 그건 trading 모듈 책임이다.
 *
 * <p>Hexagonal 경계: 외부(trading 등)가 아는 것은 {@link com.autostock.execution.BrokerPort}
 * 인터페이스뿐이다. 키움 REST 구체 타입(KiwoomRestClient, TrId 등)은
 * {@link com.autostock.execution.KiwoomBrokerAdapter} 안에서만 등장하고, 이 경계 밖으로
 * Map/DTO를 노출하지 않는다({@link com.autostock.execution.BrokerOrderResult}/
 * {@link com.autostock.execution.BrokerOutstandingOrder}/{@link com.autostock.execution.BrokerBalance}
 * 같은 도메인 record만 돌려준다).
 *
 * <p><b>의존 방향(단방향)</b>: trading → execution({@link com.autostock.execution.BrokerPort}만
 * 참조) — execution은 trading의 존재를 전혀 모른다(반대 방향 의존 없음, 순환 없음).
 * 별도로 {@link com.autostock.execution.BrokerEquitySource}가 risk 모듈의 {@code EquitySource}
 * 계약을 구현한다(LIVE 잔고 연동, PLAN 8절) — execution → risk 단방향 의존이며 risk는
 * execution을 참조하지 않으므로 여기서도 순환은 없다.
 */
@org.springframework.modulith.ApplicationModule(displayName = "execution")
package com.autostock.execution;
