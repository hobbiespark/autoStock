package com.autostock.ipo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 공모주 상장일 실거래 매도 설정(2026-11-05, aiDoc/ipo-automation.md 8절).
 *
 * <p>주문은 두 조건이 모두 맞을 때만 나간다: 실전 키 두 개가 있다 + {@code enabled=true}. 키가 비어 있으면 앱은 정상
 * 기동하고 이 기능만 꺼진다(모의 키로 폴백하지 않는다). 키 값은 {@link #toString()}에 나오지 않는다.
 *
 * @param enabled   실거래 매도 스위치(기본 false) — 키가 있어도 이 값이 true여야 주문한다
 * @param appKey    실전 앱키({@code KIWOOM_LIVE_APP_KEY}) — 커밋 금지
 * @param appSecret 실전 시크릿({@code KIWOOM_LIVE_APP_SECRET}) — 커밋 금지
 */
@ConfigurationProperties(prefix = "ipo.live-sell")
public record IpoLiveSellProperties(
        @DefaultValue("false") boolean enabled,
        String appKey,
        String appSecret
) {

    /** 실전 키가 둘 다 있는가. */
    public boolean hasKeys() {
        return appKey != null && !appKey.isBlank() && appSecret != null && !appSecret.isBlank();
    }

    /** 지금 상태 한 줄(키 값은 넣지 않는다). */
    String stateLabel() {
        if (!hasKeys()) {
            return "꺼짐 — 실전 키 없음(KIWOOM_LIVE_APP_KEY/SECRET)";
        }
        if (!enabled) {
            return "꺼짐 — 스위치 ipo.live-sell.enabled=false";
        }
        return "켜짐";
    }

    @Override
    public String toString() {
        return "IpoLiveSellProperties[enabled=" + enabled + ", keys=" + (hasKeys() ? "present" : "missing") + "]";
    }
}
