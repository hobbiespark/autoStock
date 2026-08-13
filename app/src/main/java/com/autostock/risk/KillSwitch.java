package com.autostock.risk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 킬스위치. 활성화 시 모든 신규 주문 차단.
 * 트리거: 텔레그램 원격 명령(monitor), 일 손실 한도, WS 단절, 이벤트 가드.
 */
@Component
public class KillSwitch {

    private static final Logger log = LoggerFactory.getLogger(KillSwitch.class);

    private final AtomicBoolean engaged = new AtomicBoolean(false);

    public boolean isEngaged() {
        return engaged.get();
    }

    public void engage(String reason) {
        if (engaged.compareAndSet(false, true)) {
            log.warn("킬스위치 작동: {}", reason);
        }
    }

    public void release(String operator) {
        if (engaged.compareAndSet(true, false)) {
            log.warn("킬스위치 해제 by {}", operator);
        }
    }
}
