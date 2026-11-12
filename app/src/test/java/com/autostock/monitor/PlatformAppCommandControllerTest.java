package com.autostock.monitor;

import com.autostock.risk.KillSwitch;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** VALUNIVO 610(D300): 니보가 전달하는 할 일 입구. 루프백·토큰, 처리·거절·미지원을 지어내지 않고 답한다. */
class PlatformAppCommandControllerTest {
    @TempDir Path dir;

    PlatformAppCommandController controller(TelegramStockCommands commands, KillSwitch killSwitch, DashboardFacade facade) throws Exception {
        Path token = Files.writeString(dir.resolve("token"), "synthetic-app-token-0123456789\n");
        return new PlatformAppCommandController(commands, killSwitch, facade, token.toString());
    }

    static MockHttpServletRequest from(String address) { var request = new MockHttpServletRequest(); request.setRemoteAddr(address); return request; }
    static final String AUTH = "Bearer synthetic-app-token-0123456789";

    @Test void statusStopAndKillSwitchAreHandledResumeIsRefused() throws Exception {
        var commands = mock(TelegramStockCommands.class); var killSwitch = mock(KillSwitch.class); var facade = mock(DashboardFacade.class);
        when(commands.status()).thenReturn(new TelegramStockCommands.Reply(true, "status", "킬스위치 꺼짐, 보유 0종목", null));
        when(commands.stop()).thenReturn(new TelegramStockCommands.Reply(true, "kill-switch-engaged", "킬스위치를 작동했습니다", null));
        when(killSwitch.isEngaged()).thenReturn(true);
        var c = controller(commands, killSwitch, facade);
        var status = c.run("status_view", AUTH, null, from("127.0.0.1"));
        assertEquals(HttpStatus.OK, status.getStatusCode());
        assertEquals(new PlatformAppCommandController.Result("ok", "킬스위치 꺼짐, 보유 0종목"), status.getBody());
        assertEquals("ok", c.run("emergency_stop", AUTH, null, from("127.0.0.1")).getBody().status());
        verify(commands).stop();
        assertEquals("킬스위치: 작동 중(신규 주문 막힘)", c.run("killswitch_view", AUTH, null, from("::1")).getBody().text());
        var resume = c.run("trading_resume", AUTH, null, from("127.0.0.1")).getBody();
        assertEquals("refused", resume.status());
        assertTrue(resume.text().contains("/nivo_autostock_trading_resume"));
        verify(commands, never()).requestResume(anyString());
        verify(killSwitch, never()).release(anyString());
    }

    @Test void notYetCommandsSayFailedAndUnknownIsNotFound() throws Exception {
        var c = controller(mock(TelegramStockCommands.class), mock(KillSwitch.class), mock(DashboardFacade.class));
        for (String id : PlatformAppCommandController.NOT_YET_COMMANDS)
            assertEquals(new PlatformAppCommandController.Result("failed", PlatformAppCommandController.NOT_YET), c.run(id, AUTH, null, from("127.0.0.1")).getBody(), id);
        assertEquals(HttpStatus.NOT_FOUND, c.run("transfer_money", AUTH, null, from("127.0.0.1")).getStatusCode());
    }

    @Test void readOnlyCommandsReuseTheScreenBeansAndCheckArguments() throws Exception {
        // VALUNIVO 611: 시세·판단·주문 요청·일일 리포트는 화면용 빈을 그대로 쓴다. 인자는 직접 확인하고 실패는 failed로 답한다.
        var facade = mock(DashboardFacade.class);
        var c = controller(mock(TelegramStockCommands.class), mock(KillSwitch.class), facade);
        var dashboard = mock(DashboardController.class); var decisions = mock(DecisionController.class);
        var orders = mock(OrderHistoryController.class); var report = mock(DailyReportScheduler.class);
        c.dashboard(dashboard); c.decisions(decisions); c.orderHistory(orders); c.dailyReport(report);
        assertEquals("refused", c.readOnly("quote_view", "시세 알려줘").status(), "no symbol");
        when(dashboard.quote("005930")).thenReturn(new DashboardController.QuoteView("005930", "삼성전자", new java.math.BigDecimal("71000"), null, null, null, null, null));
        var quote = c.readOnly("quote_view", "005930 시세 어때?");
        assertEquals("ok", quote.status()); assertTrue(quote.text().contains("71,000"), quote.text()); // 835: 천 단위 쉼표
        when(dashboard.quote("000660")).thenReturn(new DashboardController.QuoteView("000660", null, null, null, null, null, null, null));
        assertEquals("failed", c.readOnly("quote_view", "000660").status(), "empty quote is not invented");
        when(decisions.decisions(java.time.LocalDate.of(2026, 11, 4))).thenReturn(java.util.List.of());
        assertEquals(new PlatformAppCommandController.Result("ok", "2026-11-04 판단 기록이 없습니다."), c.readOnly("decisions_view", "2026-11-04 판단 보여줘"));
        assertEquals("refused", c.readOnly("decisions_view", "2026-13-30").status(), "date-shaped but invalid");
        when(orders.orders(7)).thenReturn(new com.autostock.monitor.view.OrderHistoryView(java.util.List.of()));
        assertEquals("최근 7일 매매 요청이 없습니다.", c.readOnly("request_history_view", "").text());
        when(report.strategyLines()).thenReturn(java.util.List.of("운영 상태: 정상"));
        when(report.cumulativeLines()).thenReturn(java.util.List.of("누적 손익: 0"));
        var daily = c.readOnly("daily_report_view", "");
        assertTrue(daily.text().startsWith("일일 리포트(지금 값으로 다시 만든 것"), daily.text());
        assertTrue(daily.text().endsWith("운영 상태: 정상\n누적 손익: 0"), daily.text());
        verify(report, never()).sendDailyReport();
        when(dashboard.quote("035720")).thenThrow(new IllegalStateException("broker down"));
        assertEquals("failed", c.readOnly("quote_view", "035720").status());
    }

