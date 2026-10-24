package com.autostock.monitor;

import com.autostock.common.event.Fill;
import com.autostock.common.event.KillSwitchChanged;
import com.autostock.common.event.MacroIndicatorStale;
import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.PositionMismatch;
import com.autostock.common.event.Side;
import com.autostock.common.event.StrategySellFilled;
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
        assertEquals("체결: 삼성전자(005930) 매수 10주 × 70,000원 = 700,000원", notices.get(0).message());
    }

    @Test
    void 부분_체결_증분_단가가_소수면_둘째_자리까지_쓴다() {
        assertEquals("220,500.89", TradeNotificationListener.won(new BigDecimal("220500.8889")));
        assertEquals("1,271,000", TradeNotificationListener.won(new BigDecimal("1271000.0000")));
    }

    @Test
    void OrderRequest는_INFO로_발송되고_이름을_모르면_미확인으로_표시한다() {
        listener.onOrderRequest(new OrderRequest("20260813-TEST-005930-BUY-001", "test-strategy",
                new StockCode("005930"), Side.BUY, new Quantity(14), new Price(new BigDecimal("70000")), Instant.now(), null));

        assertEquals(1, sent().size());
        assertEquals(NoticeLevel.INFO, notices.get(0).level());
        assertEquals("주문요청: 종목명 미확인(005930) 매수 14주 × 70,000원 = 980,000원(지정가 기준) (20260813-TEST-005930-BUY-001)",
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

    @Test
    void 거시_지표_오래됨은_WARN으로_발송된다() {
        listener.onMacroIndicatorStale(new MacroIndicatorStale(List.of("FRED_VIX", "ECOS_USDKRW"), 9, 7, Instant.now()));

        assertEquals(1, sent().size());
        assertEquals(NoticeLevel.WARN, notices.get(0).level());
        assertEquals("거시 지표 오래됨: FRED_VIX, ECOS_USDKRW — 가장 오래된 것 9일(기준 7일). 수집(FRED·ECOS) 확인 필요"
                + " — 그동안 신규 매수 금지", notices.get(0).message());
    }
    @Test
    void 잔고_장부_불일치는_WARN으로_발송된다() {
        StockNames.learn("005930", "삼성전자", StockNames.Source.KIWOOM);

        listener.onPositionMismatch(new PositionMismatch(new StockCode("005930"), 19, 0, Instant.now()));

        assertEquals(1, sent().size());
        assertEquals(NoticeLevel.WARN, notices.get(0).level());
        assertEquals("잔고·장부 불일치: 삼성전자(005930) 브로커 19주 / 장부 0주 — 10분 넘게 같음. 자동 교정 안 함,"
                + " HTS 잔고와 대시보드 확인 필요", notices.get(0).message());
    }

    // ── 매도 알림의 수익률(2026-10-23 "매도시 수익율 포함해서 메시지 필요") ──────────────────

    private static final StockCode FINE = new StockCode("441270");

    @Test
    void 전략_주문의_매도_체결은_Fill_알림을_건너뛴다_전략_장부가_평단을_실어_다시_알린다() {
        listener.onFill(new Fill("20261023-ON-CLOSE-441270-SELL-001", new BrokerOrderId("0006554"), FINE, Side.SELL,
                new Quantity(2), new Price(new BigDecimal("9800")), Instant.now()));
        listener.onFill(new Fill("20261023-ON-CLOSE-441270-BUY-001", new BrokerOrderId("0006555"), FINE, Side.BUY,
                new Quantity(2), new Price(new BigDecimal("9800")), Instant.now()));

        assertEquals(1, sent().size(), "매수 체결은 그대로 알린다");
        assertEquals(true, notices.get(0).message().contains("매수 2주"));
    }

    @Test
    void 전략_매도_체결은_평단_대비_수익률과_실현손익을_싣는다() {
        StockNames.learn("441270", "파인엠텍", StockNames.Source.KIWOOM);

        listener.onStrategySellFilled(new StrategySellFilled("ON-CLOSE", FINE, 10, new BigDecimal("10250"),
                new BigDecimal("10000.0000"), new BigDecimal("2449.25"), 83, null, Instant.now()));

        assertEquals(1, sent().size());
        assertEquals(NoticeLevel.INFO, notices.get(0).level());
        assertEquals("체결: 파인엠텍(441270) 매도 10주 × 10,250원 = 102,500원 · 평단 10,000원 대비 +2.50%"
                + " · 실현 +2,449원(비용 추정 차감) · ON-CLOSE", notices.get(0).message());
    }

    @Test
    void 청산이_끝나면_매도_완료_요약을_한_통_더_보낸다() {
        StockNames.learn("441270", "파인엠텍", StockNames.Source.KIWOOM);
        // 10/23 09:00 ON-CLOSE 파인엠텍 93주 청산(실제 값): 평단 10,110 → 평균 9,786, 매도 금액 910,098, 실현 -32,229.80
        StrategySellFilled.Exit exit = new StrategySellFilled.Exit(93, new BigDecimal("10110.0000"), new BigDecimal("9786"),
                new BigDecimal("910098.0000"), new BigDecimal("-32229.80"), 10);

        listener.onStrategySellFilled(new StrategySellFilled("ON-CLOSE", FINE, 2, new BigDecimal("9831.5000"),
                new BigDecimal("10110.0000"), new BigDecimal("-580.17"), 0, exit, Instant.now()));

        assertEquals(2, sent().size());
        assertEquals("체결: 파인엠텍(441270) 매도 2주 × 9,831.50원 = 19,663원 · 평단 10,110원 대비 -2.75%"
                + " · 실현 -580원(비용 추정 차감) · ON-CLOSE", notices.get(0).message());
        assertEquals("매도 완료: 파인엠텍(441270) 93주 — 평단 10,110원 → 평균 매도가 9,786원, 수익률 -3.20%(비용 후 -3.43%)"
                + " · 매도 금액 910,098원 · 실현손익 -32,230원 · ON-CLOSE · 체결 10건", notices.get(1).message());
    }

    @Test
    void 평단을_모르면_수익률_없이_보낸다() {
        StockNames.learn("441270", "파인엠텍", StockNames.Source.KIWOOM);

        listener.onStrategySellFilled(new StrategySellFilled("VB-DAY", FINE, 3, new BigDecimal("10000"),
                null, null, 0, null, Instant.now()));

        assertEquals(1, sent().size());
        assertEquals("체결: 파인엠텍(441270) 매도 3주 × 10,000원 = 30,000원 · 평단 모름(수익률 없음) · VB-DAY",
                notices.get(0).message());
    }
}
