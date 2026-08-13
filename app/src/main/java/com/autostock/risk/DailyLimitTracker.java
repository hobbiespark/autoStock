package com.autostock.risk;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 일간 한도 추적: 주문 횟수. (일 손실 한도는 잔고 연동 후 Phase 2 후반 추가)
 */
@Component
public class DailyLimitTracker {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final RiskProperties properties;
    private final AtomicReference<LocalDate> currentDay = new AtomicReference<>(LocalDate.now(KST));
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
        LocalDate today = LocalDate.now(KST);
        LocalDate known = currentDay.get();
        if (!today.equals(known) && currentDay.compareAndSet(known, today)) {
            orderCount.set(0);
        }
    }
}
