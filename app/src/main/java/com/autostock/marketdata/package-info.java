/**
 * 시세 수집 모듈: 키움 WS/REST → 정규화 MarketTick 발행.
 * WS 재연결·재구독은 이 모듈 책임 (PLAN 3절 사고 사례 대응).
 */
@org.springframework.modulith.ApplicationModule(displayName = "market-data")
package com.autostock.marketdata;
