package com.autostock.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OrderStatus 전이표 전수 검증 — 합법 전이 몇 개 + 불법 전이 대표 케이스 + 종결 상태 불변.
 */
class OrderStatusTest {

    @Test
    void 정상_흐름_전이는_합법() {
        assertTrue(OrderStatus.CREATED.canTransitionTo(OrderStatus.VALIDATED));
        assertTrue(OrderStatus.VALIDATED.canTransitionTo(OrderStatus.SUBMITTING));
        assertTrue(OrderStatus.SUBMITTING.canTransitionTo(OrderStatus.SUBMITTED));
        assertTrue(OrderStatus.SUBMITTED.canTransitionTo(OrderStatus.ACCEPTED));
        assertTrue(OrderStatus.ACCEPTED.canTransitionTo(OrderStatus.PARTIALLY_FILLED));
        assertTrue(OrderStatus.PARTIALLY_FILLED.canTransitionTo(OrderStatus.FILLED));
    }

    @Test
    void SUBMITTING에서_UNKNOWN과_REJECTED도_합법() {
        assertTrue(OrderStatus.SUBMITTING.canTransitionTo(OrderStatus.UNKNOWN));
        assertTrue(OrderStatus.SUBMITTING.canTransitionTo(OrderStatus.REJECTED));
    }

    @Test
    void PARTIALLY_FILLED은_자기_자신으로도_전이_가능() {
        // 부분체결 통보가 여러 번 오는 경우를 지원하기 위한 자기 전이
        assertTrue(OrderStatus.PARTIALLY_FILLED.canTransitionTo(OrderStatus.PARTIALLY_FILLED));
    }

    @Test
    void UNKNOWN은_대부분의_확정_상태로_전이_가능() {
        assertTrue(OrderStatus.UNKNOWN.canTransitionTo(OrderStatus.SUBMITTED));
        assertTrue(OrderStatus.UNKNOWN.canTransitionTo(OrderStatus.ACCEPTED));
        assertTrue(OrderStatus.UNKNOWN.canTransitionTo(OrderStatus.PARTIALLY_FILLED));
        assertTrue(OrderStatus.UNKNOWN.canTransitionTo(OrderStatus.FILLED));
        assertTrue(OrderStatus.UNKNOWN.canTransitionTo(OrderStatus.CANCELLED));
        assertTrue(OrderStatus.UNKNOWN.canTransitionTo(OrderStatus.REJECTED));
    }

    @Test
    void CANCEL_REQUESTED에서_CANCELLED_또는_체결로도_전이_가능() {
        // 취소를 요청했는데 그 사이 체결이 먼저 도착하는 경합(race)을 허용해야 한다
        assertTrue(OrderStatus.CANCEL_REQUESTED.canTransitionTo(OrderStatus.CANCELLED));
        assertTrue(OrderStatus.CANCEL_REQUESTED.canTransitionTo(OrderStatus.FILLED));
        assertTrue(OrderStatus.CANCEL_REQUESTED.canTransitionTo(OrderStatus.PARTIALLY_FILLED));
        assertTrue(OrderStatus.CANCEL_REQUESTED.canTransitionTo(OrderStatus.UNKNOWN));
    }

    @Test
    void 단계_건너뛰기는_불법() {
        assertFalse(OrderStatus.CREATED.canTransitionTo(OrderStatus.SUBMITTING));
        assertFalse(OrderStatus.CREATED.canTransitionTo(OrderStatus.SUBMITTED));
        assertFalse(OrderStatus.VALIDATED.canTransitionTo(OrderStatus.SUBMITTED));
    }

    @Test
    void 역행_전이는_불법() {
        assertFalse(OrderStatus.SUBMITTED.canTransitionTo(OrderStatus.VALIDATED));
        assertFalse(OrderStatus.FILLED.canTransitionTo(OrderStatus.SUBMITTED));
        assertFalse(OrderStatus.CANCELLED.canTransitionTo(OrderStatus.CANCEL_REQUESTED));
    }

    @Test
    void 종결_상태는_어디로도_전이_불가() {
        for (OrderStatus terminal : new OrderStatus[]{OrderStatus.FILLED, OrderStatus.CANCELLED, OrderStatus.REJECTED}) {
            assertTrue(terminal.isTerminal(), terminal + "는 종결 상태여야 한다");
            for (OrderStatus target : OrderStatus.values()) {
                assertFalse(terminal.canTransitionTo(target), terminal + " → " + target + "는 불가능해야 한다");
            }
        }
    }

    @Test
    void 비종결_상태는_isTerminal_false() {
        for (OrderStatus s : new OrderStatus[]{
                OrderStatus.CREATED, OrderStatus.VALIDATED, OrderStatus.SUBMITTING, OrderStatus.SUBMITTED,
                OrderStatus.ACCEPTED, OrderStatus.PARTIALLY_FILLED, OrderStatus.CANCEL_REQUESTED, OrderStatus.UNKNOWN}) {
            assertFalse(s.isTerminal(), s + "는 종결 상태가 아니어야 한다");
        }
    }
}
