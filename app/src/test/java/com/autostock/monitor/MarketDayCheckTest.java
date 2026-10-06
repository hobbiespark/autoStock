package com.autostock.monitor;

import com.autostock.common.event.StrategyRunCompleted;
import com.autostock.common.event.TrProbeCompleted;
import com.autostock.market.MarketCalendarService;
import com.autostock.market.MarketSessionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.DefaultHealthContributorRegistry;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 장중 자가 점검 — 전략 실행 알림, 09:15 실행 확인, 10:00·14:00 상태 점검(이상일 때만 알림).
 */
class MarketDayCheckTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 7);
    /** 2026-10-07(수) 10:00 KST. */
    private static final Clock AT_10 = Clock.fixed(Instant.parse("2026-10-07T01:00:00Z"), ZoneOffset.UTC);

    private record Notice(NoticeLevel level, String message) {
    }

    private final List<Notice> notices = new CopyOnWriteArrayList<>();
    private final NotificationDispatcher dispatcher =
            new NotificationDispatcher((level, message) -> notices.add(new Notice(level, message)));
    private final MarketCalendarService calendar = mock(MarketCalendarService.class);
    private final MarketSessionService session = mock(MarketSessionService.class);
    private final TradingSystemManager tradingSystem = mock(TradingSystemManager.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AtomicLong unknownOrders = new AtomicLong();
    private final Map<String, HealthContributor> health = new HashMap<>();

    @BeforeEach
    void setUp() {
        when(calendar.isTradingDay(any())).thenReturn(true);
        when(session.isActive()).thenReturn(true);
        when(tradingSystem.status()).thenReturn(TradingSystemStatus.RUNNING);
        meters.gauge("orders.unknown.count", unknownOrders);
        meters.gauge("orders.cancel_requested.count", new AtomicLong());
        health.put("kiwoomWs", (HealthIndicator) () -> Health.up().withDetail("session", "ACTIVE")
                .withDetail("connected", true).withDetail("loggedIn", true).withDetail("lastMessageAgeSeconds", 4L).build());
        health.put("kiwoomAuth", (HealthIndicator) () -> Health.up().withDetail("lastIssuedAt", "2026-10-06T22:03:54Z")
                .withDetail("expiresAt", "2026-10-07T22:03:54Z").build());
        health.put("killSwitch", (HealthIndicator) () -> Health.up().withDetail("engaged", false).build());
    }

    @SuppressWarnings("unchecked")
    private MarketDayCheck check(MarketCheckProperties properties) {
        ObjectProvider<HealthContributorRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(new DefaultHealthContributorRegistry(health));
        return new MarketDayCheck(properties, dispatcher, calendar, session, tradingSystem, provider, meters, true, AT_10);
    }

    private MarketDayCheck check() {
        return check(new MarketCheckProperties(true, true, 120));
    }

    private List<Notice> sent() {
        dispatcher.drain();
        return new ArrayList<>(notices);
    }

    private static StrategyRunCompleted run(LocalDate day, int errors) {
        return new StrategyRunCompleted("C3-MOMENTUM", day, "C3 판단 %s — 국면 ON · 주기 전 5 · 오류 %d".formatted(day, errors),
                errors, Instant.parse("2026-10-07T00:05:13Z"));
    }

    @Test
    void 전략_실행_요약을_한_줄로_보내고_오류가_있으면_WARN이다() {
        MarketDayCheck check = check();

        check.onStrategyRun(run(DAY, 0));
        check.onStrategyRun(run(DAY, 2));

        List<Notice> sent = sent();
        assertEquals(2, sent.size());
        assertEquals(new Notice(NoticeLevel.INFO, "C3 판단 2026-10-07 — 국면 ON · 주기 전 5 · 오류 0"), sent.get(0));
        assertEquals(NoticeLevel.WARN, sent.get(1).level());
    }

    @Test
    void 실행_요약_알림을_끄면_보내지_않지만_실행_기록은_남긴다() {
        MarketDayCheck check = check(new MarketCheckProperties(true, false, 120));

        check.onStrategyRun(run(DAY, 0));
        check.checkStrategyRan();

        assertTrue(sent().isEmpty());
    }

    @Test
    void 오늘_C3가_돌았으면_0915_점검은_조용하다() {
        MarketDayCheck check = check();
        check.onStrategyRun(run(DAY, 0));

        check.checkStrategyRan();

        List<Notice> sent = sent();   // 대기열은 한 번만 비울 수 있다(종료와 같은 동작) — 끝에서 한 번 본다
        assertEquals(1, sent.size(), "실행 요약 INFO 한 줄뿐이어야 한다");
        assertEquals(NoticeLevel.INFO, sent.get(0).level());
    }

    @Test
    void 오늘_C3_실행_기록이_없으면_0915에_WARN이다() {
        MarketDayCheck check = check();
        check.onStrategyRun(run(DAY.minusDays(1), 0));   // 어제 기록만

        check.checkStrategyRan();

        List<Notice> sent = sent();
        assertEquals(2, sent.size());   // 어제 실행 요약 INFO + 오늘 점검 WARN
        assertEquals(NoticeLevel.WARN, sent.get(1).level());
        assertTrue(sent.get(1).message().contains("09:05 실행 기록 없음"), sent.get(1).message());
    }

    @Test
    void 매매_시스템이_멈춰_있으면_그_상태를_적는다() {
        when(tradingSystem.status()).thenReturn(TradingSystemStatus.STOPPED);

        check().checkStrategyRan();

        assertTrue(sent().get(0).message().contains("매매 시스템 상태 STOPPED"));
    }

    @Test
    void 휴장일에는_점검하지_않는다() {
        when(calendar.isTradingDay(any())).thenReturn(false);
        MarketDayCheck check = check();

        check.checkStrategyRan();
        check.checkStatus();

        assertTrue(sent().isEmpty());
    }

    @Test
    void 장중_상태가_정상이면_로그만_남기고_알리지_않는다() {
        MarketDayCheck check = check();
        List<String> problems = new ArrayList<>();

        String line = check.statusLine(problems);
        check.checkStatus();

        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals("WS UP(연결·로그인, 마지막 메시지 4초 전) · 토큰 UP(만료 10/08 07:03) · 킬스위치 꺼짐 · UNKNOWN 0 · 취소 요청 0",
                line);
        assertTrue(sent().isEmpty());
    }

    @Test
    void WS가_로그인되지_않았으면_WARN이다() {
        health.put("kiwoomWs", (HealthIndicator) () -> Health.down().withDetail("session", "ACTIVE")
                .withDetail("connected", true).withDetail("loggedIn", false).withDetail("lastMessageAgeSeconds", 300L).build());

        check().checkStatus();

        List<Notice> sent = sent();
        assertEquals(1, sent.size());
        assertEquals(NoticeLevel.WARN, sent.get(0).level());
        assertTrue(sent.get(0).message().startsWith("장중 점검 10:00 이상: WS 연결·로그인 아님, WS 메시지 300초째 없음"),
                sent.get(0).message());
    }

    @Test
    void UNKNOWN_주문이나_킬스위치_작동은_WARN이다() {
        unknownOrders.set(2);
        health.put("killSwitch", (HealthIndicator) () -> Health.up().withDetail("engaged", true)
                .withDetail("reason", "일 손실 한도").build());

        check().checkStatus();

        String message = sent().get(0).message();
        assertTrue(message.contains("킬스위치 작동 중"), message);
        assertTrue(message.contains("UNKNOWN 주문 2건"), message);
        assertTrue(message.contains("킬스위치 작동(일 손실 한도)"), message);
    }

    @Test
    void 장외에는_상태_점검을_하지_않는다() {
        when(session.isActive()).thenReturn(false);
        health.put("kiwoomWs", (HealthIndicator) () -> Health.up().withDetail("connected", false).build());

        check().checkStatus();

        assertTrue(sent().isEmpty());
    }

    @Test
    void TR_실측_완료를_알린다() {
        MarketDayCheck check = check();

        check.onTrProbe(new TrProbeCompleted(DAY, true, "TR 실측(1.6) 완료 — …", Instant.now()));
        check.onTrProbe(new TrProbeCompleted(DAY, false, "TR 실측(1.6) 일부 실패 — …", Instant.now()));

        List<Notice> sent = sent();
        assertEquals(NoticeLevel.INFO, sent.get(0).level());
        assertEquals(NoticeLevel.WARN, sent.get(1).level());
    }
}
