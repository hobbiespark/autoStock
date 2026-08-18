/**
 * 포트폴리오 모듈 — 보유 포지션·손익 (ARCHITECTURE.md 2절, ADR-6 재편으로 risk → portfolio 신설).
 *
 * <p>{@link com.autostock.portfolio.PositionBook}이 체결(Fill) 이벤트를 구독해 "지금 무엇을
 * 몇 주, 평균 얼마에 들고 있나"를 답하는 장부를 유지한다. 리스크 한도 판단 장치(일 손실
 * 한도 킬스위치 등)는 여전히 risk 모듈 책임이므로 이 모듈로 옮기지 않았다 — portfolio는
 * "무엇을 들고 있나"를, risk는 "그래서 주문을 허용할지"를 답한다(책임 분리).
 *
 * <p>모듈 간 의존: risk(사이징·매도 수량 판단), monitor(대시보드 조합), strategy(C3LiveStrategy
 * 보유 여부 조회)가 이 모듈의 {@link com.autostock.portfolio.PositionBook}을 조회한다 —
 * portfolio는 어느 모듈도 참조하지 않는 리프(leaf) 모듈이라 순환이 생기지 않는다
 * (ModularityTests로 검증).
 */
@org.springframework.modulith.ApplicationModule(displayName = "portfolio")
package com.autostock.portfolio;
