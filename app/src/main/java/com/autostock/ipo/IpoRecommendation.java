package com.autostock.ipo;

/**
 * 청약 권고 판정 (PLAN.md ADR-9 ② 필터 판단).
 *
 * <p>기관경쟁률·의무보유확약비율 지표가 둘 다 있고 두 임계치({@link IpoFilterProperties})를
 * 모두 충족하면 RECOMMEND, 충족하지 못하면 SKIP, 지표가 하나라도 없으면 PENDING이다(임의
 * 추정 금지 — recommendReason에 "어떤 지표가 없어서 PENDING인지" 명시).
 */
public enum IpoRecommendation {
    RECOMMEND,
    SKIP,
    PENDING
}
