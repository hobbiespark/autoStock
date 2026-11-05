package com.autostock.monitor;

import com.autostock.common.event.IpoAlert;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * IpoAlertListener — {@link IpoAlert}를 Notifier(텔레그램)로 그대로 이어주는지(PLAN.md
 * ADR-9 ③) 검증한다. {@code TradeNotificationListener}와 동일한 브리지 패턴이므로 테스트
 * 구조도 그에 맞춘다.
 */
class IpoAlertListenerTest {

    private final Notifier notifier = mock(Notifier.class);
    private final IpoAlertListener listener = new IpoAlertListener(notifier);

    @Test
    void 공모주_알림을_받으면_INFO로_전달한다() {
        IpoAlert event = new IpoAlert("한울반도체", "D-1", "[청약 D-1] 한울반도체 — 공모가 5030원", Instant.now());

        listener.onIpoAlert(event);

        verify(notifier).notify(eq(NoticeLevel.INFO), eq("[청약 D-1] 한울반도체 — 공모가 5030원"));
    }

    @Test
    void 실거래_매도_기록은_급하면_WARN_아니면_INFO() {
        listener.onIpoLiveSell(new com.autostock.common.event.IpoLiveSellRecord("브릴스", "123450", "ORDER", "FIRST", 5,
                null, null, "UNKNOWN", "결과 불명", true, Instant.now()));
        listener.onIpoLiveSell(new com.autostock.common.event.IpoLiveSellRecord("브릴스", "123450", "ORDER", "FIRST", 5,
                null, "0101", "ACCEPTED", "접수", false, Instant.now()));
        verify(notifier).notify(eq(NoticeLevel.WARN), eq("결과 불명"));
        verify(notifier).notify(eq(NoticeLevel.INFO), eq("접수"));
    }
}
