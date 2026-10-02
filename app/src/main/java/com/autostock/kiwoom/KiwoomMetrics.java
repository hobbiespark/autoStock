package com.autostock.kiwoom;

import com.autostock.common.event.BrokerAuthFailure;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 키움 인증 실패 카운터 {@code kiwoom.auth.failure{code}} (실행 계획 1.7, aiDoc/observability.md).
 *
 * <p>{@link BrokerAuthFailure}는 토큰 발급({@link TokenManager})과 REST 호출({@link KiwoomRestClient}) 두 곳에서 나온다 —
 * 발행 지점마다 세지 않고 이벤트 하나로 센다. 유량 재시도 카운터는 {@link KiwoomRestClient}가 직접 센다.
 */
@Component
class KiwoomMetrics {

    private final MeterRegistry meterRegistry;

    KiwoomMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @EventListener
    public void onBrokerAuthFailure(BrokerAuthFailure event) {
        meterRegistry.counter("kiwoom.auth.failure", "code", String.valueOf(event.code())).increment();
    }
}
