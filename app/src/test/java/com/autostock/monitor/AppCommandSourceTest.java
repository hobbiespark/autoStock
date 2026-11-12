package com.autostock.monitor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.autostock.portfolio.PositionBook;
import com.autostock.risk.KillSwitch;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * VALUNIVO 3-4 C3·C4(D338, 기획 12 7장): 명령 원천 하나. 원천의 명령 집합 = 실행 부품이 처리하는 집합 = 봇·help·메뉴·화면이 아는 집합,
 * 두 경로(니보 경유·앱 직접)가 같은 실행 부품을 거치고 위험 확인은 앱이 경로별로 한다.
 */
class AppCommandSourceTest {
    @TempDir Path dir;

    final AppCommandCatalog catalog = new AppCommandCatalog();

    /** 플랫폼 검사기(AppManifestValidator)의 L1 상향 낱말. 조회 명령이 L1로 올라가면 플랫폼 등급이 달라진다. */
    static final List<String> L1_TERMS = List.of(
            "trade", "order", "buy", "sell", "unpause", "resume", "unhalt", "release", "publish", "delete", "remove",
            "purge", "pay", "refund", "거래", "주문", "매수", "매도", "해제", "재개", "발행", "게시", "삭제", "결제", "환불", "송금");

    @Test
    void sourceAndRunnerKnowTheSameThirteenCommands() {
        assertEquals(13, catalog.ids().size());
        assertEquals(AppCommandRunner.HANDLED, new HashSet<>(catalog.ids()), "원천과 실행 부품의 명령 집합이 같다");
        for (String target : AppCommandCatalog.ALIASES.values()) assertTrue(catalog.find(target).isPresent(), target);
        assertEquals(Set.of("emergency_stop", "trading_resume"),
                Set.copyOf(catalog.commands().stream().filter(c -> !c.query()).map(AppCommandCatalog.Command::id).toList()));
    }

    @Test
    void manifestFollowsContractOnePointOneRules() {
        var manifest = catalog.manifest();
        assertEquals("1.1", manifest.path("contract_version").asText());
        assertTrue(manifest.path("supported_contract_versions").toString().contains("\"1.0\""));
        var id = Pattern.compile("[a-z][a-z0-9_]{0,23}");
        for (var node : manifest.path("commands")) {
            String cid = node.path("id").asText();
            assertTrue(id.matcher(cid).matches() && !cid.equals("help"), cid);
            assertTrue(node.path("name").asText().length() <= 40, cid);
            assertTrue(node.path("examples").size() >= 2 && node.path("examples").size() <= 10, cid);
            node.path("examples").forEach(e -> assertTrue(e.asText().length() <= 60, cid));
            assertTrue(node.path("args").size() <= 5, cid);
            assertTrue(node.path("version").intValue() >= 1, cid);
            node.path("entries").forEach(e -> assertTrue(Set.of("platform", "app_bot", "app_fe").contains(e.asText()), cid));
            if (node.path("risk").asText().equals("query")) {
                assertEquals("read", node.path("effect").asText(), cid);
                String joined = (cid + " " + node.path("name").asText() + " " + node.path("description").asText() + " "
                        + node.path("keywords").toString()).toLowerCase(Locale.ROOT);
                assertTrue(L1_TERMS.stream().noneMatch(joined::contains), "조회 명령이 플랫폼에서 L1로 오르지 않는다: " + cid);
            }
            // 인수를 선언한 명령은 의미가 바뀐 것이라 판을 올렸다(플랫폼 변경 기록의 "판 누락" 방지).
            if (node.path("args").size() > 0) assertTrue(node.path("version").intValue() >= 2, cid);
        }
    }

    @Test
    void directNamesHelpAndMenuComeFromTheSource() {
        assertEquals("status_view", catalog.direct("/autostock_status").orElseThrow().command().id());
        var quote = catalog.direct(" /autostock_quote 005930 ").orElseThrow();
        assertEquals("quote_view", quote.command().id());
        assertEquals("005930", quote.rest());
        assertEquals("killswitch_view", catalog.direct("/autostock_killswitch@AutoStockBot").orElseThrow().command().id());
        assertTrue(catalog.direct("/autostock_unknown").isEmpty());
        assertTrue(catalog.direct("/autostock_help").isEmpty(), "help는 할 일이 아니다");
        assertTrue(catalog.direct("/status").isEmpty(), "옛 이름은 폴러의 기존 경로가 받는다");
        String help = catalog.help();
        for (var c : catalog.on("app_bot")) assertTrue(help.contains(c.directName() + " " + c.name()), c.id());
        // VALUNIVO 812(D339): 예상 평문은 플랫폼용 기계 정보라 사용자 help에 보이지 않는다.
        for (var c : catalog.on("app_bot"))
            for (var example : c.examples()) assertFalse(help.contains(example), "예상 평문이 새어 나옴: " + example);
        assertTrue(help.contains("/stop /resume /status /ipo"));
        var menu = catalog.botMenu();
        assertEquals(catalog.on("app_bot").size() + 1, menu.size());
        assertEquals("autostock_help", menu.getFirst().getKey());
        for (var entry : menu) {
            assertTrue(AppCommandCatalog.TELEGRAM_NAME.matcher(entry.getKey()).matches(), entry.getKey());
            assertTrue(entry.getValue().length() <= 256);
        }
        @SuppressWarnings("unchecked")
        var commands = (List<Map<String, String>>) TelegramBotMenu.body(catalog).get("commands");
        assertEquals(menu.size(), commands.size());
        assertEquals("autostock_help", commands.getFirst().get("command"));
    }

