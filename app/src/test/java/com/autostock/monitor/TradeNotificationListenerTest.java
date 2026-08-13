package com.autostock.monitor;

import com.autostock.common.event.Fill;
import com.autostock.common.event.KillSwitchChanged;
import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * TradeNotificationListener — 어떤 이벤트가 어떤 NoticeLevel로 이어지는지 검증한다.
 * Notifier는 실제 구현(TelegramNotifier) 대신 호출을 기록하는 가짜(fake)로 대체한다.
 */
class TradeNotificationListenerTest {

    private record Notice(NoticeLevel level, String message) {
    }

    private final List<Notice> notices = new ArrayList<>();
    private final Notifier fakeNotifier = (level, message) -> notices.add(new Notice(level, message));
    private TradeNotificationListener listener;

    @BeforeEach
    void setUp() {
        listener = new TradeNotificationListener(fakeNotifier);
    }

    @Test
    void Fill은_INFO로_발송된다() {
        listener.onFill(new Fill("k1", "b1", "005930", Side.BUY, 10,
                new BigDecimal("70000"), Instant.now()));

        assertEquals(1, notices.size());
        assertEquals(NoticeLevel.INFO, notices.get(0).level());
        assertEquals(true, notices.get(0).message().contains("005930"));
    }

    @Test
    void OrderRequest는_INFO로_발송된다() {
        listener.onOrderRequest(new OrderRequest("20260813-TEST-005930-BUY-001", "test-strategy",
                "005930", Side.BUY, 14, new BigDecimal("70000"), Instant.now()));

        assertEquals(1, notices.size());
        assertEquals(NoticeLevel.INFO, notices.get(0).level());
    }

    @Test
    void 킬스위치_작동은_CRITICAL로_발송된다() {
        listener.onKillSwitchChanged(new KillSwitchChanged(true, "일 손실 한도 도달", Instant.now()));

        assertEquals(1, notices.size());
        assertEquals(NoticeLevel.CRITICAL, notices.get(0).level());
        assertEquals(true, notices.get(0).message().contains("일 손실 한도 도달"));
    }

    @Test
    void 킬스위치_해제는_WARN으로_발송된다() {
        listener.onKillSwitchChanged(new KillSwitchChanged(false, "operator-1", Instant.now()));

        assertEquals(1, notices.size());
        assertEquals(NoticeLevel.WARN, notices.get(0).level());
    }
}
