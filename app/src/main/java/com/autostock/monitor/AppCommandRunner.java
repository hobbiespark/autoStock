package com.autostock.monitor;

import com.autostock.risk.KillSwitch;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * VALUNIVO 3-4 C3·C4(D338, 기획 12 5.2·7장): autoStock 할 일 13개의 <b>실행 하나</b>. 플랫폼 입구({@link PlatformAppCommandController}),
 * 앱 봇 직접 명령({@link TelegramCommandPoller}), 화면 명령 목록({@link AppCommandsController})이 같은 부품을 거친다(두 경로 같음).
 * 처리 내용은 VALUNIVO 610·611의 플랫폼 입구에서 그대로 옮겼다. 경로마다 다른 것은 위험 확인 방식뿐이다(N-4: 위험 확인은 앱이 한다).
 * <ul>
 *   <li>거래 재개: 플랫폼은 여기서 풀지 않고 니보 서명 경로를 안내, 앱 봇은 60초 4자리 확인 코드 절차({@link TelegramStockCommands}),
 *       화면은 킬스위치 카드로 안내.</li>
 *   <li>긴급 정지: 플랫폼·앱 봇은 즉시(안전 방향). 화면은 킬스위치 카드로 안내(화면 확인 대화가 이미 있다).</li>
 *   <li>화면은 조회만 실행한다.</li>
 * </ul>
 * 거래·주문 권한은 없다. 응답 글만 돌려주고 보내지 않는다(보내는 쪽은 호출자).
 */
@Component
public class AppCommandRunner {
    static final String RESUME_GUIDE = "거래 재개는 확인 코드가 필요해 여기서 풀지 않았습니다. 니보에서 /nivo_autostock_trading_resume 을 보내 확인 코드를 받으세요.";
    static final String FE_CHANGE_GUIDE = "변경 명령은 이 목록에서 실행하지 않습니다. 대시보드의 킬스위치 카드에서 확인 뒤 바꾸세요.";
    static final String NOT_YET = "autoStock 앱 입구가 아직 이 할 일을 처리하지 못합니다(후속 작업). 실행하지 않았습니다.";
    /** 플랫폼·봇 응답에서 앱 봇 재개 명령 이름. */
    static final String BOT_RESUME = AppCommandCatalog.DIRECT_PREFIX + "trading_resume";

    /** 이 부품이 처리하는 할 일 ID. 원천({@link AppCommandCatalog})의 명령 집합과 같아야 한다(시험이 단정, 기획 12 7장). */
    public static final Set<String> HANDLED = Set.of("status_view", "emergency_stop", "trading_resume", "killswitch_view", "positions_view",
            "today_pnl_view", "events_view", "quote_view", "ipo_view", "daily_perf_view", "daily_report_view", "request_history_view",
            "decisions_view");
    /** 변경 할 일(화면 명령 목록에서는 실행하지 않는다). */
    static final Set<String> CHANGES = Set.of("emergency_stop", "trading_resume");
    static final Set<String> READ_ONLY = Set.of("quote_view", "ipo_view", "daily_perf_view", "daily_report_view", "request_history_view", "decisions_view");

    /** 할 일을 받은 경로. */
    public enum Route { PLATFORM, APP_BOT, APP_FE }

    public record Result(String status, String text) {
        public boolean ok() { return "ok".equals(status); }
    }

    private final TelegramStockCommands commands;
    private final KillSwitch killSwitch;
    private final DashboardFacade facade;
    private final JsonMapper json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    public AppCommandRunner(TelegramStockCommands commands, KillSwitch killSwitch, DashboardFacade facade) {
        this.commands = Objects.requireNonNull(commands);
        this.killSwitch = Objects.requireNonNull(killSwitch);
        this.facade = Objects.requireNonNull(facade);
    }

    // VALUNIVO 611: read-only commands reuse the existing screens' beans. Optional so direct construction in tests still works.
    private DashboardController dashboard;
    private PerformanceController performance;
    private OrderHistoryController orderHistory;
    private DecisionController decisions;
    private DailyReportScheduler dailyReport;
    private com.autostock.ipo.IpoRecheckService ipo;
    @Autowired(required = false) void dashboard(DashboardController value) { this.dashboard = value; }
    @Autowired(required = false) void performance(PerformanceController value) { this.performance = value; }
    @Autowired(required = false) void orderHistory(OrderHistoryController value) { this.orderHistory = value; }
    @Autowired(required = false) void decisions(DecisionController value) { this.decisions = value; }
    @Autowired(required = false) void dailyReport(DailyReportScheduler value) { this.dailyReport = value; }
    @Autowired(required = false) void ipo(com.autostock.ipo.IpoRecheckService value) { this.ipo = value; }

    static final java.util.regex.Pattern SYMBOL = java.util.regex.Pattern.compile("(?<![0-9A-Z])([0-9A-Z]{6})(?![0-9A-Z])");
    static final java.util.regex.Pattern DATE = java.util.regex.Pattern.compile("(20[0-9]{2}-[01][0-9]-[0-3][0-9])");
    static final java.util.regex.Pattern RESUME_CODE = java.util.regex.Pattern.compile("^(\\d{4})$");
    static final int MAX_ROWS = 20;

