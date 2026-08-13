package com.autostock.risk;

import com.autostock.common.event.KillSwitchChanged;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KillSwitch 이벤트 발행 검증 — monitor 모듈의 텔레그램 CRITICAL 알림이 이 이벤트에 의존하므로
 * "상태가 실제로 바뀔 때만 정확히 한 번" 발행되는지가 중요하다.
 */
class KillSwitchTest {

    private final List<Object> published = new ArrayList<>();
    private final ApplicationEventPublisher publisher = published::add;
    private KillSwitch killSwitch;

    @BeforeEach
    void setUp() {
        killSwitch = new KillSwitch(publisher);
    }

    @Test
    void engage하면_KillSwitchChanged_engaged_true가_발행된다() {
        killSwitch.engage("일 손실 한도 도달");

        assertEquals(1, published.size());
        KillSwitchChanged event = (KillSwitchChanged) published.get(0);
        assertTrue(event.engaged());
        assertEquals("일 손실 한도 도달", event.reason());
    }

    @Test
    void release하면_KillSwitchChanged_engaged_false가_발행된다() {
        killSwitch.engage("테스트");
        published.clear();

        killSwitch.release("operator-1");

        assertEquals(1, published.size());
        KillSwitchChanged event = (KillSwitchChanged) published.get(0);
        assertFalse(event.engaged());
        assertEquals("operator-1", event.reason());
    }

    @Test
    void 이미_작동_중이면_중복_engage는_이벤트를_다시_발행하지_않는다() {
        killSwitch.engage("최초");
        killSwitch.engage("중복 시도");

        assertEquals(1, published.size());
    }

    @Test
    void 이미_해제_상태면_release는_이벤트를_발행하지_않는다() {
        killSwitch.release("operator-1");

        assertTrue(published.isEmpty());
    }
}
