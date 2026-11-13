package com.autostock.fx;

import com.autostock.common.event.FxAlert;
import com.autostock.common.util.MarketConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 월초 안내(1일 09:10)와 월말 정산(말일 18:10) — 설계 2·7장(D-28). 달마다 한 번(`fx_alert_log` MONTH_START·MONTH_END, 기준값 YYYY-MM).
 * 월초 안내는 AI 제안이고, 매수를 권하지 않는다.
 */
@Component
public class FxMonthScheduler {

    private static final Logger log = LoggerFactory.getLogger(FxMonthScheduler.class);

    static final String MONTH_START_TEXT = "이번 달 외화 예약을 신청했으면 /autostock_fx_reserve 로 입력해 주세요(통화 예약환율 금액 신청당시환율).";

    private final FxAccumulationProperties properties;
    private final FxCommandService commands;
    private final FxAlertLogRepository alertLog;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    public FxMonthScheduler(FxAccumulationProperties properties, FxCommandService commands, FxAlertLogRepository alertLog,
                            ApplicationEventPublisher publisher, Clock clock) {
        this.properties = properties;
        this.commands = commands;
        this.alertLog = alertLog;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Scheduled(cron = "0 10 9 1 * *", zone = "Asia/Seoul")
    public void scheduledMonthStart() {
        if (properties.activeOn(LocalDate.now(clock.withZone(MarketConstants.KST)))) {
            guarded(this::monthStart, "월초 안내");
        }
    }

    /** 말일(L) 18:10. 시작일 전 달의 정산은 보내지 않는다. */
    @Scheduled(cron = "0 10 18 L * *", zone = "Asia/Seoul")
    public void scheduledMonthEnd() {
        if (properties.activeOn(LocalDate.now(clock.withZone(MarketConstants.KST)))) {
            guarded(this::monthEnd, "월말 정산");
        }
    }

    private void guarded(Runnable task, String name) {
        try {
            task.run();
        } catch (RuntimeException e) {
            log.error("외화 {} 알림 실패", name, e);
        }
    }

    @Transactional
    public void monthStart() {
        YearMonth month = YearMonth.from(LocalDate.now(clock.withZone(MarketConstants.KST)));
        send(FxAlertLogEntity.KIND_MONTH_START, month,
                commands.summary(month, "[외화 월초] " + month + "\n" + MONTH_START_TEXT));
    }

    @Transactional
    public void monthEnd() {
        YearMonth month = YearMonth.from(LocalDate.now(clock.withZone(MarketConstants.KST)));
        send(FxAlertLogEntity.KIND_MONTH_END, month, commands.summary(month, "[외화 월말 정산] " + month));
    }

    private void send(String kind, YearMonth month, String message) {
        String key = FxAlertLogEntity.monthKey(month);
        if (alertLog.existsByKindAndCurrencyAndAlertKey(kind, FxAlertLogEntity.ALL_CURRENCIES, key)) {
            return;
        }
        alertLog.saveAndFlush(new FxAlertLogEntity(kind, FxAlertLogEntity.ALL_CURRENCIES, key, clock.instant()));
        publisher.publishEvent(new FxAlert(kind, FxAlertLogEntity.ALL_CURRENCIES, message, clock.instant()));
    }
}
