package com.autostock.monitor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * LogOnlyNotifier — monitor.telegram.enabled=false(기본값)일 때 꽂히는 no-op 경로.
 * 로그만 남기고 외부 호출은 전혀 하지 않으므로, "예외 없이 끝난다"만 확인하면 충분하다.
 */
class LogOnlyNotifierTest {

    @Test
    void notify는_외부호출_없이_로그만_남기고_끝난다() {
        LogOnlyNotifier notifier = new LogOnlyNotifier();

        assertDoesNotThrow(() -> notifier.notify(NoticeLevel.CRITICAL, "킬스위치 작동: 테스트"));
    }
}
