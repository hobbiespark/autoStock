/**
 * 전략 모듈: MarketTick(+MacroIndicator, NewsSentiment 필터) → Signal 발행.
 * 백테스트/모의/실전을 구분하지 않는다 (PLAN 4절 불변 원칙).
 *
 * <p>모듈 간 의존: {@link com.autostock.strategy.C3LiveStrategy}가 monitor 모듈의
 * {@code TradingSystemManager.status()}를 직접 호출해 운영 상태기계가 RUNNING인지
 * 확인한다(이중 가드, ARCHITECTURE.md 10절) — 단순 조회라 ARCHITECTURE.md 9절에 따라
 * 인터페이스 직접 호출을 허용했다. strategy → monitor 단방향 의존이며, monitor는
 * strategy를 참조하지 않으므로 순환은 없다(ModularityTests로 확인).
 */
@org.springframework.modulith.ApplicationModule(displayName = "strategy")
package com.autostock.strategy;
