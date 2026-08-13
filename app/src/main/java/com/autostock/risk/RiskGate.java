package com.autostock.risk;

import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.Side;
import com.autostock.common.event.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Signal → (킬스위치 · 한도 · 사이징) → OrderRequest. 주문의 유일한 관문.
 * 통과 순서: 킬스위치 → 일 주문 한도 → 포지션 규칙 → 사이징.
 */
@Component
public class RiskGate {

    private static final Logger log = LoggerFactory.getLogger(RiskGate.class);

    private final ApplicationEventPublisher publisher;
    private final KillSwitch killSwitch;
    private final RiskProperties properties;
    private final PositionSizer sizer;
    private final PositionBook positionBook;
    private final DailyLimitTracker dailyLimits;

    public RiskGate(ApplicationEventPublisher publisher,
                    KillSwitch killSwitch,
                    RiskProperties properties,
                    PositionSizer sizer,
                    PositionBook positionBook,
                    DailyLimitTracker dailyLimits) {
        this.publisher = publisher;
        this.killSwitch = killSwitch;
        this.properties = properties;
        this.sizer = sizer;
        this.positionBook = positionBook;
        this.dailyLimits = dailyLimits;
    }

    @EventListener
    public void onSignal(Signal signal) {
        if (killSwitch.isEngaged()) {
            log.warn("킬스위치 작동 중 — 시그널 거부: {}", signal.symbol());
            return;
        }
        long quantity = switch (signal.side()) {
            case BUY -> sizeBuy(signal);
            case SELL -> sizeSell(signal);
        };
        if (quantity <= 0) {
            return;
        }
        if (!dailyLimits.tryAcquireOrderSlot()) {
            log.warn("일 주문 한도 초과 — 거부: {}", signal.symbol());
            return;
        }
        publisher.publishEvent(new OrderRequest(
                UUID.randomUUID().toString(),
                signal.strategyId(),
                signal.symbol(),
                signal.side(),
                quantity,
                signal.refPrice(),
                Instant.now()));
    }

    private long sizeBuy(Signal signal) {
        if (positionBook.holds(signal.symbol())) {
            log.info("이미 보유 중 — 추가 매수 차단: {}", signal.symbol());
            return 0;
        }
        if (positionBook.openPositionCount() >= properties.maxConcurrentPositions()) {
            log.info("동시 보유 한도 도달({}) — 매수 거부: {}",
                    properties.maxConcurrentPositions(), signal.symbol());
            return 0;
        }
        // TODO Phase 2 후반: live 모드에서는 브로커 잔고 이벤트로 equity 갱신
        BigDecimal equity = BigDecimal.valueOf(properties.paperEquity());
        long qty = sizer.sizeBuy(equity, signal.refPrice());
        if (qty <= 0) {
            log.info("사이징 결과 0주 — 매수 불가: {} (equity={}, price={})",
                    signal.symbol(), equity, signal.refPrice());
        }
        return qty;
    }

    private long sizeSell(Signal signal) {
        PositionBook.Position position = positionBook.get(signal.symbol());
        if (position == null) {
            log.info("미보유 종목 매도 시그널 무시: {}", signal.symbol());
            return 0;
        }
        return position.quantity(); // 전량 청산
    }
}
