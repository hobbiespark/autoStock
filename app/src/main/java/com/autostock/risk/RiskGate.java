package com.autostock.risk;

import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Signal → (사이징·한도·킬스위치 검사) → OrderRequest.
 * Phase 2~4에 걸쳐 검사 규칙 완성. 골격 단계에서는 킬스위치 검사만 활성.
 */
@Component
public class RiskGate {

    private static final Logger log = LoggerFactory.getLogger(RiskGate.class);

    private final ApplicationEventPublisher publisher;
    private final KillSwitch killSwitch;
    private final RiskProperties properties;

    public RiskGate(ApplicationEventPublisher publisher, KillSwitch killSwitch, RiskProperties properties) {
        this.publisher = publisher;
        this.killSwitch = killSwitch;
        this.properties = properties;
    }

    @EventListener
    public void onSignal(Signal signal) {
        if (killSwitch.isEngaged()) {
            log.warn("킬스위치 작동 중 — 시그널 거부: {}", signal);
            return;
        }
        // TODO Phase 2: 포지션 조회 기반 사이징 (고정비율 → PLAN 2절 (3))
        // TODO Phase 2: 일 손실 한도, 주문 횟수, 동시 보유 수 검사
        // TODO Phase 5: 거시 국면 필터 (MacroIndicator 소비)
        long quantity = 0; // 사이징 미구현 상태에서는 주문 불가
        if (quantity <= 0) {
            log.info("사이징 미구현 — 주문 미발행 (골격 단계): {}", signal.symbol());
            return;
        }
        publisher.publishEvent(new OrderRequest(
                UUID.randomUUID().toString(),
                signal.strategyId(),
                signal.symbol(),
                signal.side(),
                quantity,
                signal.refPrice(),
                Instant.now()));
    }
}
