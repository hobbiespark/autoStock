package com.autostock.monitor;

import com.autostock.common.event.FxAlert;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** FxAlertListener — {@link FxAlert}를 Notifier로 그대로 잇는지(VALUNIVO 4-6). {@code IpoAlertListenerTest}와 같은 구조. */
class FxAlertListenerTest {

    private final Notifier notifier = mock(Notifier.class);
    private final FxAlertListener listener = new FxAlertListener(notifier);

    @Test
    void 외화_알림을_받으면_INFO로_전달한다() {
        listener.onFxAlert(new FxAlert("REVIEW", "USD", "[외화 검토] USD 직접매수 검토 조건에 닿았습니다", Instant.now()));

        verify(notifier).notify(eq(NoticeLevel.INFO), eq("[외화 검토] USD 직접매수 검토 조건에 닿았습니다"));
    }
}
