package com.autostock.ipo;

/**
 * 청약 권고 판정 (PLAN.md ADR-9 ② 필터 판단).
 *
 * <p>2026-10-15부터 청약 추천({@link IpoSubscriptionAdvisor}, aiDoc/ipo-subscription-strategy.md)의 등급을 옮긴 값이다 —
 * 비례까지·균등만 → RECOMMEND, 패스 → SKIP, 기관경쟁률을 모름 → PENDING. 등급·점수는 딜의 advice_grade·advice_score(V19)에,
 * 근거·청약 방법은 recommendReason에 글로 남는다. 그 전(2026-09~10-14)에는 기관경쟁률 ≥500:1 그리고 확약 ≥20%면
 * RECOMMEND였다({@link IpoFilterProperties} — 지금은 쓰지 않음).
 */
public enum IpoRecommendation {
    RECOMMEND,
    SKIP,
    PENDING
}
