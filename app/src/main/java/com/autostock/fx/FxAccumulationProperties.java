package com.autostock.fx;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 외화 적립 알림 설정 — 설계 11장(D-28). 값은 데브 계획서의 규칙값이다(개인 보유량·평단은 여기 두지 않는다).
 *
 * @param alertsEnabled   감시·월초·월말 알림(기본 false). 운영 반영 뒤 12-01 전에 켠다. 입력 명령은 꺼도 동작한다
 * @param monthlyCapKrw   월 상한(원)
 * @param directBuyUsdKrw 달러 직접매수 금액(원)
 * @param directBuyJpyKrw 엔 직접매수 금액(원)
 * @param reviewStartDay  검토 시작일(포함, 데브 계획서 "15일 이후")
 */
@ConfigurationProperties(prefix = "fx.accumulation")
public record FxAccumulationProperties(
        @DefaultValue("false") boolean alertsEnabled,
        @DefaultValue("130000") long monthlyCapKrw,
        @DefaultValue("20000") long directBuyUsdKrw,
        @DefaultValue("20000") long directBuyJpyKrw,
        @DefaultValue("15") int reviewStartDay
) {
    public FxAccumulationProperties {
        if (reviewStartDay < 1 || reviewStartDay > 28) {
            throw new IllegalArgumentException("fx.accumulation.review-start-day는 1~28이어야 합니다: " + reviewStartDay);
        }
        if (monthlyCapKrw <= 0 || directBuyUsdKrw <= 0 || directBuyJpyKrw <= 0) {
            throw new IllegalArgumentException("fx.accumulation 금액은 0보다 커야 합니다");
        }
    }

    /** 그 통화의 직접매수 금액. */
    public long directBuyKrw(FxCurrency currency) {
        return currency == FxCurrency.USD ? directBuyUsdKrw : directBuyJpyKrw;
    }
}
