package com.autostock.monitor.view;

/**
 * 시스템(설정) 상태 View DTO — 지금 어떤 모드/기능이 켜져 있는지 한눈에 보여준다.
 *
 * <p>값을 만드는 쪽({@code monitor.DashboardFacade})은 일부러 다른 모듈의 설정 클래스
 * (execution.ExecutionProperties 제외)를 직접 참조하지 않고 {@code @Value}로 원본 설정
 * 프로퍼티 키를 그대로 읽는다 — 특히 {@code strategy.c3.enabled}를 strategy 모듈의
 * {@code C3StrategyProperties} 타입으로 참조하면 monitor → strategy 의존이 생기는데,
 * strategy 모듈이 이미 상태 조회를 위해 monitor → 아니 strategy → monitor로
 * {@code TradingSystemManager}를 참조하므로 그 반대 방향까지 추가하면 순환(cycle)이 되어
 * ModularityTests가 깨진다. 프로퍼티 키만 공유하고 타입은 공유하지 않는 방식으로 순환을 피했다.
 *
 * @param executionMode SIM/LIVE (execution.ExecutionProperties.Mode.name())
 * @param c3Enabled     C3 라이브 전략 활성화 여부(strategy.c3.enabled)
 * @param wsEnabled     키움 실시간 WS 연결 활성화 여부(autostock.ws.enabled)
 */
public record SystemStatusView(String executionMode, boolean c3Enabled, boolean wsEnabled) {
}
