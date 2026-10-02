package com.autostock.monitor;

import com.autostock.common.event.Fill;
import com.autostock.common.event.KillSwitchChanged;
import com.autostock.common.event.MacroIndicatorStale;
import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.PositionMismatch;
import com.autostock.common.util.StockNames;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 매매 이벤트를 텔레그램(Notifier)로 이어주는 브리지 — EventFeed(대시보드용 전체 이력)와
 * 달리 "사람에게 즉시 알려야 하는" 이벤트만 선별해서 다룬다.
 *
 * <p>기존 FillLogger(체결 시 로그만 남기던 골격, Javadoc에 "Phase 4에서 텔레그램 발송으로
 * 교체" 예고돼 있었다)를 이 클래스가 대체한다 — Notifier가 no-op(LogOnlyNotifier)일 때는
 * 결과적으로 이전과 동일하게 로그만 남고, 텔레그램이 켜지면 그대로 알림이 나간다.
 *
 * <p>MarketTick은 의도적으로 구독하지 않는다 — 초당 수십 건이라 알림을 폭주시킨다
 * (EventFeed와 같은 이유, monitor/EventFeed Javadoc 참고).
 *
 * <p><b>비동기 발송(실행 계획 1.3, 2026-10-02)</b>: 알림은 {@link NotificationDispatcher} 대기열에 넣고 바로 돌아온다 —
 * 체결(WS 수신 스레드)·주문 요청(주문 경로)을 텔레그램 왕복으로 막지 않는다. 이 리스너의 알림끼리는 순서가 지켜지지만,
 * 다른 리스너의 로그·처리와의 상대 순서는 바뀔 수 있다(예: 체결 알림이 장부 반영 로그보다 늦게 도착).
 * 순서가 중요한 리스너(PositionBook·DailyPnlTracker·SlippageTracker·OrderNoticeHandler)는 그대로 동기다.
 */
@Component
public class TradeNotificationListener {

    private final NotificationDispatcher dispatcher;

    public TradeNotificationListener(NotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /** 체결 요약 — INFO. */
    @EventListener
    public void onFill(Fill fill) {
        dispatcher.submit(NoticeLevel.INFO,
                "체결: %s %s %d주 @ %s".formatted(
                        StockNames.label(fill.symbol()), fill.side(), fill.filledQuantity().value(), fill.fillPrice()));
    }

    /** 주문 요청 발행 — INFO. RiskGate를 통과했다는 뜻(=실제로 브로커에 나갈 주문). */
    @EventListener
    public void onOrderRequest(OrderRequest order) {
        dispatcher.submit(NoticeLevel.INFO,
                "주문요청: %s %s %d주 @ %s (%s)".formatted(
                        StockNames.label(order.symbol()), order.side(), order.quantity().value(),
                        order.limitPrice(), order.idempotencyKey()));
    }

    /**
     * 킬스위치 상태 변화 — 작동(engaged=true)은 즉시 확인이 필요하므로 CRITICAL,
     * 해제는 정상적인 운영 재개 신호라 WARN(참고용)으로 구분한다.
     */
    @EventListener
    public void onKillSwitchChanged(KillSwitchChanged event) {
        if (event.engaged()) {
            dispatcher.submit(NoticeLevel.CRITICAL, "킬스위치 작동: " + event.reason());
        } else {
            dispatcher.submit(NoticeLevel.WARN, "킬스위치 해제: " + event.reason());
        }
    }

    /**
     * 거시 지표 오래됨(실행 계획 1.4) — WARN. risk.MacroGuard가 하루 한 번 발행한다. 그동안 신규 매수는 막혀 있다.
     */
    @EventListener
    public void onMacroIndicatorStale(MacroIndicatorStale event) {
        dispatcher.submit(NoticeLevel.WARN,
                "거시 지표 오래됨: %s — 가장 오래된 것 %d일(기준 %d일). 수집(FRED·ECOS) 확인 필요 — 그동안 신규 매수 금지".formatted(
                        String.join(", ", event.indicatorIds()), event.ageDays(), event.maxStalenessDays()));
    }
    /**
     * 잔고·장부 수량 불일치(실행 계획 1.5) — WARN. trading.PositionReconciler가 같은 불일치를 연속 2회 보면 한 번 발행한다.
     */
    @EventListener
    public void onPositionMismatch(PositionMismatch event) {
        dispatcher.submit(NoticeLevel.WARN,
                "잔고·장부 불일치: %s 브로커 %d주 / 장부 %d주 — 10분 넘게 같음. 자동 교정 안 함, HTS 잔고와 대시보드 확인 필요".formatted(
                        StockNames.label(event.symbol()), event.brokerQuantity(), event.bookQuantity()));
    }
}
