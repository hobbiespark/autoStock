package com.autostock.monitor;

import com.autostock.common.util.MarketConstants;
import com.autostock.market.MarketSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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
 *       밤·주말에는 평소대로 절전된다. {@code autostock.session.keep-awake=false}면 끈다.</li>
 *   <li><b>복귀 감지</b>: 30초 틱 사이 간격이 {@link #RESUME_GAP} 이상이면 절전(또는 프로세스 정지)에서
 *       돌아온 것으로 본다. 멈춘 구간이 장 대응 시간에 걸쳤으면 WARN 알림, 아니면 INFO 로그만 남긴다.</li>
 * </ul>
 */
@Component
public class SleepGuard {

    private static final Logger log = LoggerFactory.getLogger(SleepGuard.class);

    /** 틱 주기(30초)의 여섯 배 — GC·부하로 늦어진 틱을 절전으로 오판하지 않을 만큼 넉넉하게 잡았다. */
    static final Duration RESUME_GAP = Duration.ofMinutes(3);

    private final MarketSessionService marketSession;
    private final SystemSleepBlocker sleepBlocker;
    private final Notifier notifier;
    private final Clock clock;
    private final boolean keepAwake;

    private final AtomicReference<Instant> lastTick = new AtomicReference<>();
    private final AtomicBoolean lastActive = new AtomicBoolean(false);

    public SleepGuard(MarketSessionService marketSession,
                      SystemSleepBlocker sleepBlocker,
                      Notifier notifier,
                      Clock clock,
                      @Value("${autostock.session.keep-awake:true}") boolean keepAwake) {
        this.marketSession = marketSession;
        this.sleepBlocker = sleepBlocker;
        this.notifier = notifier;
        this.clock = clock;
        this.keepAwake = keepAwake;
    }

    @Scheduled(fixedDelay = 30_000)
    public void tick() {
        Instant now = clock.instant();
        boolean active = marketSession.isActive();
        reportResume(now, active);
        if (keepAwake) {
            sleepBlocker.preventSleep(active);
        }
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

    private static LocalDateTime kst(Instant instant) {
        return LocalDateTime.ofInstant(instant, MarketConstants.KST).truncatedTo(ChronoUnit.SECONDS);
    }
}
