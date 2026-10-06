package com.autostock.monitor;

import com.autostock.common.event.StrategyRunCompleted;
import com.autostock.common.event.TrProbeCompleted;
import com.autostock.common.util.MarketConstants;
import com.autostock.market.MarketCalendarService;
import com.autostock.market.MarketSessionService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 장중 자가 점검 — 사람이 장중에 로그·화면을 보지 않아도 되게 한다(2026-10-06, aiDoc/market-hours-automation.md).
 *
 * <ul>
 *   <li>전략 정기 실행 요약({@link StrategyRunCompleted}, 예: C3 09:05)을 텔레그램으로 한 줄 보낸다. 종목 판단 오류가
 *       있으면 WARN.</li>
 *   <li>09:15 — 오늘 C3 실행 기록이 없으면 WARN(매매 시스템 상태를 함께 적는다).</li>
 *   <li>10:00·14:00 — 헬스(kiwoomWs·kiwoomAuth·killSwitch)와 주문 지표로 한 줄 상태를 로그에 남기고, 이상이 있으면
 *       같은 줄을 WARN으로 보낸다.</li>
 *   <li>체결·미체결 TR 실측 완료({@link TrProbeCompleted}, 실행 계획 1.6)를 알린다.</li>
 * </ul>
 * 휴장일과 장외(STANDBY)에는 점검하지 않는다. 헬스는 actuator와 같은 인디케이터를 이름으로 읽는다 — 다른 모듈의
 * 내부 클래스를 직접 알 필요가 없다.
 */
@Component
public class MarketDayCheck {

    private static final Logger log = LoggerFactory.getLogger(MarketDayCheck.class);

    static final String C3_STRATEGY_ID = "C3-MOMENTUM";
    private static final DateTimeFormatter EXPIRES = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    private final MarketCheckProperties properties;
    private final NotificationDispatcher dispatcher;
    private final MarketCalendarService marketCalendar;
    private final MarketSessionService marketSession;
    private final TradingSystemManager tradingSystem;
    private final ObjectProvider<HealthContributorRegistry> healthRegistry;
    private final MeterRegistry meterRegistry;
    private final boolean c3Enabled;
    private final Clock clock;

    /** 전략별 마지막 정기 실행일 — 09:15 확인용(재기동하면 비지만 09:05 실행이 다시 채운다). */
    private final Map<String, LocalDate> lastRun = new ConcurrentHashMap<>();

    public MarketDayCheck(MarketCheckProperties properties, NotificationDispatcher dispatcher,
                          MarketCalendarService marketCalendar, MarketSessionService marketSession,
                          TradingSystemManager tradingSystem, ObjectProvider<HealthContributorRegistry> healthRegistry,
                          MeterRegistry meterRegistry, @Value("${strategy.c3.enabled:false}") boolean c3Enabled,
                          Clock clock) {
        this.properties = properties;
        this.dispatcher = dispatcher;
        this.marketCalendar = marketCalendar;
        this.marketSession = marketSession;
        this.tradingSystem = tradingSystem;
        this.healthRegistry = healthRegistry;
        this.meterRegistry = meterRegistry;
        this.c3Enabled = c3Enabled;
        this.clock = clock;
    }

    @EventListener
    public void onStrategyRun(StrategyRunCompleted event) {
        lastRun.put(event.strategyId(), event.tradeDate());
        if (properties.strategyRunNotice()) {
            dispatcher.submit(event.errors() > 0 ? NoticeLevel.WARN : NoticeLevel.INFO, event.summary());
        }
    }

    @EventListener
    public void onTrProbe(TrProbeCompleted event) {
        dispatcher.submit(event.success() ? NoticeLevel.INFO : NoticeLevel.WARN, event.summary());
    }

    /** 09:15 — C3(09:05)가 오늘 돌았는가. */
    @Scheduled(cron = "0 15 9 * * MON-FRI", zone = "Asia/Seoul")
    public void checkStrategyRan() {
        LocalDate today = LocalDate.now(clock.withZone(MarketConstants.KST));
        if (!properties.enabled() || !c3Enabled || !marketCalendar.isTradingDay(today)) {
            return;
        }
        if (today.equals(lastRun.get(C3_STRATEGY_ID))) {
            log.info("장중 점검 09:15 — C3 오늘 실행 확인");
            return;
        }
        TradingSystemStatus status = tradingSystem.status();
        String message = status == TradingSystemStatus.RUNNING
                ? "장중 점검: C3 오늘(%s) 09:05 실행 기록 없음 — 로그의 C3 줄 확인 필요".formatted(today)
                : "장중 점검: C3 오늘(%s) 실행 안 함 — 매매 시스템 상태 %s".formatted(today, status);
        log.warn(message);
        dispatcher.submit(NoticeLevel.WARN, message);
    }

