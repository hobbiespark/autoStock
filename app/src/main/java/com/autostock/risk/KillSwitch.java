package com.autostock.risk;

import com.autostock.common.event.KillSwitchChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 킬스위치 — 자동매매의 비상 정지 버튼. 활성화되면 모든 신규 주문이 차단된다.
 *
 * <p>규제 흐름상으로도(2025~2026) "작동하는 킬스위치"는 자동매매의 필수 인프라다. (PLAN 2절 (4))
 *
 * <p>트리거 경로 (누가 이 스위치를 누르나):
 * <ul>
 *   <li>사람: 텔레그램 원격 명령 (monitor 모듈, Phase 4)</li>
 *   <li>자동: 일 손실 한도 도달, WS 장시간 단절, 지정학 이벤트 가드 (PLAN 8절)</li>
 * </ul>
 *
 * <p>구현 노트: AtomicBoolean + compareAndSet으로 "이미 켜져 있는데 또 켜는" 중복
 * 로그를 막는다. 해제(release)는 반드시 사람(operator)의 명시적 행동으로만 —
 * 자동 해제는 의도적으로 만들지 않았다. 비상 정지가 저절로 풀리면 안 되기 때문.
 *
 * <p>Phase 4: 상태가 실제로 바뀔 때(compareAndSet 성공 시)만 {@link KillSwitchChanged}
 * 이벤트를 발행한다 — monitor 모듈이 이 이벤트를 구독해 텔레그램 CRITICAL 알림을 보낸다.
 * risk 모듈은 monitor의 존재를 전혀 모른다(이벤트만 던질 뿐) — 모듈 경계 원칙 준수.
 */
@Component
public class KillSwitch {

    private static final Logger log = LoggerFactory.getLogger(KillSwitch.class);

    private final ApplicationEventPublisher publisher;
    private final AtomicBoolean engaged = new AtomicBoolean(false);

    public KillSwitch(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public boolean isEngaged() {
        return engaged.get();
    }

    public void engage(String reason) {
        if (engaged.compareAndSet(false, true)) {
            log.warn("킬스위치 작동: {}", reason);
            publisher.publishEvent(new KillSwitchChanged(true, reason, Instant.now()));
        }
    }

    public void release(String operator) {
        if (engaged.compareAndSet(true, false)) {
            log.warn("킬스위치 해제 by {}", operator);
            publisher.publishEvent(new KillSwitchChanged(false, operator, Instant.now()));
        }
    }
}