    @Test void onlyLoopbackWithTheRightTokenIsServed() throws Exception {
        var commands = mock(TelegramStockCommands.class);
        var c = controller(commands, mock(KillSwitch.class), mock(DashboardFacade.class));
        assertEquals(HttpStatus.FORBIDDEN, c.run("emergency_stop", AUTH, null, from("10.0.0.5")).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, c.run("emergency_stop", null, null, from("127.0.0.1")).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, c.run("emergency_stop", "Bearer wrong", null, from("127.0.0.1")).getStatusCode());
        verify(commands, never()).stop();
        Path empty = Files.writeString(dir.resolve("empty"), "  ");
        assertThrows(IllegalArgumentException.class, () -> new PlatformAppCommandController(commands, mock(KillSwitch.class), mock(DashboardFacade.class), empty.toString()));
    }

    /** VALUNIVO 3-4 C3: 설명서 입구는 같은 인증으로 명령 원천을 그대로 내고, 본문의 명령 판이 원천과 다르면 실행하지 않는다. */
    @Test void manifestEntryAndCommandVersionGuard() throws Exception {
        var commands = mock(TelegramStockCommands.class);
        when(commands.status()).thenReturn(new TelegramStockCommands.Reply(true, "status", "킬스위치 꺼짐, 보유 0종목", null));
        var c = controller(commands, mock(KillSwitch.class), mock(DashboardFacade.class));
        assertEquals(HttpStatus.UNAUTHORIZED, c.manifest(null, from("127.0.0.1")).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, c.manifest(AUTH, from("192.168.0.10")).getStatusCode());
        var manifest = (tools.jackson.databind.JsonNode) c.manifest(AUTH, from("127.0.0.1")).getBody();
        assertEquals("1.1", manifest.path("contract_version").asText());
        assertEquals(13, manifest.path("commands").size());
        assertEquals(new AppCommandCatalog().manifest(), manifest, "원천 그대로");

        var stale = new PlatformAppCommandController.Body("r1", "sp_self", "quote_view", "005930", 1);
        assertEquals(new PlatformAppCommandController.Result("refused", PlatformAppCommandController.VERSION_CHANGED),
                c.run("quote_view", AUTH, stale, from("127.0.0.1")).getBody(), "판 2인 명령에 판 1로 온 옛 요청");
        var current = new PlatformAppCommandController.Body("r2", "sp_self", "status_view", "", 1);
        assertEquals("ok", c.run("status_view", AUTH, current, from("127.0.0.1")).getBody().status());
        var unversioned = new PlatformAppCommandController.Body("r3", "sp_self", "status_view", "", null);
        assertEquals("ok", c.run("status_view", AUTH, unversioned, from("127.0.0.1")).getBody().status(), "1.0 플랫폼은 판을 싣지 않는다");
    }

    /** VALUNIVO 855: 플랫폼이 보낸 명령 한 건마다 명령 ID·요청 ID·결과·답 글자 수를 한 줄 남긴다. 본문·답 글·토큰은 남기지 않는다. */
    @Test void everyCommandLeavesOneResultLineWithoutTextOrToken() throws Exception {
        var commands = mock(TelegramStockCommands.class);
        when(commands.status()).thenReturn(new TelegramStockCommands.Reply(true, "status", "킬스위치 꺼짐, 보유 0종목", null));
        var c = controller(commands, mock(KillSwitch.class), mock(DashboardFacade.class));
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AppCommandLog.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            c.run("status_view", AUTH, new PlatformAppCommandController.Body("01JREQ0001", "sp_self", "status_view", "상태 알려줘 비밀 아닌 글", 1), from("127.0.0.1"));
            c.run("status_view", AUTH, null, from("127.0.0.1"));
            c.run("quote_view", AUTH, new PlatformAppCommandController.Body("01JREQ0002", "sp_self", "quote_view", "005930", 1), from("127.0.0.1"));
            c.run("transfer_money", AUTH, new PlatformAppCommandController.Body("요청 한글", "sp_self", "transfer_money", "", null), from("127.0.0.1"));
            c.run("emergency_stop", "Bearer wrong", null, from("127.0.0.1"));
            c.run("status view 한글", AUTH, null, from("10.0.0.5"));
            var lines = appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
            assertEquals(java.util.List.of(
                    "앱 명령 — route=platform command=status_view request=01JREQ0001 outcome=ok http=200 chars=" + "킬스위치 꺼짐, 보유 0종목".length(),
                    "앱 명령 — route=platform command=status_view request=- outcome=ok http=200 chars=" + "킬스위치 꺼짐, 보유 0종목".length(),
                    "앱 명령 — route=platform command=quote_view request=01JREQ0002 outcome=refused=version-changed http=200 chars=" + PlatformAppCommandController.VERSION_CHANGED.length(),
                    "앱 명령 — route=platform command=transfer_money request=other outcome=unknown-command http=404 chars=0",
                    "앱 명령 — route=platform command=emergency_stop request=- outcome=denied=unauthorized http=401 chars=0",
                    "앱 명령 — route=platform command=other request=- outcome=denied=loopback-only http=403 chars=0"), lines);
            assertTrue(lines.stream().noneMatch(l -> l.contains("킬스위치") || l.contains("비밀") || l.contains("synthetic-app-token") || l.contains("wrong")), lines.toString());
        } finally {
            logger.detachAppender(appender);
        }
    }
}
