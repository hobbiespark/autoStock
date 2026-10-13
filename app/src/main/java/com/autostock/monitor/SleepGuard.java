package com.autostock.monitor;

import com.autostock.common.util.MarketConstants;
import com.autostock.market.MarketSessionService;
import com.autostock.monitor.PowerSource.PowerStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PC 절전 대응 — 장 대응 시간(ACTIVE)에는 Windows 유휴 절전을 막고, 절전에서 깨어나면 멈춰 있던 구간을 알린다.
 *
 * <p>배경(2026-09-30 로그): 10:08 WS가 끊긴 뒤 17:51까지 로그가 한 줄도 없었고, 16:00 대기 진입·15:45 분봉
 * 적재·15:50 일일 리포트가 17:51에 한꺼번에 실행됐다 — 앱이 켜진 채 PC가 절전에 들어가 장중 7시간 넘게 시세·
 * 체결통보 없이 멈춰 있었다. 9/19~22, 9/24에도 같은 일이 있었다(docs/worklog_20260923.md).
 *
 * <ul>
 *   <li><b>절전 방지</b>: ACTIVE 동안 {@link SystemSleepBlocker}로 유휴 절전을 막고 STANDBY가 되면 푼다.
 *       거래일에는 {@code autostock.session.keep-awake-from}(기본 07:30)부터 미리 막는다 — 장전 실측과 08:30 장 대응
 *       시작 전에 PC가 다시 잠들지 않게(2026-10-08: 밤사이 네 번 절전, 07:52에 사람이 깨움).
 *       밤·주말에는 평소대로 절전된다. {@code autostock.session.keep-awake=false}면 끈다.</li>
 *   <li><b>복귀 감지</b>: 30초 틱 사이 간격이 {@link #RESUME_GAP} 이상이면 절전(또는 프로세스 정지)에서
 *       돌아온 것으로 본다. 멈춘 구간이 장 대응 시간에 걸쳤으면 WARN 알림, 아니면 INFO 로그만 남긴다.</li>
 *   <li><b>배터리 감시</b>(2026-10-12): 운영 PC는 노트북이다. 절전을 막는 시간(장 대응 시간)에 충전기가 빠진 채
 *       {@link #DC_CONFIRM_TICKS}틱 연속이면 WARN, 잔량이 {@link #LOW_BATTERY_PERCENT}% 이하면 CRITICAL을 한 번씩 보낸다.
 *       알린 뒤 충전기가 다시 꽂히면 INFO로 알린다. 1~6초짜리 순간 끊김(10/8 실측)은 연속 확인으로 거른다.
 *       10/12에는 배터리로 장을 맞아 화면 꺼짐(배터리 60분)·절전(배터리 120분)으로 앱이 멈췄다 — aiDoc/sleep-resume.md 5절.</li>
 * </ul>
 */
@Component
public class SleepGuard {

    private static final Logger log = LoggerFactory.getLogger(SleepGuard.class);

    /** 틱 주기(30초)의 여섯 배 — GC·부하로 늦어진 틱을 절전으로 오판하지 않을 만큼 넉넉하게 잡았다. */
    static final Duration RESUME_GAP = Duration.ofMinutes(3);
    /** 배터리 경고 전에 연속으로 배터리를 확인하는 틱 수 — 순간 끊김을 거른다(30초 틱이면 30초 이상). */
    static final int DC_CONFIRM_TICKS = 2;
    /** 이 잔량(%) 이하면 CRITICAL — 10/12 실측 소모(시간당 약 24%)로 1시간 남짓 남은 때. */
    static final int LOW_BATTERY_PERCENT = 30;

    private final MarketSessionService marketSession;
    private final SystemSleepBlocker sleepBlocker;
    private final Notifier notifier;
    private final Clock clock;
    private final boolean keepAwake;
    /** 거래일 이 시각부터 장 대응 시작 전까지도 절전을 막는다 — null이면 ACTIVE 동안만. */
    private final LocalTime keepAwakeFrom;
    private final PowerSource powerSource;

    private final AtomicReference<Instant> lastTick = new AtomicReference<>();
    private final AtomicBoolean lastActive = new AtomicBoolean(false);

    /** 배터리 감시 상태 — 틱은 한 번에 하나씩 돈다(fixedDelay). */
    private volatile Boolean lastOnAc;
    private volatile int dcTicks;
    private volatile boolean dcWarned;
    private volatile boolean lowWarned;

    @Autowired
    public SleepGuard(MarketSessionService marketSession,
                      SystemSleepBlocker sleepBlocker,
                      Notifier notifier,
                      Clock clock,
                      @Value("${autostock.session.keep-awake:true}") boolean keepAwake,
                      @Value("${autostock.session.keep-awake-from:07:30}") String keepAwakeFrom,
                      PowerSource powerSource) {
        this.marketSession = marketSession;
        this.sleepBlocker = sleepBlocker;
        this.notifier = notifier;
        this.clock = clock;
        this.keepAwake = keepAwake;
        this.keepAwakeFrom = keepAwakeFrom == null || keepAwakeFrom.isBlank() ? null : LocalTime.parse(keepAwakeFrom);
        this.powerSource = powerSource;
    }

    /** 테스트용 — 전원 상태를 모르는 PC(배터리 감시 없음). */
    SleepGuard(MarketSessionService marketSession, SystemSleepBlocker sleepBlocker, Notifier notifier, Clock clock,
               boolean keepAwake, String keepAwakeFrom) {
        this(marketSession, sleepBlocker, notifier, clock, keepAwake, keepAwakeFrom, Optional::empty);
    }

    /** 테스트용 — 장전 준비 구간 없이(ACTIVE 동안만) 막는다. */
    SleepGuard(MarketSessionService marketSession, SystemSleepBlocker sleepBlocker, Notifier notifier, Clock clock,
               boolean keepAwake) {
        this(marketSession, sleepBlocker, notifier, clock, keepAwake, null);
    }

    @Scheduled(fixedDelay = 30_000)
    public void tick() {
        Instant now = clock.instant();
        boolean active = marketSession.isActive();
        reportResume(now, active);
        boolean awakeWindow = active || (keepAwakeFrom != null && marketSession.isPreOpen(keepAwakeFrom));
        if (keepAwake) {
            sleepBlocker.preventSleep(awakeWindow);
        }
        watchBattery(awakeWindow);
    }

    private void reportResume(Instant now, boolean active) {
        Instant previous = lastTick.getAndSet(now);
        boolean wasActive = lastActive.getAndSet(active);
        if (previous == null) {
            return;
        }
        Duration gap = Duration.between(previous, now);
        if (gap.compareTo(RESUME_GAP) < 0) {
            return;
        }
        String message = "절전 복귀 감지 — %s ~ %s (%d시간 %d분) 동안 앱이 멈춰 있었다".formatted(
                kst(previous), kst(now), gap.toHours(), gap.toMinutesPart());
        if (active || wasActive) {
            notifier.notify(NoticeLevel.WARN,
                    message + ". 장 대응 시간이 걸려 있어 이 구간의 시세·체결통보·예약 작업이 누락됐다");
        } else {
            log.info("{} (장외 구간 — 누락 없음)", message);
        }
    }

    /** 충전기·배터리 — 바뀔 때마다 로그 한 줄, 절전을 막는 시간에만 알림(클래스 설명). */
    private void watchBattery(boolean awakeWindow) {
        Optional<PowerStatus> read;
        try {
            read = powerSource.read();
        } catch (RuntimeException e) {
            return;   // 감시는 부가 기능 — 조회 실패가 절전 대응을 막지 않는다
        }
        if (read.isEmpty()) {
            return;
        }
        PowerStatus power = read.get();
        if (lastOnAc == null || lastOnAc != power.onAc()) {
            log.info("전원: {} — {}", power.onAc() ? "충전기 연결" : "배터리로 동작", power.batteryText());
        }
        lastOnAc = power.onAc();
        if (power.onAc()) {
            if (dcWarned) {
                notifier.notify(NoticeLevel.INFO, "충전기 다시 연결됨 — %s".formatted(power.batteryText()));
            }
            dcTicks = 0;
            dcWarned = false;
            lowWarned = false;
            return;
        }
        dcTicks++;
        if (!awakeWindow || dcTicks < DC_CONFIRM_TICKS) {
            return;   // 장외이거나 아직 순간 끊김일 수 있다
        }
        if (!dcWarned) {
            dcWarned = true;
            notifier.notify(NoticeLevel.WARN, ("노트북이 배터리로 돌고 있다(%s) — 장 대응 시간이니 충전기를 연결해야 한다. "
                    + "배터리로는 화면 꺼짐·절전 설정과 방전 때문에 몇 시간 안에 앱이 멈춘다(10/12 실측: 시간당 약 24%% 소모, "
                    + "08:14 화면 꺼짐 → 09:20 절전으로 정지)").formatted(power.batteryText()));
        }
        Integer percent = power.batteryPercent();
        if (!lowWarned && percent != null && percent <= LOW_BATTERY_PERCENT) {
            lowWarned = true;
            notifier.notify(NoticeLevel.CRITICAL,
                    "배터리 %d%% — 곧 방전돼 앱이 멈춘다. 지금 충전기를 연결해야 한다(장 대응 시간)".formatted(percent));
        }
    }

    private static LocalDateTime kst(Instant instant) {
        return LocalDateTime.ofInstant(instant, MarketConstants.KST).truncatedTo(ChronoUnit.SECONDS);
    }
}
