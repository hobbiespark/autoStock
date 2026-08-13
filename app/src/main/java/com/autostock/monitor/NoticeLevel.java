package com.autostock.monitor;

/**
 * 알림 심각도. Notifier 구현체(텔레그램 등)가 표시 방식(이모지·강조 등)을 결정할 때 참고한다.
 */
public enum NoticeLevel {
    /** 정보성 — 체결·주문요청 등 정상 흐름. */
    INFO,
    /** 주의 — 킬스위치 해제 등 사람이 인지는 해야 하지만 긴급하지는 않은 상태 변화. */
    WARN,
    /** 긴급 — 킬스위치 작동 등 즉시 확인이 필요한 상태. */
    CRITICAL
}
