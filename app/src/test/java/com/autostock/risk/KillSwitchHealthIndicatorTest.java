package com.autostock.risk;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

/**
 * 헬스 killSwitch·게이지 risk.killswitch.engaged(실행 계획 1.7) — 킬스위치는 장애가 아니므로 늘 UP, 세부에 작동 여부와 사유.
 */
class KillSwitchHealthIndicatorTest {

    private final KillSwitch killSwitch = new KillSwitch(event -> { }, mock(RiskStateStore.class), Clock.systemUTC());

    @Test
    void 작동해도_UP이고_세부에_작동_여부와_사유를_싣는다() {
        KillSwitchHealthIndicator indicator = new KillSwitchHealthIndicator(killSwitch);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new RiskMetrics(killSwitch).bindTo(registry);

        Health released = indicator.health();
        assertEquals(Status.UP, released.getStatus());
        assertEquals(false, released.getDetails().get("engaged"));
        assertFalse(released.getDetails().containsKey("reason"));
        assertEquals(0.0, registry.get("risk.killswitch.engaged").gauge().value());

        killSwitch.engage("일 손실 한도 도달");
        Health engaged = indicator.health();
        assertEquals(Status.UP, engaged.getStatus());
        assertEquals(true, engaged.getDetails().get("engaged"));
        assertEquals("일 손실 한도 도달", engaged.getDetails().get("reason"));
        assertEquals(1.0, registry.get("risk.killswitch.engaged").gauge().value());

        killSwitch.release("dashboard");
        assertFalse(indicator.health().getDetails().containsKey("reason"));
    }
}