    static final class FixedClock extends Clock {
        Instant now = Instant.parse("2026-11-10T13:00:00Z");
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void resumeUsesTheConfirmCodeOnlyOnTheAppBotAndTheScreenRunsQueriesOnly() {
        var killSwitch = mock(KillSwitch.class);
        when(killSwitch.isEngaged()).thenReturn(true);
        var runner = new AppCommandRunner(new TelegramStockCommands(killSwitch, new PositionBook(), new FixedClock()), killSwitch, mock(DashboardFacade.class));
        assertEquals(new AppCommandRunner.Result("refused", AppCommandRunner.RESUME_GUIDE), runner.run("trading_resume", "", AppCommandRunner.Route.PLATFORM));
        var issued = runner.run("trading_resume", "", AppCommandRunner.Route.APP_BOT);
        assertEquals("ok", issued.status());
        assertTrue(issued.text().contains("/autostock_trading_resume "), issued.text());
        var code = Pattern.compile("/autostock_trading_resume (\\d{4})").matcher(issued.text());
        assertTrue(code.find());
        String wrong = code.group(1).equals("0000") ? "1111" : "0000";
        assertEquals("refused", runner.run("trading_resume", wrong, AppCommandRunner.Route.APP_BOT).status(), "틀린 코드");
        verify(killSwitch, never()).release(anyString());
        // 코드는 한 번 쓰면 끝이라 다시 발급받아 맞는 코드로 확인한다.
        var again = Pattern.compile("/autostock_trading_resume (\\d{4})").matcher(runner.run("trading_resume", "", AppCommandRunner.Route.APP_BOT).text());
        assertTrue(again.find());
        assertEquals("ok", runner.run("trading_resume", again.group(1), AppCommandRunner.Route.APP_BOT).status());
        verify(killSwitch).release("telegram");
        // 화면은 변경 명령을 실행하지 않는다.
        assertEquals(new AppCommandRunner.Result("refused", AppCommandRunner.FE_CHANGE_GUIDE), runner.run("emergency_stop", "", AppCommandRunner.Route.APP_FE));
        assertEquals(new AppCommandRunner.Result("refused", AppCommandRunner.FE_CHANGE_GUIDE), runner.run("trading_resume", "", AppCommandRunner.Route.APP_FE));
        verify(killSwitch, never()).engage(anyString());
        assertEquals("ok", runner.run("killswitch_view", "", AppCommandRunner.Route.APP_FE).status());
        assertNull(runner.run("no_such", "", AppCommandRunner.Route.APP_BOT));
    }

    @Test
    void botDirectCommandsGoThroughTheSharedRunnerAndReplyByNotice() {
        var killSwitch = mock(KillSwitch.class);
        var notifier = mock(Notifier.class);
        var props = new TelegramProperties(true, "synthetic-bot-token", "123");
        var stock = new TelegramStockCommands(killSwitch, new PositionBook(), new FixedClock());
        var poller = new TelegramCommandPoller(org.springframework.web.reactive.function.client.WebClient.builder(), props, killSwitch, new PositionBook(), notifier, new FixedClock());
        poller.stockCommands(stock);
        poller.appCommands(catalog, new AppCommandRunner(stock, killSwitch, mock(DashboardFacade.class)));
        assertEquals(TelegramCommandPoller.Command.APP_DIRECT, TelegramCommandPoller.parseCommand("123", "/autostock_status", "123"));
        assertEquals(TelegramCommandPoller.Command.IGNORED, TelegramCommandPoller.parseCommand("999", "/autostock_status", "123"), "등록 안 된 채팅은 무시");
        assertEquals(TelegramCommandPoller.Command.STOP, TelegramCommandPoller.parseCommand("123", "/stop", "123"), "옛 이름은 그대로");

        poller.handleUpdate(update(1, "/autostock_help"));
        verify(notifier).notify(NoticeLevel.INFO, catalog.help());
        poller.handleUpdate(update(2, "/autostock_killswitch"));
        verify(notifier).notify(NoticeLevel.INFO, "킬스위치: 해제(주문 허용)");
        poller.handleUpdate(update(3, "/autostock_nothing"));
        verify(notifier).notify(NoticeLevel.INFO, "모르는 명령입니다. /autostock_help 를 보세요.");
        poller.handleUpdate(update(4, "/autostock_emergency_stop"));
        verify(killSwitch).engage("텔레그램 원격 명령");
        verifyNoMoreInteractions(notifier);
    }

