package com.autostock.risk;

import com.autostock.common.util.MarketConstants;
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

    private void rollDayIfNeeded() {
        LocalDate today = LocalDate.now(MarketConstants.KST);
        LocalDate known = currentDay.get();
        if (!today.equals(known) && currentDay.compareAndSet(known, today)) {
            orderCount.set(0);
        }
    }
}
