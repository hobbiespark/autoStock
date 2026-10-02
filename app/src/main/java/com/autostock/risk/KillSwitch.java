package com.autostock.risk;

import com.autostock.common.event.KillSwitchChanged;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 킬스위치 — 자동매매의 비상 정지 버튼. 활성화되면 모든 신규 주문이 차단된다.
 *
 * <p>규제 흐름상으로도(2025~2026) "작동하는 킬스위치"는 자동매매의 필수 인프라다. (PLAN 2절 (4))
 *
 * <p>트리거 경로 (누가 이 스위치를 누르나):
 * <ul>
 *   <li>사람: 텔레그램 원격 명령 (monitor 모듈, Phase 4)</li>
 *   <li>자동: 일 손실 한도 도달, WS 장시간 단절, 지정학 이벤트 가드 (PLAN 8절)</li>
 * </ul>
 *
 * <p>구현 노트: AtomicBoolean + compareAndSet으로 "이미 켜져 있는데 또 켜는" 중복
 * 로그를 막는다. 해제(release)는 반드시 사람(operator)의 명시적 행동으로만 —
 * 자동 해제는 의도적으로 만들지 않았다. 비상 정지가 저절로 풀리면 안 되기 때문.
 *
 * <p>Phase 4: 상태가 실제로 바뀔 때(compareAndSet 성공 시)만 {@link KillSwitchChanged}
 * 이벤트를 발행한다 — monitor 모듈이 이 이벤트를 구독해 텔레그램 CRITICAL 알림을 보낸다.
 * risk 모듈은 monitor의 존재를 전혀 모른다(이벤트만 던질 뿐) — 모듈 경계 원칙 준수.
 *
 * <h2>재기동 복원 (Phase 0.2, 사용자 결정 D-05 — aiDoc/risk-state-persistence.md)</h2>
 * 예전에는 상태가 메모리에만 있어 재기동(auto-start 포함)하면 비상 정지가 저절로 풀렸다 — "해제는 사람만"
 * 원칙이 재기동 한 번으로 깨졌다. 이제 작동·해제 때마다 {@code risk_state}에 저장하고, 빈 초기화 때
 * 복원한다. 작동 상태였으면 작동으로 시작하고, 기동 완료 후 "재기동 복원: 원 사유" 이벤트를 1회 발행해
 * 텔레그램·대시보드에 알린다.
 * <ul>
 *   <li>저장 실패: 메모리 상태가 우선이다(fail-safe) — 저장이 안 돼도 작동·해제를 되돌리지 않고 ERROR만 남긴다.</li>
 *   <li>복원 실패(읽기 예외): 상태를 모르면 막는 쪽 — 작동으로 시작한다. 사람이 확인 후 해제한다.</li>
 *   <li>"메모리 전환 → 저장 → 이벤트" 순서가 스레드 사이에서 뒤섞이지 않게 작동·해제를 락으로 직렬화한다.
 *       조회({@link #isEngaged})는 락 없이 AtomicBoolean만 읽는다(RiskGate 매 시그널 경로).</li>
 * </ul>
 */
@Component
public class KillSwitch {

    private static final Logger log = LoggerFactory.getLogger(KillSwitch.class);

    /** 재기동 복원 알림의 사유 접두어. */
    static final String RESTORED_PREFIX = "재기동 복원: ";

    private final ApplicationEventPublisher publisher;
    private final AtomicBoolean engaged = new AtomicBoolean(false);
    private final RiskStateStore store;
    private final Clock clock;

    /** 작동·해제 직렬화 — synchronized 대신 ReentrantLock(가상 스레드 pinning 회피, DailyPnlTracker와 같은 이유). */
    private final ReentrantLock lock = new ReentrantLock();

    /** 기동 시 작동 상태로 복원했으면 그 사유 — 기동 완료 알림 1회 후 비운다. */
    private volatile String restoredReason;

    /** 지금 작동 중이면 그 사유 — 헬스 killSwitch(실행 계획 1.7)가 읽는다. 해제되면 null. */
    private volatile String engagedReason;

    public KillSwitch(ApplicationEventPublisher publisher, RiskStateStore store, Clock clock) {
        this.clock = clock;
        this.publisher = publisher;
        this.store = store;
    }

    public boolean isEngaged() {
        return engaged.get();
    }

    /** 작동 중이면 그 사유(재기동 복원이면 "재기동 복원: …"), 해제 상태면 빈 값. */
    public Optional<String> engagedReason() {
        return engaged.get() ? Optional.ofNullable(engagedReason) : Optional.empty();
    }

    public void engage(String reason) {
        lock.lock();
        try {
            if (engaged.compareAndSet(false, true)) {
                engagedReason = reason;
                log.warn("킬스위치 작동: {}", reason);
                persist(true, () -> store.saveEngaged(reason, clock.instant()));
                publisher.publishEvent(new KillSwitchChanged(true, reason, clock.instant()));
            }
        } finally {
            lock.unlock();
        }
    }

    public void release(String operator) {
        lock.lock();
        try {
            if (engaged.compareAndSet(true, false)) {
                log.warn("킬스위치 해제 by {}", operator);
                restoredReason = null;
                engagedReason = null;
                persist(false, () -> store.saveReleased(operator, clock.instant()));
                publisher.publishEvent(new KillSwitchChanged(false, operator, clock.instant()));
            }
        } finally {
            lock.unlock();
        }
    }

    private void persist(boolean engagedNow, Runnable save) {
        try {
            save.run();
        } catch (RuntimeException e) {
            // 메모리 상태가 우선(fail-safe). 작동 저장 실패면 재기동 시 해제로 뜰 수 있으니 ERROR로 남긴다
            // (해제 저장 실패는 재기동 시 작동으로 떠 안전 방향).
            log.error("킬스위치 상태 저장 실패(engaged={}) — 메모리 상태는 유지한다. 재기동 전 DB 확인 필요", engagedNow, e);
        }
    }

    /**
     * 저장된 상태 복원 — 빈 초기화 때 실행해, 기동 완료({@link ApplicationReadyEvent}) 리스너들(자동 시작,
     * 시작 절차의 DEGRADED 판정 등)이 이미 복원된 상태를 보게 한다. 알림은 {@link #announceRestored}가 보낸다.
     */
    @PostConstruct
    void restorePersistedState() {
        try {
            store.loadKillSwitch().ifPresent(state -> {
                if (state.isKillSwitchEngaged()) {
                    engaged.set(true);
                    restoredReason = state.getKillSwitchReason() == null ? "(사유 없음)" : state.getKillSwitchReason();
                    engagedReason = RESTORED_PREFIX + restoredReason;
                    log.warn("킬스위치 재기동 복원 — 작동 상태로 시작: {} (작동 시각 {})", restoredReason, state.getChangedAt());
                }
            });
        } catch (RuntimeException e) {
            engaged.set(true);
            restoredReason = "상태 복원 실패 — 확인 후 수동 해제 필요(" + e.getClass().getSimpleName() + ")";
            engagedReason = restoredReason;
            log.error("킬스위치 상태 복원 실패 — 안전을 위해 작동 상태로 시작한다", e);
        }
    }

    /** 기동 완료 후 복원 알림 1회 — 텔레그램 CRITICAL·대시보드 이벤트 피드로 이어진다. */
    @EventListener(ApplicationReadyEvent.class)
    public void announceRestored() {
        lock.lock();
        try {
            String reason = restoredReason;
            restoredReason = null;
            if (reason != null && engaged.get()) {
                publisher.publishEvent(new KillSwitchChanged(true, RESTORED_PREFIX + reason, clock.instant()));
            }
        } finally {
            lock.unlock();
        }
    }
}
