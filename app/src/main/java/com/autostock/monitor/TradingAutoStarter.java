package com.autostock.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 부팅 자동 시작 — {@code autostock.trading.auto-start=true}면 앱 기동 완료 시
 * 대시보드 [시작] 버튼과 동일한 경로({@link TradingSystemManager#start()})를 자동 호출한다.
 *
 * <p>목적: Windows 재부팅 후 무인 복구(RUNBOOK 5절 — start_autostock.bat가 이 플래그를 켠다).
 * 사람이 매번 [시작]을 눌러야 한다면 "무인" 운영이 아니다. 기본값은 false — 수동 기동
 * (개발·SIM 검증)에서는 기존 동작 그대로다.
 *
 * <p>킬스위치와의 관계: 킬스위치가 켜진 채 재시작해도 start() 자체는 RUNNING으로 가지만
 * RiskGate가 모든 주문을 차단하므로 안전하다(킬스위치 해제는 사람이 원인 확인 후 수동으로만 —
 * RUNBOOK 7절 원칙 유지).
 */
@Component
public class TradingAutoStarter {

    private static final Logger log = LoggerFactory.getLogger(TradingAutoStarter.class);

    private final TradingSystemManager manager;
    private final boolean autoStart;

    public TradingAutoStarter(TradingSystemManager manager,
                              @Value("${autostock.trading.auto-start:false}") boolean autoStart) {
        this.manager = manager;
        this.autoStart = autoStart;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!autoStart) {
            return;
        }
        log.info("auto-start 활성 — 매매 시스템 자동 시작 (현재 상태: {})", manager.status());
        manager.start();
    }
}
