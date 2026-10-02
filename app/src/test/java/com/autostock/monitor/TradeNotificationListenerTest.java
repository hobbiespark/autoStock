package com.autostock.monitor;

import com.autostock.common.event.Fill;
import com.autostock.common.event.KillSwitchChanged;
import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.Side;
import com.autostock.common.util.StockCode;
import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.Price;
import com.autostock.common.util.Quantity;
import com.autostock.common.util.StockNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * TradeNotificationListener — 어떤 이벤트가 어떤 NoticeLevel로 이어지는지 검증한다.
 * Notifier는 실제 구현(TelegramNotifier) 대신 호출을 기록하는 가짜(fake)로 대체한다.
 */
class TradeNotificationListenerTest {

    private record Notice(NoticeLevel level, String message) {
    }

    private final List<Notice> notices = new CopyOnWriteArrayList<>();
    private final Notifier fakeNotifier = (level, message) -> notices.add(new Notice(level, message));
    private NotificationDispatcher dispatcher;
    private TradeNotificationListener listener;

    @BeforeEach
    void setUp() {
        dispatcher = new NotificationDispatcher(fakeNotifier);
        listener = new TradeNotificationListener(dispatcher);
    }

    /** 비동기 발송(실행 계획 1.3) — 대기열을 비운 뒤 확인한다. */
    private List<Notice> sent() {
        dispatcher.drain();
        return notices;
    }

    @Test
    void Fill은_INFO로_발송되고_종목명과_코드를_함께_싣는다() {
        StockNames.learn("005930", "삼성전자", StockNames.Source.KIWOOM);

        listener.onFill(new Fill("k1", new BrokerOrderId("b1"), new StockCode("005930"), Side.BUY, new Quantity(10),
                new Price(new BigDecimal("70000")), Instant.now()));

        assertEquals(1, sent().size());
        assertEquals(NoticeLevel.INFO, notices.get(0).level());
        assertEquals("체결: 삼성전자(005930) BUY 10주 @ 70000", notices.get(0).message());
    }

    @Test
    void OrderRequest는_INFO로_발송되고_이름을_모르면_미확인으로_표시한다() {
        listener.onOrderRequest(new OrderRequest("20260813-TEST-005930-BUY-001", "test-strategy",
                new StockCode("005930"), Side.BUY, new Quantity(14), new Price(new BigDecimal("70000")), Instant.now()));

        assertEquals(1, sent().size());
        assertEquals(NoticeLevel.INFO, notices.get(0).level());
        assertEquals("주문요청: 종목명 미확인(005930) BUY 14주 @ 70000 (20260813-TEST-005930-BUY-001)",
                notices.get(0).message());
    }

    @Test
    void 킬스위치_작동은_CRITICAL로_발송된다() {
        listener.onKillSwitchChanged(new KillSwitchChanged(true, "일 손실 한도 도달", Instant.now()));

        assertEquals(1, sent().size());
        assertEquals(NoticeLevel.CRITICAL, notices.get(0).level());
        assertEquals(true, notices.get(0).message().contains("일 손실 한도 도달"));
    }

    @Test
    void 킬스위치_해제는_WARN으로_발송된다() {
        listener.onKillSwitchChanged(new KillSwitchChanged(false, "operator-1", Instant.now()));

        assertEquals(1, sent().size());
        assertEquals(NoticeLevel.WARN, notices.get(0).level());
    }
}
