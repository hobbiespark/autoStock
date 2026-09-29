package com.autostock.market;

import com.autostock.common.util.MarketConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 장외 대기 판정 — market 모듈 공개 API. 프로세스는 계속 살아 있되, 장 마감 30분 후부터
 * 다음 장 시작 30분 전까지는 장 대응 작업을 멈추게 하는 기준 시계다.
 *
 * <h2>판정 규칙</h2>
 * <pre>
 *   거래일(MarketCalendarService) &amp;&amp; wake-time ≤ 현재(KST) &lt; sleep-time  → ACTIVE
 *   그 외(장외 시간, 주말, 휴장일)                                        → STANDBY
 * </pre>
 * 기본값: wake-time 08:30(정규장 09:00 − 30분), sleep-time 16:00(정규장 15:30 + 30분).
 * 15:45 분봉 적재·15:50 일일 리포트는 ACTIVE 창 안에 있으므로 영향이 없다.
 *
 * <h2>상태를 저장하지 않고 매번 계산한다</h2>
 * {@link #current()}는 호출 시점의 시계로 매번 판정하는 순수 함수다. "전환 시각에 이벤트를
 * 한 번 쏘는" 방식은 PC 절전·시계 점프(2026-09-22·24 실측: Hikari clock leap 4h40m) 때
 * 전환을 놓칠 수 있지만, 매번 계산하면 절전에서 깨어난 첫 호출부터 올바른 값이 나온다.
 * 소비자(WS watchdog, 주기 대사, 미체결 취소)는 각자의 스케줄 틱에서 이 값을 조회한다.
 * 거래일 판정은 {@link MarketCalendarService}의 연도 캐시를 타므로 호출 비용은 무시할 수준이다.
 *
 * <h2>전환 로그</h2>
 * {@link #logTransition()}이 30초마다 값을 확인해 바뀌었을 때만 INFO 한 줄을 남긴다 —
 * 운영 로그에서 "언제 대기로 들어가고 언제 깨어났는지"를 추적하기 위한 것이며, 동작 자체는
 * 이 로그에 의존하지 않는다.
 *
 * <p>{@code autostock.session.enabled=false}면 항상 ACTIVE(기존 24시간 동작과 동일) —
 * 장외에 WS·대사 동작을 직접 검증해야 할 때 쓴다.
 */
@Service
public class MarketSessionService {

    private static final Logger log = LoggerFactory.getLogger(MarketSessionService.class);

    private final MarketCalendarService calendar;
    private final Clock clock;
    private final boolean enabled;
    private final LocalTime wakeTime;
    private final LocalTime sleepTime;

    /** 마지막으로 로그에 남긴 세션 — 전환 로그 중복 방지용. 판정에는 쓰지 않는다. */
    private final AtomicReference<MarketSession> lastLogged = new AtomicReference<>();

    @Autowired
    public MarketSessionService(MarketCalendarService calendar,
                                Clock clock,
                                @Value("${autostock.session.enabled:true}") boolean enabled,
                                @Value("${autostock.session.wake-time:08:30}") String wakeTime,
                                @Value("${autostock.session.sleep-time:16:00}") String sleepTime) {
        this(calendar, clock, enabled, LocalTime.parse(wakeTime), LocalTime.parse(sleepTime));
    }

    /** 테스트·내부용 — 시각을 이미 파싱된 값으로 받는다. */
    MarketSessionService(MarketCalendarService calendar, Clock clock, boolean enabled,
                         LocalTime wakeTime, LocalTime sleepTime) {
        if (!wakeTime.isBefore(sleepTime)) {
            throw new IllegalArgumentException(
                    "autostock.session.wake-time(" + wakeTime + ")은 sleep-time(" + sleepTime + ")보다 앞서야 한다");
        }
        this.calendar = calendar;
        this.clock = clock;
        this.enabled = enabled;
        this.wakeTime = wakeTime;
        this.sleepTime = sleepTime;
    }

    /** 현재 세션. 클래스 설명 "판정 규칙" 참고. */
    public MarketSession current() {
        if (!enabled) {
            return MarketSession.ACTIVE;
        }
        return sessionAt(ZonedDateTime.now(clock).withZoneSameInstant(MarketConstants.KST));
    }

    /** 장 대응 시간대인가 — 소비자 가드용 단축 메서드. */
    public boolean isActive() {
        return current() == MarketSession.ACTIVE;
    }

    /** 대기 해제 시각(KST) — 로그 안내용. */
    public LocalTime wakeTime() {
        return wakeTime;
    }

    /** 대기 진입 시각(KST) — 로그 안내용. */
    public LocalTime sleepTime() {
        return sleepTime;
    }

    MarketSession sessionAt(ZonedDateTime kstNow) {
        LocalTime time = kstNow.toLocalTime();
        boolean inWindow = !time.isBefore(wakeTime) && time.isBefore(sleepTime);
        if (inWindow && calendar.isTradingDay(kstNow.toLocalDate())) {
            return MarketSession.ACTIVE;
        }
        return MarketSession.STANDBY;
    }

    /**
     * 다음 대기 해제 시각 — 전환 로그에 "언제 깨어나는지"를 함께 남기기 위한 계산.
     * 휴장일 데이터가 없는 먼 미래로 무한 루프하지 않도록 30일에서 멈춘다.
     */
    ZonedDateTime nextWake(ZonedDateTime kstNow) {
        LocalDate date = kstNow.toLocalDate();
        if (!kstNow.toLocalTime().isBefore(wakeTime)) {
            date = date.plusDays(1);
        }
        for (int i = 0; i < 30 && !calendar.isTradingDay(date); i++) {
            date = date.plusDays(1);
        }
        return date.atTime(wakeTime).atZone(MarketConstants.KST);
    }

    @Scheduled(fixedDelay = 30_000)
    public void logTransition() {
        if (!enabled) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(MarketConstants.KST);
        MarketSession session = sessionAt(now);
        MarketSession previous = lastLogged.getAndSet(session);
        if (previous == session) {
            return;
        }
        if (session == MarketSession.STANDBY) {
            log.info("시장 세션 {} → STANDBY — 장외 대기 (WS 해제·주기 대사/미체결 점검 중지). 다음 활성화: {}",
                    previous == null ? "(기동)" : previous, nextWake(now).toLocalDateTime());
        } else {
            log.info("시장 세션 {} → ACTIVE — 장 대응 재개 (대기 진입 예정 {})",
                    previous == null ? "(기동)" : previous, sleepTime);
        }
    }
}
