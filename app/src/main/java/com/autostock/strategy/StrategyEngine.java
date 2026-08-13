package com.autostock.strategy;

import com.autostock.common.event.MarketTick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 전략 실행기 골격. Phase 4에서 변동성 돌파 v1 탑재.
 * Strategy 구현체는 이 모듈 내부에만 추가 — 외부 모듈 참조 금지.
 */
@Component
public class StrategyEngine {

    private static final Logger log = LoggerFactory.getLogger(StrategyEngine.class);

    private final ApplicationEventPublisher publisher;

    public StrategyEngine(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @EventListener
    public void onTick(MarketTick tick) {
        // TODO Phase 4: 변동성 돌파 전략 — 시그널 생성 시 publisher.publishEvent(new Signal(...))
        log.trace("tick 수신: {}", tick.symbol());
    }
}
