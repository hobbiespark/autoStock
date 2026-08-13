package com.autostock.monitor;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 자동매매 "운영" 상태기계 — 대시보드/시작·정지 명령이 보는 시스템 전체의 상태다
 * (docs/ARCHITECTURE.md 10절, PLAN.md ADR-6). {@code execution.OrderStatus}(주문 하나의 상태)와는
 * 다른 개념이다 — 이쪽은 "시스템이 지금 매매를 해도 되는 상태인가"를 나타낸다.
 *
 * <pre>
 *   STOPPED → STARTING → RUNNING → STOPPING → STOPPED
 *                │           │
 *                └─ ERROR    ├─ DEGRADED ─┬─ RUNNING(회복)
 *                            │            ├─ STOPPING
 *                            │            └─ ERROR
 *                            └─ ERROR
 *   ERROR → STOPPING (정리 후 정지만 허용 — ERROR에서 바로 RUNNING으로 못 돌아간다)
 * </pre>
 *
 * <p>불리언(자동매매 on/off)이 아니라 상태기계로 만든 이유: "지금 시작 준비 중인지",
 * "장애로 매매가 막혔을 뿐 시스템은 살아있는지"(DEGRADED)를 구분해야 운영자가 상황을
 * 오판하지 않는다. 합법 전이표는 {@link #canTransitionTo(TradingSystemStatus)}가 강제하고,
 * 실제 전이는 {@link TradingSystemManager#transitionTo(TradingSystemStatus)}에서만 일어난다
 * (패턴은 {@code execution.OrderStatus}를 그대로 따랐다).
 */
public enum TradingSystemStatus {

    /** 정지 상태 — 아무 것도 안 하는 초기/최종 상태. */
    STOPPED,

    /** 시작 명령을 받아 준비 절차(LIVE면 Reconciliation)를 진행 중. */
    STARTING,

    /** 정상 운영 중 — 이 상태일 때만 전략이 실제로 매매 판단을 실행한다(strategy 모듈 이중 가드). */
    RUNNING,

    /** 정지 명령을 받아 정리 절차(TODO: 미체결 주문 정리)를 진행 중. */
    STOPPING,

    /** 킬스위치 작동 등으로 매매가 차단됐지만 시스템 자체는 살아있는 상태 — 자동 복귀 가능. */
    DEGRADED,

    /** 준비 절차 실패 등 복구 불가능한 오류 — 정리(STOPPING) 후 정지만 허용된다. */
    ERROR;

    /** 합법 전이 표. EnumMap+EnumSet으로 상수 시점(static) 1회만 구성한다(OrderStatus 패턴). */
    private static final Map<TradingSystemStatus, Set<TradingSystemStatus>> TRANSITIONS = buildTransitions();

    private static Map<TradingSystemStatus, Set<TradingSystemStatus>> buildTransitions() {
        Map<TradingSystemStatus, Set<TradingSystemStatus>> t = new EnumMap<>(TradingSystemStatus.class);
        t.put(STOPPED, EnumSet.of(STARTING));
        t.put(STARTING, EnumSet.of(RUNNING, ERROR));
        t.put(RUNNING, EnumSet.of(STOPPING, DEGRADED, ERROR));
        t.put(DEGRADED, EnumSet.of(RUNNING, STOPPING, ERROR)); // RUNNING = 킬스위치 해제 등으로 회복
        t.put(ERROR, EnumSet.of(STOPPING)); // 정리 후 정지만 허용 — 바로 RUNNING 복귀 금지
        t.put(STOPPING, EnumSet.of(STOPPED));
        // STOPPED는 위에서 이미 등록. 여기 없는 상태는 등록하지 않아 빈 Set(종결) 취급.
        return Map.copyOf(t);
    }

    /** 현재 상태에서 target으로 전이가 합법인지 확인한다. */
    public boolean canTransitionTo(TradingSystemStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }
}
