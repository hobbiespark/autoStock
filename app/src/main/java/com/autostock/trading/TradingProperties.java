package com.autostock.trading;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 실행 모드: SIM(즉시 체결 시뮬레이션) / LIVE(키움 주문 API).
 * 앱키 없이도 SIM으로 전체 이벤트 루프 검증 가능.
 *
 * <p>ADR-6 재편으로 execution → trading 모듈로 이동, 클래스명도 ExecutionProperties →
 * TradingProperties로 바뀌었다. 다만 {@code @ConfigurationProperties} prefix는
 * <b>{@code execution}을 그대로 유지</b>한다 — application.yml의 기존 {@code execution.mode}/
 * {@code execution.stale-order-timeout} 키를 깨지 않기 위한 yml 호환성 결정이다(운영 설정
 * 파일·환경변수를 함께 바꿀 필요가 없다).
 *
 * @param mode              SIM/LIVE
 * @param staleOrderTimeout SUBMITTED 상태로 이 시간 이상 머문 주문을 {@link StaleOrderCanceller}가
 *                          자동 취소 요청한다(기본 5분). "5m"/"300s" 같은 Spring Duration 표기 사용.
 */
@ConfigurationProperties(prefix = "execution")
public record TradingProperties(Mode mode, @DefaultValue("5m") Duration staleOrderTimeout) {

    public enum Mode { SIM, LIVE }
}
