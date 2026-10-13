package com.autostock.monitor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 절전 차단 요청 값 — 2026-10-12 실측: 최신 대기 모드 PC에서는 시스템 요청만으로 대기 진입을 막지 못해 화면 요청을 더한다.
 */
class SystemSleepBlockerTest {

    @Test
    void 화면_켜짐을_켜면_시스템과_화면_요청을_함께_건다() {
        int flags = SystemSleepBlocker.flags(true, true);

        assertEquals(SystemSleepBlocker.ES_CONTINUOUS | SystemSleepBlocker.ES_SYSTEM_REQUIRED
                | SystemSleepBlocker.ES_DISPLAY_REQUIRED, flags);
    }

    @Test
    void 화면_켜짐을_끄면_시스템_요청만_건다() {
        assertEquals(SystemSleepBlocker.ES_CONTINUOUS | SystemSleepBlocker.ES_SYSTEM_REQUIRED,
                SystemSleepBlocker.flags(true, false));
    }

    @Test
    void 풀_때는_설정과_상관없이_지속_플래그만_남겨_앞선_요청을_지운다() {
        assertEquals(SystemSleepBlocker.ES_CONTINUOUS, SystemSleepBlocker.flags(false, true));
        assertEquals(SystemSleepBlocker.ES_CONTINUOUS, SystemSleepBlocker.flags(false, false));
    }

    @Test
    void Windows가_아니면_요청해도_아무것도_하지_않는다() {
        SystemSleepBlocker blocker = new SystemSleepBlocker(true);

        assertDoesNotThrow(() -> blocker.preventSleep(true));   // 컨테이너(리눅스) — JNA 호출 없음
        assertDoesNotThrow(() -> blocker.preventSleep(false));
        blocker.shutdown();
    }
}
