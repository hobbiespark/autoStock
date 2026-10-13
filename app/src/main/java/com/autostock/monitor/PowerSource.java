package com.autostock.monitor;

import java.util.Optional;

/**
 * PC 전원 상태 — 충전기 연결 여부와 배터리 잔량. {@link SleepGuard}가 장 대응 시간에 배터리로 도는지 본다.
 *
 * <p>배경(2026-10-12, aiDoc/sleep-resume.md 5절): 운영 PC는 노트북이다. 충전기가 빠진 채 배터리로 장을 맞아, 배터리 쪽
 * 화면 끄기(60분)·절전(120분) 설정대로 대기에 들어가 앱이 09:19부터 멈췄다. 배터리는 시간당 약 24%씩 줄었다.
 */
public interface PowerSource {

    /** 지금 전원 상태 — 알 수 없으면(Windows가 아님, 조회 실패, 상태 모름) 빈 값. */
    Optional<PowerStatus> read();

    /**
     * @param onAc           충전기(AC)에 연결돼 있으면 true
     * @param batteryPercent 배터리 잔량(0~100). 배터리가 없거나 모르면 null
     */
    record PowerStatus(boolean onAc, Integer batteryPercent) {

        /** 로그·알림용 — "배터리 73%" 또는 "배터리 잔량 모름". */
        public String batteryText() {
            return batteryPercent == null ? "배터리 잔량 모름" : "배터리 %d%%".formatted(batteryPercent);
        }
    }
}
