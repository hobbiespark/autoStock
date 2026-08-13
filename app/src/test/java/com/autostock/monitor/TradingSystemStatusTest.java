package com.autostock.monitor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TradingSystemStatus 전이표 전수 검증 — 합법 전이 전부 + 대표 불법 전이 케이스.
 */
class TradingSystemStatusTest {

    @Test
    void STOPPED는_STARTING으로만_전이_가능() {
        assertTrue(TradingSystemStatus.STOPPED.canTransitionTo(TradingSystemStatus.STARTING));
        for (TradingSystemStatus target : TradingSystemStatus.values()) {
            if (target != TradingSystemStatus.STARTING) {
                assertFalse(TradingSystemStatus.STOPPED.canTransitionTo(target),
                        "STOPPED → " + target + "는 불가능해야 함");
            }
        }
    }

    @Test
    void STARTING은_RUNNING_또는_ERROR로만_전이_가능() {
        assertTrue(TradingSystemStatus.STARTING.canTransitionTo(TradingSystemStatus.RUNNING));
        assertTrue(TradingSystemStatus.STARTING.canTransitionTo(TradingSystemStatus.ERROR));
        assertFalse(TradingSystemStatus.STARTING.canTransitionTo(TradingSystemStatus.STOPPED));
        assertFalse(TradingSystemStatus.STARTING.canTransitionTo(TradingSystemStatus.STOPPING));
        assertFalse(TradingSystemStatus.STARTING.canTransitionTo(TradingSystemStatus.DEGRADED));
    }

    @Test
    void RUNNING은_STOPPING_DEGRADED_ERROR로_전이_가능() {
        assertTrue(TradingSystemStatus.RUNNING.canTransitionTo(TradingSystemStatus.STOPPING));
        assertTrue(TradingSystemStatus.RUNNING.canTransitionTo(TradingSystemStatus.DEGRADED));
        assertTrue(TradingSystemStatus.RUNNING.canTransitionTo(TradingSystemStatus.ERROR));
        assertFalse(TradingSystemStatus.RUNNING.canTransitionTo(TradingSystemStatus.STARTING));
        assertFalse(TradingSystemStatus.RUNNING.canTransitionTo(TradingSystemStatus.STOPPED));
    }

    @Test
    void DEGRADED는_RUNNING_STOPPING_ERROR로_전이_가능() {
        assertTrue(TradingSystemStatus.DEGRADED.canTransitionTo(TradingSystemStatus.RUNNING));
        assertTrue(TradingSystemStatus.DEGRADED.canTransitionTo(TradingSystemStatus.STOPPING));
        assertTrue(TradingSystemStatus.DEGRADED.canTransitionTo(TradingSystemStatus.ERROR));
        assertFalse(TradingSystemStatus.DEGRADED.canTransitionTo(TradingSystemStatus.STARTING));
        assertFalse(TradingSystemStatus.DEGRADED.canTransitionTo(TradingSystemStatus.STOPPED));
    }

    @Test
    void ERROR는_STOPPING으로만_전이_가능_RUNNING_직행_금지() {
        assertTrue(TradingSystemStatus.ERROR.canTransitionTo(TradingSystemStatus.STOPPING));
        assertFalse(TradingSystemStatus.ERROR.canTransitionTo(TradingSystemStatus.RUNNING),
                "ERROR에서 정리 없이 바로 RUNNING 복귀는 금지돼야 함");
        for (TradingSystemStatus target : TradingSystemStatus.values()) {
            if (target != TradingSystemStatus.STOPPING) {
                assertFalse(TradingSystemStatus.ERROR.canTransitionTo(target),
                        "ERROR → " + target + "는 불가능해야 함");
            }
        }
    }

    @Test
    void STOPPING은_STOPPED로만_전이_가능() {
        assertTrue(TradingSystemStatus.STOPPING.canTransitionTo(TradingSystemStatus.STOPPED));
        for (TradingSystemStatus target : TradingSystemStatus.values()) {
            if (target != TradingSystemStatus.STOPPED) {
                assertFalse(TradingSystemStatus.STOPPING.canTransitionTo(target),
                        "STOPPING → " + target + "는 불가능해야 함");
            }
        }
    }
}
