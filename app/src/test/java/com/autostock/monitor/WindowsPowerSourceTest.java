package com.autostock.monitor;

import com.autostock.monitor.PowerSource.PowerStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Win32 {@code SYSTEM_POWER_STATUS} 값 해석 — 실제 호출은 Windows에서만 된다(10/12 노트북 배터리 대기 정지 대응).
 */
class WindowsPowerSourceTest {

    @Test
    void 충전기_연결과_잔량을_읽는다() {
        assertEquals(Optional.of(new PowerStatus(true, 100)), WindowsPowerSource.toStatus(1, 8, 100));   // 충전 중
        assertEquals(Optional.of(new PowerStatus(false, 73)), WindowsPowerSource.toStatus(0, 1, 73));    // 배터리
    }

    @Test
    void 충전기_연결을_모르면_빈_값이다() {
        assertEquals(Optional.empty(), WindowsPowerSource.toStatus(255, 1, 50));
    }

    @Test
    void 배터리가_없거나_잔량을_모르면_잔량만_비운다() {
        assertEquals(Optional.of(new PowerStatus(true, null)), WindowsPowerSource.toStatus(1, 128, 255));   // 데스크톱
        assertEquals(Optional.of(new PowerStatus(false, null)), WindowsPowerSource.toStatus(0, 255, 255));  // 모름
        assertEquals(Optional.of(new PowerStatus(true, 40)), WindowsPowerSource.toStatus(1, 255, 40));      // 플래그만 모름
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)   // 운영 PC(Windows)에서 돌리면 실제 전원 상태를 읽는다(2026-10-21 PC 시험에서 실패로 드러남)
    void Windows가_아니면_빈_값이다() {
        assertEquals(Optional.empty(), new WindowsPowerSource().read());   // 컨테이너(리눅스)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void Windows에서는_실제_전원_상태를_예외_없이_읽는다() {
        assertDoesNotThrow(() -> new WindowsPowerSource().read());
    }

    @Test
    void 잔량_글자() {
        assertEquals("배터리 73%", new PowerStatus(false, 73).batteryText());
        assertEquals("배터리 잔량 모름", new PowerStatus(true, null).batteryText());
    }
}