    /** 할 일 하나를 실행한다. 모르는 할 일이면 null. */
    public Result run(String commandId, String text, Route route) {
        Objects.requireNonNull(route);
        String input = text == null ? "" : text.strip();
        if (route == Route.APP_FE && CHANGES.contains(commandId)) return new Result("refused", FE_CHANGE_GUIDE);
        return switch (commandId) {
            case "status_view" -> new Result("ok", commands.status().reply());
            case "emergency_stop" -> { var reply = commands.stop(); yield new Result(reply.succeeded() ? "ok" : "failed", reply.reply()); }
            case "trading_resume" -> resume(input, route);
            case "killswitch_view" -> new Result("ok", killSwitch.isEngaged() ? "킬스위치: 작동 중(신규 주문 막힘)" : "킬스위치: 해제(주문 허용)");
            case "positions_view" -> new Result("ok", facade.positions().isEmpty() ? "보유 종목이 없습니다." : "보유 종목\n" + pretty(facade.positions()));
            case "today_pnl_view" -> new Result("ok", "계좌·손익\n" + pretty(accountAndTrading()));
            case "events_view" -> {
                var events = facade.recentEvents();
                yield new Result("ok", events.isEmpty() ? "최근 신호·체결이 없습니다." : "최근 신호·체결\n" + pretty(events.subList(0, Math.min(10, events.size()))));
            }
            case "quote_view", "ipo_view", "daily_perf_view", "daily_report_view", "request_history_view", "decisions_view" -> readOnly(commandId, input);
            default -> null;
        };
    }

    /** 재개: 플랫폼은 안내만(서명 경로), 앱 봇은 확인 코드 절차(첫 번째는 코드 발급, 코드와 함께 보내면 확인). */
    private Result resume(String input, Route route) {
        if (route != Route.APP_BOT) return new Result("refused", RESUME_GUIDE);
        var code = RESUME_CODE.matcher(input);
        var reply = code.matches() ? commands.confirmResume(code.group(1), BOT_RESUME) : commands.requestResume(BOT_RESUME);
        return new Result(reply.succeeded() ? "ok" : "refused", reply.reply());
    }

    /** VALUNIVO 611: 읽기 전용 할 일. 화면용 빈을 그대로 부르고, 스프링 검증이 돌지 않으므로 인자를 직접 확인한다. 실패는 failed로 답한다. */
    Result readOnly(String commandId, String text) {
        String input = text == null ? "" : text.toUpperCase(java.util.Locale.ROOT);
        try {
            switch (commandId) {
                case "quote_view" -> {
                    if (dashboard == null) return new Result("failed", NOT_YET);
                    var m = SYMBOL.matcher(input);
                    if (!m.find()) return new Result("refused", "종목 코드 6자리를 함께 보내 주세요(예: 005930 시세 알려줘).");
                    var quote = dashboard.quote(m.group(1));
                    if (quote.currentPrice() == null) return new Result("failed", m.group(1) + " 시세를 받지 못했습니다. 잠시 뒤 다시 시도해 주세요.");
                    return new Result("ok", "현재 시세\n" + pretty(quote));
                }
                case "ipo_view" -> { return ipo == null ? new Result("failed", NOT_YET) : new Result("ok", ipo.reply("/ipo")); }
                case "daily_perf_view" -> {
                    if (performance == null) return new Result("failed", NOT_YET);
                    var rows = performance.daily(7);
                    return new Result("ok", rows.isEmpty() ? "최근 7일 실적 기록이 없습니다." : "최근 7일 실적\n" + pretty(rows));
                }
                case "daily_report_view" -> {
                    if (dailyReport == null) return new Result("failed", NOT_YET);
                    StringBuilder out = new StringBuilder("일일 리포트(지금 값으로 다시 만든 것이며 마감 발송본과 다를 수 있습니다)\n");
                    out.append(pretty(accountAndTrading()));
                    for (String line : dailyReport.strategyLines()) out.append('\n').append(line);
                    for (String line : dailyReport.cumulativeLines()) out.append('\n').append(line);
                    return new Result("ok", out.toString());
                }
                case "request_history_view" -> {
                    if (orderHistory == null) return new Result("failed", NOT_YET);
                    var orders = orderHistory.orders(7).orders();
                    if (orders.isEmpty()) return new Result("ok", "최근 7일 매매 요청이 없습니다.");
                    String more = orders.size() > MAX_ROWS ? "\n외 " + (orders.size() - MAX_ROWS) + "건" : "";
                    return new Result("ok", "최근 7일 매매 요청(최신순)\n" + pretty(orders.subList(0, Math.min(MAX_ROWS, orders.size()))) + more);
                }
                case "decisions_view" -> {
                    if (decisions == null) return new Result("failed", NOT_YET);
                    var m = DATE.matcher(input);
                    java.time.LocalDate date = null;
                    if (m.find()) {
                        try { date = java.time.LocalDate.parse(m.group(1)); }
                        catch (java.time.format.DateTimeParseException e) { return new Result("refused", "날짜는 2026-11-05 형식으로 보내 주세요."); }
                    }
                    var rows = decisions.decisions(date);
                    if (rows.isEmpty()) return new Result("ok", (date == null ? "오늘" : date.toString()) + " 판단 기록이 없습니다.");
                    String more = rows.size() > MAX_ROWS ? "\n외 " + (rows.size() - MAX_ROWS) + "건" : "";
                    return new Result("ok", (date == null ? "오늘" : date.toString()) + " 판단 기록\n" + pretty(rows.subList(0, Math.min(MAX_ROWS, rows.size()))) + more);
                }
                default -> { return null; }
            }
        } catch (RuntimeException e) {
            return new Result("failed", "조회 중 오류가 나 처리하지 못했습니다(" + e.getClass().getSimpleName() + ").");
        }
    }

    /** 611: 자료가 비어도(null) 오류 없이 보이게 순서 있는 맵으로 담는다(Map.of는 null을 받지 않는다). */
    private Map<String, Object> accountAndTrading() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("account", facade.account()); out.put("trading", facade.trading());
        return out;
    }

    private String pretty(Object value) {
        try { return json.writeValueAsString(value); } catch (RuntimeException e) { return String.valueOf(value); }
    }
}
