package com.autostock.risk;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * risk 모듈 게이지 (실행 계획 1.7, aiDoc/observability.md) — {@code risk.killswitch.engaged}(작동 1, 해제 0).
 */
@Component
class RiskMetrics implements MeterBinder {

    private final KillSwitch killSwitch;

    RiskMetrics(KillSwitch killSwitch) {
        this.killSwitch = killSwitch;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("risk.killswitch.engaged", killSwitch, k -> k.isEngaged() ? 1 : 0)
                .description("킬스위치 작동 여부(1=작동)")
                .strongReference(true)
                .register(registry);
    }
}
