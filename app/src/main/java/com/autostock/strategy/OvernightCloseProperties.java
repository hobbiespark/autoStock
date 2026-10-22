package com.autostock.strategy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * 종가 → 다음 날 시가(ON-CLOSE) 설정 — 값은 사전 선언(aiDoc/multi-strategy-paper-2026-10-16.md 3-2)이다.
 *
 * @param enabled         기본 꺼짐 — 시작 스크립트가 켠다
 * @param maxPicks        하루 매수 종목 수
 * @param minChangePct    등락률 하한(%, 이상)
 * @param maxChangePct    등락률 상한(%, 이하)
 * @param nearHighRatio   현재가 ≥ 당일 고가 × 이 값(윗꼬리 제한)
 * @param buyPremium      종가 단일가 매수 지정가 = 15:15 현재가 × (1 + buyPremium)
 * @param sellDiscount    다음 날 매도 지정가 = 최우선 매수호가 × (1 − sellDiscount)
 * @param maxQuoteChecks  거래대금 큰 순서로 현재가를 확인할 최대 종목 수(TR 초당 1건 — 15:15~15:16 안에 끝내려고)
 * @param stockConditions 등락률 상위를 받을 종목조건(키움 코드) — 결과의 교집합을 쓴다
 */
@ConfigurationProperties(prefix = "strategy.on")
public record OvernightCloseProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("3") int maxPicks,
        @DefaultValue("5") double minChangePct,
        @DefaultValue("20") double maxChangePct,
        @DefaultValue("0.97") double nearHighRatio,
        @DefaultValue("0.05") double buyPremium,
        @DefaultValue("0.02") double sellDiscount,
        @DefaultValue("20") int maxQuoteChecks,
        @DefaultValue({"16", "4", "5", "11"}) List<String> stockConditions
) {
}
