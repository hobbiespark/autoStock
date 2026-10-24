package com.autostock.monitor;

import com.autostock.common.event.Fill;
import com.autostock.common.event.KillSwitchChanged;
import com.autostock.common.event.MacroIndicatorStale;
import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.PositionMismatch;
import com.autostock.common.event.Side;
import com.autostock.common.event.StrategySellFilled;
import com.autostock.common.util.StockNames;
import com.autostock.portfolio.StrategyBook;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

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

    /**
     * 체결 요약 — INFO. 수량 × 단가 = 금액을 함께 보인다(2026-10-22 사용자 요청 "체결 X 단가로 총 금액 알 수 있게").
     * 전략 주문의 매도 체결은 여기서 보내지 않는다 — 전략 장부가 평단을 실어 {@link StrategySellFilled}로 다시 알리고,
     * {@link #onStrategySellFilled}가 수익률을 붙여 보낸다(2026-10-23 "매도시 수익율 포함해서 메시지 필요").
     */
    @EventListener
    public void onFill(Fill fill) {
        if (fill.side() == Side.SELL && StrategyBook.strategyIdOf(fill.orderIdempotencyKey()) != null) {
            return;
        }
        long qty = fill.filledQuantity().value();
        BigDecimal price = fill.fillPrice().value();
        dispatcher.submit(NoticeLevel.INFO,
                "체결: %s %s %d주 × %s원 = %s원".formatted(StockNames.label(fill.symbol()), sideText(fill.side()), qty,
                        won(price), won(price.multiply(BigDecimal.valueOf(qty)))));
    }

    /**
     * 전략 매도 체결 — INFO(2026-10-23). 체결 한 건마다 평단 대비 수익률·실현손익을 붙이고, 이 체결로 청산이 끝나면
     * "매도 완료" 요약(평균 매도가·수익률·비용 후 수익률·매도 금액·실현손익)을 한 통 더 보낸다. 평단을 모르면 수익률 없이 보낸다.
     * 수익률은 가격 기준(체결가 ÷ 평단 − 1), 비용 후 수익률은 실현손익 ÷ 매수 원가(수수료·거래세 추정 차감)다.
     */
    @EventListener
    public void onStrategySellFilled(StrategySellFilled event) {
        long qty = event.quantity();
        BigDecimal price = event.fillPrice();
        StringBuilder text = new StringBuilder("체결: %s 매도 %d주 × %s원 = %s원".formatted(StockNames.label(event.symbol()),
                qty, won(price), won(price.multiply(BigDecimal.valueOf(qty)))));
        BigDecimal avg = event.avgPrice();
        if (avg != null && avg.signum() > 0) {
            text.append(" · 평단 %s원 대비 %s".formatted(won(avg), pct(price.subtract(avg), avg)));
            if (event.realizedPnl() != null) {
                text.append(" · 실현 %s원(비용 추정 차감)".formatted(signedWon(event.realizedPnl())));
            }
        } else {
            text.append(" · 평단 모름(수익률 없음)");
        }
        text.append(" · ").append(event.strategyId());
        dispatcher.submit(NoticeLevel.INFO, text.toString());

        StrategySellFilled.Exit exit = event.exit();
        if (exit != null && exit.avgPrice() != null && exit.avgPrice().signum() > 0 && exit.quantity() > 0) {
            BigDecimal cost = exit.avgPrice().multiply(BigDecimal.valueOf(exit.quantity()));
            dispatcher.submit(NoticeLevel.INFO,
                    "매도 완료: %s %d주 — 평단 %s원 → 평균 매도가 %s원, 수익률 %s(비용 후 %s) · 매도 금액 %s원 · 실현손익 %s원 · %s · 체결 %d건"
                            .formatted(StockNames.label(event.symbol()), exit.quantity(), won(exit.avgPrice()),
                                    won(exit.avgSellPrice()), pct(exit.proceeds().subtract(cost), cost),
                                    pct(exit.realizedPnl(), cost), won(exit.proceeds().setScale(0, RoundingMode.HALF_UP)),
                                    signedWon(exit.realizedPnl()), event.strategyId(), exit.fills()));
        }
    }

    /** 부호 있는 백분율(소수 둘째 자리) — numerator ÷ denominator × 100. */
    static String pct(BigDecimal numerator, BigDecimal denominator) {
        BigDecimal value = numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 2, RoundingMode.HALF_UP);
        return (value.signum() > 0 ? "+" : "") + value.toPlainString() + "%";
    }

    /** 원 단위 반올림, 이익이면 + 부호. */
    static String signedWon(BigDecimal value) {
        BigDecimal rounded = value.setScale(0, RoundingMode.HALF_UP);
        return (rounded.signum() > 0 ? "+" : "") + won(rounded);
    }

    /** 주문 요청 발행 — INFO. RiskGate를 통과했다는 뜻(=실제로 브로커에 나갈 주문). 금액은 지정가 기준. */
    @EventListener
    public void onOrderRequest(OrderRequest order) {
        long qty = order.quantity().value();
        BigDecimal price = order.limitPrice().value();
        dispatcher.submit(NoticeLevel.INFO,
                "주문요청: %s %s %d주 × %s원 = %s원(지정가 기준) (%s)".formatted(StockNames.label(order.symbol()),
                        sideText(order.side()), qty, won(price), won(price.multiply(BigDecimal.valueOf(qty))),
                        order.idempotencyKey()));
    }

    private static String sideText(Side side) {
        return side == Side.BUY ? "매수" : "매도";
    }

    /** 원 단위 쉼표. 단가에 소수가 있으면(부분 체결 증분 단가) 소수 둘째 자리까지. */
    static String won(BigDecimal value) {
        if (value == null) {
            return "?";
        }
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() <= 0) {
            return "%,d".formatted(value.setScale(0, RoundingMode.HALF_UP).longValue());
        }
        return "%,.2f".formatted(value);
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
