package com.autostock.common.event;

import java.time.Instant;

/**
 * 킬스위치(비상 정지) 상태 변경 이벤트. (스키마 v1)
 *
 * <p>risk/KillSwitch가 engage()/release() 될 때마다 발행한다. monitor 모듈이 이 이벤트를
 * 구독해 텔레그램 CRITICAL 알림으로 이어간다 — risk 모듈은 "누가 알림을 받는지" 전혀
 * 모른 채 상태 변화만 이벤트로 알리고, monitor가 알림 채널로 변환하는 책임을 진다
 * (ARCHITECTURE 9절: 상태 변화 통지는 Domain Event로).
 *
 * @param engaged   true면 방금 작동(비상 정지), false면 방금 해제
 * @param reason    작동 사유 또는 해제한 operator 식별자 — engage(reason)/release(operator)의
 *                  인자를 그대로 옮겨 담는다(필드 하나로 겸용, 값의 의미는 engaged로 구분)
 * @param timestamp 상태가 바뀐 시각
 */
public record KillSwitchChanged(
        boolean engaged,
        String reason,
        Instant timestamp
) {
}
