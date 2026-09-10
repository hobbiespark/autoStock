package com.autostock.monitor;

import com.autostock.common.event.DisclosureBlacklisted;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 공시 블랙리스트 신규 등록({@link DisclosureBlacklisted})을 Notifier(텔레그램)로 이어주는
 * 브리지 (PLAN.md ADR-14, 트랙 G1) — {@link IpoAlertListener}와 동일 패턴. risk 모듈은 발송
 * 수단을 전혀 모르고 이벤트만 발행하며, 이 리스너가 실제 채널로 이어준다.
 */
@Component
public class DisclosureBlacklistListener {

    private final Notifier notifier;

    public DisclosureBlacklistListener(Notifier notifier) {
        this.notifier = notifier;
    }

    /** 매수 금지 신규 등록 — WARN(킬스위치만큼 긴급하진 않지만 반드시 인지해야 하는 상태 변화). */
    @EventListener
    public void onDisclosureBlacklisted(DisclosureBlacklisted event) {
        notifier.notify(NoticeLevel.WARN,
                "매수 금지 등록: %s(%s) — %s(rcept_no=%s), 해제예정 %s".formatted(
                        event.corpName(), event.symbol(), event.disclosureType(),
                        event.rceptNo(), event.expiresOn()));
    }
}
