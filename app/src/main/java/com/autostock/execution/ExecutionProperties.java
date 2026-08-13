package com.autostock.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 실행 모드: SIM(즉시 체결 시뮬레이션) / LIVE(키움 주문 API).
 * 앱키 없이도 SIM으로 전체 이벤트 루프 검증 가능.
 */
@ConfigurationProperties(prefix = "execution")
public record ExecutionProperties(Mode mode) {

    public enum Mode { SIM, LIVE }
}
