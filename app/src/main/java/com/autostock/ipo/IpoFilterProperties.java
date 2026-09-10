package com.autostock.ipo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

/**
 * 공모주 청약 권고 필터 임계치 (PLAN.md ADR-9 ② 필터 판단).
 *
 * <p>청약경쟁률(및 상관관계가 높은 기관경쟁률)이 상장일 수익률과 정(+)의 관계를 갖는다는
 * 실증(ADR-9 인용 KCI 논문 2건)에 근거해, 기관경쟁률과 의무보유확약비율 두 지표로 단순
 * AND 필터를 둔다. 두 지표 모두 DART가 구조화 제공하지 않아(estkRs.json 실측 결과) 수동
 * 입력({@code POST /api/ipo/{id}/metrics})으로만 채워진다 — 값이 없으면 판정은 항상 PENDING이다
 * (임의로 RECOMMEND/SKIP을 추정하지 않는다, 과대약속 금지).
 *
 * @param minInstitutionalCompetitionRate 기관경쟁률 최소 임계치(단위: "N:1"의 N, 기본 500)
 * @param minLockupCommitRate             의무보유확약비율 최소 임계치(비율, 기본 0.20 = 20%)
 */
@ConfigurationProperties(prefix = "ipo.filter")
public record IpoFilterProperties(
        @DefaultValue("500") BigDecimal minInstitutionalCompetitionRate,
        @DefaultValue("0.20") BigDecimal minLockupCommitRate
) {
}
