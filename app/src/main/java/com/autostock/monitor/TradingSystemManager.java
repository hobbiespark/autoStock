package com.autostock.monitor;

import com.autostock.common.event.KillSwitchChanged;
import com.autostock.execution.ExecutionProperties;
import com.autostock.execution.ReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 자동매매 운영 상태기계({@link TradingSystemStatus})를 관리하는 매니저 —
 * 시작/정지 Command의 유일한 진입점(docs/ARCHITECTURE.md 10절).
 *
 * <h2>Backend가 Source of Truth</h2>
 * {@link #start()}/{@link #stop()}은 각각 STARTING/STOPPING을 <b>즉시</b> 반환하고
 * 완료를 기다리지 않는다. 실제 준비/정리 절차는 가상 스레드({@link Thread#ofVirtual()})에서
 * 백그라운드로 실행되며, 최종 결과(RUNNING 도달 또는 ERROR 전이)는 {@link #status()}를
 * 다시 조회해야 알 수 있다 — FE는 명령 응답으로 화면 상태를 낙관적으로 바꾸지 않고
 * 재조회한다(index.html 주석 참고).
 *
 * <h2>strategy 모듈과의 경계</h2>
 * 이 클래스는 monitor의 <b>루트 패키지 공개 API</b>다. strategy 모듈(C3LiveStrategy)이
 * {@link #status()}를 직접 호출해 RUNNING 여부를 이중 가드로 확인한다 — 조회이므로
 * 인터페이스 직접 호출을 허용하는 ARCHITECTURE.md 9절 원칙에 따른 것이다. 반대 방향
 * (monitor → strategy)의 타입 의존은 만들지 않았다 — 만들면 순환(cycle)이 되어
 * ModularityTests(Spring Modulith verify())가 빌드를 깨뜨린다.
 *
 * <h2>킬스위치 연동</h2>
 * risk 모듈의 {@link KillSwitchChanged} 이벤트를 구독해 RUNNING↔DEGRADED를 오간다 —
 * "매매가 막혔다"는 사실을 운영 상태기계에도 반영한다.
 */
@Component
public class TradingSystemManager {

    private static final Logger log = LoggerFactory.getLogger(TradingSystemManager.class);

    private final ExecutionProperties executionProperties;
    private final ReconciliationService reconciliationService;

    private final AtomicReference<TradingSystemStatus> status =
            new AtomicReference<>(TradingSystemStatus.STOPPED);

    public TradingSystemManager(ExecutionProperties executionProperties,
                                ReconciliationService reconciliationService) {
        this.executionProperties = executionProperties;
        this.reconciliationService = reconciliationService;
    }

    /** 현재 운영 상태 조회 — 대시보드/전략 모듈이 부르는 유일한 공개 조회 메서드. */
    public TradingSystemStatus status() {
        return status.get();
    }

    /**
     * 시작 명령. STOPPED에서만 합법이며, 그 외 상태에서 부르면 IllegalStateException.
     * 반환값은 항상 STARTING — 실제 준비 절차는 백그라운드에서 진행된다(클래스 설명 참고).
     */
    public TradingSystemStatus start() {
        TradingSystemStatus result = transitionTo(TradingSystemStatus.STARTING);
        Thread.ofVirtual().name("trading-system-start").start(this::runStartupSequence);
        return result;
    }

    /**
     * 준비 절차 — LIVE 모드면 브로커 대사(Reconciliation)를 먼저 통과해야 RUNNING으로 간다
     * (ARCHITECTURE.md 8절 "Broker가 Source of Truth"). SIM은 브로커가 없으므로 즉시 RUNNING.
     * reconcile()이 예외를 던지면(브로커 연결 실패 등) ERROR로 전이한다.
     */
    private void runStartupSequence() {
        try {
            if (executionProperties.mode() == ExecutionProperties.Mode.LIVE) {
                reconciliationService.reconcile();
            }
            transitionTo(TradingSystemStatus.RUNNING);
            log.info("TradingSystemManager: 시작 준비 완료 — RUNNING");
        } catch (RuntimeException e) {
            log.error("TradingSystemManager: 시작 준비 절차 실패 — ERROR로 전이", e);
            transitionTo(TradingSystemStatus.ERROR);
        }
    }

    /**
     * 정지 명령. RUNNING/DEGRADED에서만 합법이며, 그 외 상태에서 부르면 IllegalStateException.
     * 반환값은 항상 STOPPING — 실제 정리 절차는 백그라운드에서 진행된다.
     */
    public TradingSystemStatus stop() {
        TradingSystemStatus result = transitionTo(TradingSystemStatus.STOPPING);
        Thread.ofVirtual().name("trading-system-stop").start(this::runShutdownSequence);
        return result;
    }

    /**
     * 정리 절차. TODO: 미체결 주문 취소 등 실제 정리 로직 — 지금은 로그만 남기고
     * 곧바로 STOPPED로 전이한다(과제 스펙 "현재는 로그만" 명시).
     */
    private void runShutdownSequence() {
        log.info("TradingSystemManager: 정지 절차 실행(TODO: 미체결 주문 정리) — STOPPED로 전이");
        transitionTo(TradingSystemStatus.STOPPED);
    }

    /**
     * 킬스위치 상태 변화 반영. RUNNING 중 작동하면 DEGRADED로, DEGRADED 중 해제되면
     * RUNNING으로 복귀한다. 그 외 조합(예: STARTING 중 작동)은 조용히 무시한다 —
     * 이건 사람이 내린 Command가 아니라 시스템이 자동으로 반응하는 것이므로, 조건이
     * 안 맞으면 예외를 던지지 않고 그냥 넘어간다(compareAndSet 실패 = 무시).
     */
    @EventListener
    public void on(KillSwitchChanged event) {
        if (event.engaged()) {
            if (status.compareAndSet(TradingSystemStatus.RUNNING, TradingSystemStatus.DEGRADED)) {
                log.warn("TradingSystemManager: 킬스위치 작동 — RUNNING → DEGRADED");
            }
        } else {
            if (status.compareAndSet(TradingSystemStatus.DEGRADED, TradingSystemStatus.RUNNING)) {
                log.info("TradingSystemManager: 킬스위치 해제 — DEGRADED → RUNNING 복귀");
            }
        }
    }

    /**
     * 상태 전이 — 불법 전이는 즉시 IllegalStateException(OrderEntity.transitionTo와 동일 패턴).
     * compareAndSet 루프를 쓰는 이유: AtomicReference 값을 읽고 검사하고 쓰는 세 단계 사이에
     * 다른 스레드(킬스위치 리스너 등)가 끼어들 수 있어, 원자적으로 재시도해야 한다.
     */
    private TradingSystemStatus transitionTo(TradingSystemStatus target) {
        while (true) {
            TradingSystemStatus current = status.get();
            if (!current.canTransitionTo(target)) {
                throw new IllegalStateException(
                        "불법 운영 상태 전이: " + current + " → " + target);
            }
            if (status.compareAndSet(current, target)) {
                log.info("TradingSystemManager: 상태 전이 {} → {}", current, target);
                return target;
            }
            // CAS 실패 — 동시에 다른 전이가 끼어들었으므로 최신 상태로 재시도
        }
    }
}
