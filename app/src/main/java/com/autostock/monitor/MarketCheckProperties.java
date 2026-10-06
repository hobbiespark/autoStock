package com.autostock.monitor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 장중 자가 점검 설정 — {@link MarketDayCheck}. aiDoc/market-hours-automation.md 참고.
 *
 * @param enabled                09:15 전략 실행 확인과 10:00·14:00 상태 점검을 켠다
 * @param strategyRunNotice      전략 정기 실행 요약(예: C3 09:05)을 텔레그램으로 한 줄 보낸다
 * @param maxMessageAgeSeconds   장중 WS 마지막 메시지가 이보다 오래되면 이상으로 본다(PING이 약 10초 간격)
 */
@ConfigurationProperties(prefix = "monitor.market-check")
public record MarketCheckProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("true") boolean strategyRunNotice,
        @DefaultValue("120") long maxMessageAgeSeconds
) {
}
