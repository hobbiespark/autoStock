/**
 * 전략 모듈: MarketTick(+MacroIndicator, NewsSentiment 필터) → Signal 발행.
 * 백테스트/모의/실전을 구분하지 않는다 (PLAN 4절 불변 원칙).
 */
@org.springframework.modulith.ApplicationModule(displayName = "strategy")
package com.autostock.strategy;
