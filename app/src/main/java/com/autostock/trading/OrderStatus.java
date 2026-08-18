package com.autostock.trading;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 주문 상태 — 11단계로 확장된 상태기계 (PLAN.md ADR-6, docs/ARCHITECTURE.md 6절).
 *
 * <p>{@code orders} 테이블의 {@code status} 컬럼 값과 1:1 대응한다(name()을 그대로 저장).
 * 상태 전이는 여기 정의한 {@link #canTransitionTo(OrderStatus)} 표를 벗어날 수 없다 —
 * 실제 강제는 {@link OrderEntity#transitionTo(OrderStatus)}가 담당한다.
 *
 * <pre>
 *   CREATED → VALIDATED → SUBMITTING → SUBMITTED → ACCEPTED → PARTIALLY_FILLED → FILLED
 *                             │            ├─ REJECTED
 *                             │            ├─ CANCEL_REQUESTED → CANCELLED
 *                             └─ UNKNOWN ──┘
 * </pre>
 */
public enum OrderStatus {

    /** 주문 객체가 막 생성됨 — 아직 아무 검증도 거치지 않은 최초 상태. */
    CREATED,

    /** 리스크 게이트를 통과해 "주문할 자격이 있다"고 확인된 상태(수량·가격 등 형식 검증 포함). */
    VALIDATED,

    /**
     * 브로커로 전송하기 직전에 기록하는 상태.
     * <p>왜 필요한가 — SUBMITTING과 SUBMITTED를 분리하지 않으면 "DB에 저장은 됐는데
     * 네트워크 전송 중 앱이 죽은" 경우를 구분할 수 없다. SUBMITTING 상태로 DB에 먼저
     * 저장한 뒤 전송하면, 재시작 후 SUBMITTING 상태로 남아있는 주문을 "전송했는지 안
     * 했는지 모르는 주문"으로 식별해 Reconciliation 대상에 올릴 수 있다.
     */
    SUBMITTING,

    /** 브로커가 주문을 접수했다고 응답함(HTTP 200 + 주문번호 발급). 체결은 아직 아님. */
    SUBMITTED,

    /** 브로커 시스템/거래소가 주문을 정식으로 접수 확인함(체결통보 등으로 확인되는 상태). */
    ACCEPTED,

    /** 주문 수량 중 일부만 체결됨 — 나머지는 계속 미체결로 남아있다. */
    PARTIALLY_FILLED,

    /** 주문 수량 전량이 체결됨 — 종결 상태. */
    FILLED,

    /** 미체결 주문에 대해 취소를 요청함(StaleOrderCanceller 등) — 브로커 응답 대기 중. */
    CANCEL_REQUESTED,

    /** 취소가 완료됨 — 종결 상태. */
    CANCELLED,

    /** 브로커가 주문 접수 자체를 거부함(파라미터 오류, 잔고 부족 등) — 종결 상태. */
    REJECTED,

    /**
     * 결과를 모름 — 이 상태가 이 상태기계의 핵심이다(PLAN.md ADR-6 6절).
     * <p>주문 전송 중 타임아웃/네트워크 오류가 나면 "주문이 실제로 접수됐는지 아닌지"를
     * 알 수 없다. 이때 REJECTED로 잘못 단정하고 재시도하면 중복 주문이 나갈 수 있고,
     * SUBMITTED로 잘못 단정하면 실제로는 안 나간 주문을 있는 것처럼 취급하게 된다.
     * 그래서 "모른다"를 있는 그대로의 상태로 남겨두고, {@code ReconciliationService}가
     * 브로커 조회로 진짜 상태를 확인해 해소한다.
     */
    UNKNOWN;

    /** 합법 전이 표. EnumMap+EnumSet으로 상수 시점(static) 1회만 구성한다. */
    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = buildTransitions();

    private static Map<OrderStatus, Set<OrderStatus>> buildTransitions() {
        Map<OrderStatus, Set<OrderStatus>> t = new EnumMap<>(OrderStatus.class);
        t.put(CREATED, EnumSet.of(VALIDATED));
        t.put(VALIDATED, EnumSet.of(SUBMITTING));
        t.put(SUBMITTING, EnumSet.of(SUBMITTED, UNKNOWN, REJECTED));
        t.put(SUBMITTED, EnumSet.of(ACCEPTED, PARTIALLY_FILLED, FILLED, REJECTED, CANCEL_REQUESTED, UNKNOWN));
        t.put(ACCEPTED, EnumSet.of(PARTIALLY_FILLED, FILLED, CANCEL_REQUESTED, UNKNOWN));
        t.put(PARTIALLY_FILLED, EnumSet.of(PARTIALLY_FILLED, FILLED, CANCEL_REQUESTED, UNKNOWN));
        t.put(CANCEL_REQUESTED, EnumSet.of(CANCELLED, FILLED, PARTIALLY_FILLED, UNKNOWN));
        // UNKNOWN은 Reconciliation이 브로커 조회로 실제 상태를 확인한 뒤 어디로든 확정될 수 있다.
        t.put(UNKNOWN, EnumSet.of(SUBMITTED, ACCEPTED, PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED));
        // 종결 상태(FILLED/CANCELLED/REJECTED)는 전이 대상이 없다(빈 집합) — 아래 종결 상태들은
        // buildTransitions()에 아예 등록하지 않으므로 getOrDefault가 빈 Set을 돌려준다.
        return Map.copyOf(t);
    }

    /**
     * 현재 상태에서 target으로 전이가 합법인지 확인한다.
     * 종결 상태(FILLED, CANCELLED, REJECTED)에서는 어떤 target도 false다(표에 등록 자체가 없음).
     */
    public boolean canTransitionTo(OrderStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    /** 더 이상 전이가 불가능한 종결 상태인지 확인한다. */
    public boolean isTerminal() {
        return TRANSITIONS.getOrDefault(this, Set.of()).isEmpty();
    }
}
