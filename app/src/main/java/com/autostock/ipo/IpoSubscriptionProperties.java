package com.autostock.ipo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

/**
 * 공모주 청약 금액 추천 설정(2026-10-30 사용자 "청약추천이라면 200만원 예산 내에서 얼마나 청약할지도 추천",
 * aiDoc/ipo-automation.md "2026-10-30 바뀜"). 계산은 {@link IpoSubscriptionSizer}가 한다.
 *
 * <p>증거금률·최소 청약 수량·청약 단위는 주관사마다 다르다 — 여기 값은 기본값이고, 알림 문구에 "주관사 공고 확인"을 붙인다.
 *
 * @param budgetKrw  공모주 청약 예산(원, 기본 2,000,000)
 * @param marginRate 청약 증거금률(기본 0.5 = 50%)
 * @param minQty     최소 청약 수량(주, 기본 10 — 균등 배정 최소 단위)
 * @param unitQty    청약 단위(주, 기본 10 — 비례 수량은 이 단위로 내림)
 */
@ConfigurationProperties(prefix = "ipo.subscription")
public record IpoSubscriptionProperties(
        @DefaultValue("2000000") BigDecimal budgetKrw,
        @DefaultValue("0.5") BigDecimal marginRate,
        @DefaultValue("10") int minQty,
        @DefaultValue("10") int unitQty
) {

    /** 설정 없이 쓰는 기본값(시험·옛 생성자). */
    static final IpoSubscriptionProperties DEFAULT =
            new IpoSubscriptionProperties(new BigDecimal("2000000"), new BigDecimal("0.5"), 10, 10);

    public IpoSubscriptionProperties {
        if (budgetKrw == null || budgetKrw.signum() <= 0) {
            throw new IllegalArgumentException("ipo.subscription.budget-krw는 0보다 커야 한다");
        }
        if (marginRate == null || marginRate.signum() <= 0 || marginRate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("ipo.subscription.margin-rate는 0보다 크고 1 이하여야 한다");
        }
        if (minQty <= 0 || unitQty <= 0) {
            throw new IllegalArgumentException("ipo.subscription.min-qty·unit-qty는 0보다 커야 한다");
        }
    }
}
