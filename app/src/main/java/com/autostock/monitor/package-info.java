/**
 * 관측 모듈: 텔레그램 알림/원격 명령(킬스위치), 헬스체크, 성과 리포트,
 * 대시보드(CQRS Lite: View DTO + Facade), 자동매매 운영 상태기계(TradingSystemManager).
 *
 * <p>모듈 간 의존(ADR-6 재편 반영): portfolio 모듈의 {@code PositionBook}, risk 모듈의
 * {@code KillSwitch}/{@code DailyLimitTracker}/{@code DailyPnlTracker}(기존과 동일) 외에,
 * {@link com.autostock.monitor.TradingSystemManager}가 시작 절차에서 trading 모듈의
 * {@code ReconciliationService.reconcile()}을 직접 호출한다(LIVE 모드 준비 절차,
 * ARCHITECTURE.md 8·10절, 기존 execution → 분리된 trading으로 이동) — trading은 monitor를
 * 참조하지 않으므로 순환은 없다.
 * strategy 모듈의 설정({@code strategy.c3.enabled})은 타입 의존 없이 {@code @Value}로만
 * 읽는다({@link com.autostock.monitor.view.SystemStatusView} Javadoc 참고) — strategy가
 * 이미 monitor를 참조하므로(TradingSystemManager 조회) 반대 방향 타입 의존까지 추가하면
 * 순환이 생기기 때문이다.
 */
@org.springframework.modulith.ApplicationModule(displayName = "monitor")
package com.autostock.monitor;
