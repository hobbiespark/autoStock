package com.autostock.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 실행 모드: SIM(즉시 체결 시뮬레이션) / LIVE(키움 주문 API).
 * 앱키 없이도 SIM으로 전체 이벤트 루프 검증 가능.
 *
 * @param mode              SIM/LIVE
 * @param staleOrderTimeout SUBMITTED 상태로 이 시간 이상 머문 주문을 {@link StaleOrderCanceller}가
 *                          자동 취소 요청한다(기본 5분). "5m"/"300s" 같은 Spring Duration 표기 사용.
 */
@ConfigurationProperties(prefix = "execution")
public record ExecutionProperties(Mode mode, @DefaultValue("5m") Duration staleOrderTimeout) {

    public enum Mode { SIM, LIVE }
}
