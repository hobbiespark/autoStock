package com.autostock.risk;

import com.autostock.common.event.OrderSequenceRestored;
import com.autostock.common.util.MarketConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 일간 한도 추적기 — "오늘 주문을 몇 번 냈나"를 세는 카운터.
 *
 * <p>존재 이유: 전략 버그로 시그널이 폭주하면 주문이 수백 건 나갈 수 있다.
 * RiskGate의 다른 검사를 다 통과하더라도 하루 총량에는 상한이 있어야 한다.
 * 마지막 방어선. (일 손실 한도는 잔고 연동 후 Phase 2 후반 추가)
 *
 * <p>날짜 롤오버 주의점: "오늘"의 기준은 KST(한국시간)다. 서버가 UTC로 돌아도
 * 자정(KST)에 카운터가 리셋되도록 {@link MarketConstants#KST}를 명시했다
 * (여러 모듈이 각자 ZoneId를 선언하던 것을 공용 상수로 통합, PLAN ADR-5).
 * compareAndSet을 쓰는 이유: 자정 직후 여러 스레드가 동시에 리셋을 시도해도
 * 정확히 한 번만 리셋되게 하기 위해서다.
 */
@Component
public class DailyLimitTracker {

    private static final Logger log = LoggerFactory.getLogger(DailyLimitTracker.class);

    private final RiskProperties properties;
    private final AtomicReference<LocalDate> currentDay =
            new AtomicReference<>(LocalDate.now(MarketConstants.KST));
    private final AtomicInteger orderCount = new AtomicInteger(0);

    public DailyLimitTracker(RiskProperties properties) {
        this.properties = properties;
    }

    /** 주문 슬롯 획득 시도. 한도 초과면 false. */
    public boolean tryAcquireOrderSlot() {
        rollDayIfNeeded();
        return orderCount.incrementAndGet() <= properties.dailyMaxOrders();
    }

    public int todayOrderCount() {
        rollDayIfNeeded();
        return orderCount.get();
    }

    /**
     * 재시작 일련번호 복원 (운영 1일차 ① — trading.OrderSequenceRestorer가 기동 시 발행).
     * 카운터를 "당일 DB에 이미 존재하는 최대 일련번호" 이상으로 끌어올려, 재기동 후 첫
     * 주문이 기존 ClientOrderId와 UNIQUE 충돌하는 결함(2026-09-11 실측, -001~-003 3회
     * 스킵)을 막는다. 최댓값 방식이라 이벤트가 중복 수신돼도 안전하다(멱등).
     */
    @EventListener
    public void onOrderSequenceRestored(OrderSequenceRestored event) {
        rollDayIfNeeded();
        if (!event.day().equals(currentDay.get()) || event.lastSequence() <= 0) {
            return; // 날짜가 어긋난 복원(자정 직전 기동 등)은 무시 — 새 날은 0부터가 맞다
        }
        int restored = orderCount.accumulateAndGet(event.lastSequence(), Math::max);
        log.info("일 주문 카운터 복원: {} (일련번호·일 한도 겸용)", restored);
    }

    private void rollDayIfNeeded() {
        LocalDate today = LocalDate.now(MarketConstants.KST);
        LocalDate known = currentDay.get();
        if (!today.equals(known) && currentDay.compareAndSet(known, today)) {
            orderCount.set(0);
        }
    }
}
