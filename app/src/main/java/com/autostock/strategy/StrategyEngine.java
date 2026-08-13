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
 *
 * <p><b>C3는 이 경로를 쓰지 않는다(2026-08-13)</b>: C3(시계열 모멘텀 + 국면필터 + 변동성
 * 타게팅, {@link C3LiveStrategy})는 일봉 기준으로 하루 한 번만 판단하는 저빈도 전략이라
 * 장중 틱({@link MarketTick})을 볼 필요가 없다. 그래서 이 리스너를 거치지 않고
 * {@code @Scheduled} 크론으로 스스로 실행 주기를 관리한다 — 장중 틱 반응이 꼭 필요한
 * 전략(예: 변동성 돌파류)만 이 클래스에 탑재한다.
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
