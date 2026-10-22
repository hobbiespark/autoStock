package com.autostock.audit;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.MapPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 시세 감사 조건식({@link EventAuditListener#MARKET_TICK_CONDITION})을 실제 스프링 컨텍스트에서 평가해 본다(2026-10-16).
 * 식이 틀리면 시세 이벤트를 발행하는 WS 수신 스레드에서 예외가 나므로 기동 전에 잡는다.
 */
class MarketTickAuditConditionTest {

    record Ping(String value) {
    }

    static class Recorder {
        final List<Ping> received = new ArrayList<>();

        @EventListener(condition = EventAuditListener.MARKET_TICK_CONDITION)
        public void on(Ping ping) {
            received.add(ping);
        }
    }

    private List<Ping> publishWith(Map<String, Object> props) {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", props));
            ctx.registerBean(Recorder.class);
            ctx.refresh();
            ctx.publishEvent(new Ping("x"));
            return ctx.getBean(Recorder.class).received;
        }
    }

    @Test
    void 기본은_기록하지_않는다() {
        assertEquals(0, publishWith(Map.of()).size());
    }

    @Test
    void 설정으로_켜면_기록한다() {
        assertEquals(1, publishWith(Map.of("autostock.audit.market-ticks", "true")).size());
    }
}