    /** 10:00·14:00 — 장중 상태 한 줄. 이상이 있을 때만 텔레그램. */
    @Scheduled(cron = "0 0 10,14 * * MON-FRI", zone = "Asia/Seoul")
    public void checkStatus() {
        LocalDate today = LocalDate.now(clock.withZone(MarketConstants.KST));
        if (!properties.enabled() || !marketCalendar.isTradingDay(today) || !marketSession.isActive()) {
            return;
        }
        List<String> problems = new ArrayList<>();
        String line = statusLine(problems);
        String time = LocalTime.now(clock.withZone(MarketConstants.KST)).truncatedTo(ChronoUnit.MINUTES).toString();
        if (problems.isEmpty()) {
            log.info("장중 점검 {} — {}", time, line);
            return;
        }
        String message = "장중 점검 %s 이상: %s — %s".formatted(time, String.join(", ", problems), line);
        log.warn(message);
        dispatcher.submit(NoticeLevel.WARN, message);
    }

    /** 상태 한 줄을 만들고, 이상은 problems에 모은다. */
    String statusLine(List<String> problems) {
        List<String> parts = new ArrayList<>();

        Health ws = health("kiwoomWs");
        if (ws == null) {
            parts.add("WS 헬스 없음");
            problems.add("WS 헬스 없음");
        } else {
            Map<String, Object> details = ws.getDetails();
            boolean connected = Boolean.TRUE.equals(details.get("connected"));
            boolean loggedIn = Boolean.TRUE.equals(details.get("loggedIn"));
            Object age = details.get("lastMessageAgeSeconds");
            parts.add("WS %s(%s·%s, %s)".formatted(ws.getStatus().getCode(), connected ? "연결" : "미연결",
                    loggedIn ? "로그인" : "미로그인", age == null ? "받은 메시지 없음" : "마지막 메시지 " + age + "초 전"));
            if (!Status.UP.equals(ws.getStatus()) || !connected || !loggedIn) {
                problems.add("WS 연결·로그인 아님");
            }
            if (age == null) {
                problems.add("WS 메시지를 받은 적 없음");
            } else if (age instanceof Number seconds && seconds.longValue() > properties.maxMessageAgeSeconds()) {
                problems.add("WS 메시지 " + seconds + "초째 없음");
            }
        }

        Health auth = health("kiwoomAuth");
        if (auth == null) {
            parts.add("토큰 헬스 없음");
            problems.add("토큰 헬스 없음");
        } else if (Status.UP.equals(auth.getStatus())) {
            parts.add("토큰 UP(만료 " + kst(auth.getDetails().get("expiresAt")) + ")");
        } else {
            Object why = auth.getDetails().getOrDefault("failure", auth.getDetails().getOrDefault("lastIssuedAt", ""));
            parts.add("토큰 %s(%s)".formatted(auth.getStatus().getCode(), why));
            problems.add("토큰 " + auth.getStatus().getCode());
        }

        Health killSwitch = health("killSwitch");
        if (killSwitch != null) {
            boolean engaged = Boolean.TRUE.equals(killSwitch.getDetails().get("engaged"));
            parts.add(engaged ? "킬스위치 작동(" + killSwitch.getDetails().getOrDefault("reason", "사유 없음") + ")"
                    : "킬스위치 꺼짐");
            if (engaged) {
                problems.add("킬스위치 작동 중");
            }
        }

        long unknown = gauge("orders.unknown.count");
        long cancelRequested = gauge("orders.cancel_requested.count");
        parts.add("UNKNOWN %d · 취소 요청 %d".formatted(unknown, cancelRequested));
        if (unknown > 0) {
            problems.add("UNKNOWN 주문 " + unknown + "건");
        }
        return String.join(" · ", parts);
    }

    private Health health(String name) {
        HealthContributorRegistry registry = healthRegistry.getIfAvailable();
        if (registry == null) {
            return null;
        }
        HealthContributor contributor = registry.getContributor(name);
        return contributor instanceof HealthIndicator indicator ? indicator.health() : null;
    }

    private long gauge(String name) {
        Gauge gauge = meterRegistry.find(name).gauge();
        if (gauge == null) {
            return 0;
        }
        double value = gauge.value();
        return Double.isFinite(value) ? Math.round(value) : 0;
    }

    private static String kst(Object isoInstant) {
        if (isoInstant == null) {
            return "?";
        }
        try {
            return Instant.parse(String.valueOf(isoInstant)).atZone(MarketConstants.KST).format(EXPIRES);
        } catch (DateTimeParseException e) {
            return String.valueOf(isoInstant);
        }
    }
}
