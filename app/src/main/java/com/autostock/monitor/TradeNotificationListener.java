package com.autostock.monitor;

import com.autostock.common.event.Fill;
import com.autostock.common.event.KillSwitchChanged;
import com.autostock.common.event.OrderRequest;
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
 */
@Component
public class TradeNotificationListener {

    private final Notifier notifier;

    public TradeNotificationListener(Notifier notifier) {
        this.notifier = notifier;
    }

    /** 체결 요약 — INFO. */
    @EventListener
    public void onFill(Fill fill) {
        notifier.notify(NoticeLevel.INFO,
                "체결: %s %s %d주 @ %s".formatted(
                        fill.symbol(), fill.side(), fill.filledQuantity(), fill.fillPrice()));
    }

    /** 주문 요청 발행 — INFO. RiskGate를 통과했다는 뜻(=실제로 브로커에 나갈 주문). */
    @EventListener
    public void onOrderRequest(OrderRequest order) {
        notifier.notify(NoticeLevel.INFO,
                "주문요청: %s %s %d주 @ %s (%s)".formatted(
                        order.symbol(), order.side(), order.quantity(),
                        order.limitPrice(), order.idempotencyKey()));
    }

    /**
     * 킬스위치 상태 변화 — 작동(engaged=true)은 즉시 확인이 필요하므로 CRITICAL,
     * 해제는 정상적인 운영 재개 신호라 WARN(참고용)으로 구분한다.
     */
    @EventListener
    public void onKillSwitchChanged(KillSwitchChanged event) {
        if (event.engaged()) {
            notifier.notify(NoticeLevel.CRITICAL, "킬스위치 작동: " + event.reason());
        } else {
            notifier.notify(NoticeLevel.WARN, "킬스위치 해제: " + event.reason());
        }
    }
}
