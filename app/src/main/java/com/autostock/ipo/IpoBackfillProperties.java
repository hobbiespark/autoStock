package com.autostock.ipo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 지난 공모 인수인 거꾸로 채우기 설정(2026-11-13, 결정 D-29, VALUNIVO 875).
 *
 * @param enabled        밤 매시 거꾸로 채우기(기본 true). DART가 꺼져 있으면({@code dart.enabled=false}) 돌지 않는다
 * @param years          몇 년 전까지 거슬러 올라갈지(계좌 개설 추천의 가장 긴 구간과 같게 5)
 * @param windowsPerPass 한 번에 읽을 2주 창 수. 2주는 list.json 약 11쪽이라 4창이면 한 번에 약 50번 호출(DART 하루 한도 2만 회 안)
 */
@ConfigurationProperties(prefix = "ipo.underwriter-backfill")
public record IpoBackfillProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("5") int years,
        @DefaultValue("4") int windowsPerPass
) {
    public IpoBackfillProperties {
        if (years < 1 || years > 10 || windowsPerPass < 1 || windowsPerPass > 26) {
            throw new IllegalArgumentException("ipo.underwriter-backfill: years 1~10, windows-per-pass 1~26");
        }
    }
}