    /** VALUNIVO 855: 앱 직접 명령 한 건마다 명령 이름·결과·답 글자 수를 한 줄 남긴다. 답 본문과 입력 글은 남기지 않는다. */
    @Test
    void botDirectCommandsLeaveOneResultLineWithoutText() {
        var killSwitch = mock(KillSwitch.class);
        var notifier = mock(Notifier.class);
        var props = new TelegramProperties(true, "synthetic-bot-token", "123");
        var stock = new TelegramStockCommands(killSwitch, new PositionBook(), new FixedClock());
        var poller = new TelegramCommandPoller(org.springframework.web.reactive.function.client.WebClient.builder(), props, killSwitch, new PositionBook(), notifier, new FixedClock());
        poller.stockCommands(stock);
        poller.appCommands(catalog, new AppCommandRunner(stock, killSwitch, mock(DashboardFacade.class)));
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AppCommandLog.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            poller.handleUpdate(update(1, "/autostock_help"));
            poller.handleUpdate(update(2, "/autostock_killswitch"));
            poller.handleUpdate(update(3, "/autostock_한글명령 비밀 아닌 인자"));
            poller.handleUpdate(update(4, "/autostock_emergency_stop"));
            doThrow(new IllegalStateException("boom")).when(notifier).notify(NoticeLevel.INFO, "킬스위치: 해제(주문 허용)");
            poller.handleUpdate(update(5, "/autostock_killswitch"));
            var lines = appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
            assertEquals(5, lines.size(), lines.toString());
            assertEquals("앱 명령 — route=app-bot command=help outcome=ok chars=" + catalog.help().length() + " notice=handed", lines.get(0));
            assertEquals("앱 명령 — route=app-bot command=killswitch_view outcome=ok chars=" + "킬스위치: 해제(주문 허용)".length() + " notice=handed", lines.get(1));
            assertEquals("앱 명령 — route=app-bot command=unknown outcome=unknown-command chars=" + "모르는 명령입니다. /autostock_help 를 보세요.".length() + " notice=handed", lines.get(2));
            assertTrue(lines.get(3).matches("앱 명령 — route=app-bot command=emergency_stop outcome=ok chars=[1-9]\\d* notice=skipped-kill-switch-alert"), lines.get(3));
            assertEquals("앱 명령 — route=app-bot command=killswitch_view outcome=error=IllegalStateException chars=0 notice=failure-notice", lines.get(4));
            assertTrue(lines.stream().noneMatch(l -> l.contains("한글명령") || l.contains("인자") || l.contains("킬스위치")), lines.toString());
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void logNamesKeepOnlySafeCharacters() {
        assertEquals("status_view", AppCommandLog.name("status_view"));
        assertEquals("other", AppCommandLog.name("status view"));
        assertEquals("other", AppCommandLog.name("한글"));
        assertEquals("other", AppCommandLog.name(null));
        assertEquals("-", AppCommandLog.request(null));
        assertEquals("-", AppCommandLog.request(" "));
        assertEquals("01JABCDEFGHJKMNPQRSTVWXYZ0", AppCommandLog.request("01JABCDEFGHJKMNPQRSTVWXYZ0"));
        assertEquals("other", AppCommandLog.request("id with space"));
    }

    static Map<String, Object> update(long id, String text) {
        return Map.of("update_id", id, "message", Map.of("chat", Map.of("id", 123), "text", text));
    }

    @Test
    void screenListShowsOnlyScreenCommandsAndRunsQueries() {
        var killSwitch = mock(KillSwitch.class);
        var runner = new AppCommandRunner(new TelegramStockCommands(killSwitch, new PositionBook(), new FixedClock()), killSwitch, mock(DashboardFacade.class));
        var screen = new AppCommandsController(catalog, runner);
        var items = screen.list();
        assertEquals(11, items.size());
        assertTrue(items.stream().allMatch(AppCommandsController.Item::runnable));
        var status = items.stream().filter(i -> i.id().equals("status_view")).findFirst().orElseThrow();
        assertEquals("/autostock_status", status.botName());
        assertEquals("/nivo_autostock_status", status.nivoName());
        assertEquals("ok", screen.run("killswitch_view", null).getBody().status());
        assertEquals(404, screen.run("emergency_stop", null).getStatusCode().value(), "변경 명령은 화면 목록에 없다");
    }

    @Test
    void springWiresTheSourceRunnerAndBothEntriesWhenPlatformCommandsAreOn() throws Exception {
        Path token = Files.writeString(dir.resolve("token"), "synthetic-app-token-0123456789\n");
        new ApplicationContextRunner()
                .withBean(AppCommandCatalog.class)
                .withBean(KillSwitch.class, () -> mock(KillSwitch.class))
                .withBean(DashboardFacade.class, () -> mock(DashboardFacade.class))
                .withBean(TelegramStockCommands.class, () -> new TelegramStockCommands(mock(KillSwitch.class), new PositionBook(), Clock.systemUTC()))
                .withBean(AppCommandRunner.class)
                .withBean(AppCommandsController.class)
                .withUserConfiguration(PlatformAppCommandController.class)
                .withPropertyValues("autostock.platform.commands.enabled=true", "autostock.platform.commands.token-file=" + token)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(PlatformAppCommandController.class));
                    assertNotNull(context.getBean(AppCommandsController.class));
                });
    }
}
