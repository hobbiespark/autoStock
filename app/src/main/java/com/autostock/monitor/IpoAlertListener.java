package com.autostock.monitor;

import com.autostock.common.event.IpoAlert;
import com.autostock.common.event.IpoLiveSellRecord;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 공모주 알림({@link IpoAlert})을 Notifier(텔레그램)로 이어주는 브리지 (PLAN.md ADR-9 ③,
 * 트랙 E2) — {@link TradeNotificationListener}와 동일한 패턴. ipo 모듈은 발송 수단을 전혀
 * 모르고 이벤트만 발행하며, 이 리스너가 실제 채널로 이어준다.
 */
@Component
public class IpoAlertListener {

    private final Notifier notifier;

    public IpoAlertListener(Notifier notifier) {
        this.notifier = notifier;
    }

    /** 청약 D-1/시작 알림 — INFO(운영자가 직접 청약 여부를 판단해야 하므로 매매 이벤트와 동급). */
    @EventListener
    public void onIpoAlert(IpoAlert event) {
        notifier.notify(NoticeLevel.INFO, event.message());
    }

    /**
     * 공모주 상장일 실거래 매도 기록(2026-11-05, aiDoc/ipo-automation.md 8절) — 계획·확인·주문·체결은 INFO, 거부·결과 불명·중단은 WARN.
     */
    @EventListener
    public void onIpoLiveSell(IpoLiveSellRecord event) {
        notifier.notify(event.urgent() ? NoticeLevel.WARN : NoticeLevel.INFO, event.message());
    }
}
