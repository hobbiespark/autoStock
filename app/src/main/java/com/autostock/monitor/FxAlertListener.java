package com.autostock.monitor;

import com.autostock.common.event.FxAlert;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 외화 적립 알림({@link FxAlert})을 Notifier(텔레그램)로 이어주는 브리지(VALUNIVO 4-6) — {@link IpoAlertListener}와 같은 방식.
 * fx 모듈은 발송 수단을 모르고 이벤트만 발행한다.
 */
@Component
public class FxAlertListener {

    private final Notifier notifier;

    public FxAlertListener(Notifier notifier) {
        this.notifier = notifier;
    }

    /** 데브가 은행 앱에서 직접 판단할 검토 신호라 INFO(공모주 알림과 같은 급). */
    @EventListener
    public void onFxAlert(FxAlert event) {
        notifier.notify(NoticeLevel.INFO, event.message());
    }
}
