package com.autostock.risk;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 헬스 {@code killSwitch} (실행 계획 1.7, aiDoc/observability.md) — 항상 UP이고 세부에 작동 여부·사유를 싣는다.
 * 킬스위치는 장애가 아니라 안전장치가 제 일을 하는 상태라 DOWN으로 올리지 않는다(전체 헬스를 흔들지 않게).
 */
@Component
class KillSwitchHealthIndicator implements HealthIndicator {

    private final KillSwitch killSwitch;

    KillSwitchHealthIndicator(KillSwitch killSwitch) {
        this.killSwitch = killSwitch;
    }

    @Override
    public Health health() {
        Health.Builder builder = Health.up().withDetail("engaged", killSwitch.isEngaged());
        killSwitch.engagedReason().ifPresent(reason -> builder.withDetail("reason", reason));
        return builder.build();
    }
}
