package com.autostock.monitor;

import com.autostock.risk.KillSwitch;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * VALUNIVO 610(D300): 플랫폼(니보)이 전달하는 할 일의 표준 입구. 앱 연결 승인 = 할 일 실행 승인이고, 처리·위험 확인은 이 앱이 한다.
 * - {@code POST /api/app/v1/commands/{할일 ID}}, 루프백 요청만, {@code Authorization: Bearer <토큰 파일 값>}(일정 시간 비교).
 * - 답: {@code {"status":"ok|refused|failed","text":"…"}}. 플랫폼은 글을 자료로만 보인다.
 * - 거래 재개는 여기서 풀지 않는다. 확인 코드 절차가 있는 서명 경로(니보 {@code /nivo_autostock_trading_resume})를 안내한다.
 * - 아직 처리하지 못하는 할 일은 failed로 그렇다고 답한다(지어내지 않는다).
 * {@code autostock.platform.commands.enabled=true}일 때만 생긴다. 토큰·요청 본문은 로그에 남기지 않는다.
 */
@RestController
@RequestMapping("/api/app/v1/commands")
@ConditionalOnProperty(prefix = "autostock.platform.commands", name = "enabled", havingValue = "true")
public class PlatformAppCommandController {
    static final String RESUME_GUIDE = "거래 재개는 확인 코드가 필요해 여기서 풀지 않았습니다. 니보에서 /nivo_autostock_trading_resume 을 보내 확인 코드를 받으세요.";
    static final String NOT_YET = "autoStock 앱 입구가 아직 이 할 일을 처리하지 못합니다(후속 작업). 실행하지 않았습니다.";
    static final List<String> NOT_YET_COMMANDS = List.of("quote_view", "ipo_view", "daily_perf_view", "daily_report_view", "request_history_view", "decisions_view");

    public record Body(String request_id, String space, String command, String text) {}
    public record Result(String status, String text) {}

    private final TelegramStockCommands commands;
    private final KillSwitch killSwitch;
    private final DashboardFacade facade;
    private final byte[] expected;
    private final JsonMapper json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    public PlatformAppCommandController(TelegramStockCommands commands, KillSwitch killSwitch, DashboardFacade facade,
                                        @Value("${autostock.platform.commands.token-file}") String tokenFile) throws IOException {
        this.commands = Objects.requireNonNull(commands); this.killSwitch = Objects.requireNonNull(killSwitch); this.facade = Objects.requireNonNull(facade);
        String token = Files.readString(Path.of(tokenFile), StandardCharsets.UTF_8).strip();
        if (token.isEmpty() || token.chars().anyMatch(c -> c < 0x21 || c > 0x7e)) throw new IllegalArgumentException("invalid platform command token file");
        this.expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/{commandId}")
    public ResponseEntity<Result> run(@PathVariable String commandId, @RequestHeader(value = "Authorization", required = false) String authorization,
                                      @RequestBody(required = false) Body body, HttpServletRequest request) {
        if (!loopback(request.getRemoteAddr())) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new Result("refused", "loopback only"));
        if (authorization == null || !MessageDigest.isEqual(expected, authorization.getBytes(StandardCharsets.UTF_8)))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new Result("refused", "unauthorized"));
        Result result = switch (commandId) {
            case "status_view" -> new Result("ok", commands.status().reply());
            case "emergency_stop" -> { var reply = commands.stop(); yield new Result(reply.succeeded() ? "ok" : "failed", reply.reply()); }
            case "trading_resume" -> new Result("refused", RESUME_GUIDE);
            case "killswitch_view" -> new Result("ok", killSwitch.isEngaged() ? "킬스위치: 작동 중(신규 주문 막힘)" : "킬스위치: 해제(주문 허용)");
            case "positions_view" -> new Result("ok", facade.positions().isEmpty() ? "보유 종목이 없습니다." : "보유 종목\n" + pretty(facade.positions()));
            case "today_pnl_view" -> new Result("ok", "계좌·손익\n" + pretty(accountAndTrading()));
            case "events_view" -> {
                var events = facade.recentEvents();
                yield new Result("ok", events.isEmpty() ? "최근 신호·체결이 없습니다." : "최근 신호·체결\n" + pretty(events.subList(0, Math.min(10, events.size()))));
            }
            case "quote_view", "ipo_view", "daily_perf_view", "daily_report_view", "request_history_view", "decisions_view" -> readOnly(commandId, body == null ? "" : body.text());
            default -> null;
        };
        if (result == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new Result("failed", "unknown command"));
        return ResponseEntity.ok(result);
    }

    // VALUNIVO 611: read-only commands reuse the existing screens' beans. Optional so direct construction in tests still works.
    private DashboardController dashboard;
    private PerformanceController performance;
    private OrderHistoryController orderHistory;
    private DecisionController decisions;
    private DailyReportScheduler dailyReport;
    private com.autostock.ipo.IpoRecheckService ipo;
    @org.springframework.beans.factory.annotation.Autowired(required = false) void dashboard(DashboardController value) { this.dashboard = value; }
    @org.springframework.beans.factory.annotation.Autowired(required = false) void performance(PerformanceController value) { this.performance = value; }
    @org.springframework.beans.factory.annotation.Autowired(required = false) void orderHistory(OrderHistoryController value) { this.orderHistory = value; }
    @org.springframework.beans.factory.annotation.Autowired(required = false) void decisions(DecisionController value) { this.decisions = value; }
    @org.springframework.beans.factory.annotation.Autowired(required = false) void dailyReport(DailyReportScheduler value) { this.dailyReport = value; }
    @org.springframework.beans.factory.annotation.Autowired(required = false) void ipo(com.autostock.ipo.IpoRecheckService value) { this.ipo = value; }

    static final java.util.regex.Pattern SYMBOL = java.util.regex.Pattern.compile("(?<![0-9A-Z])([0-9A-Z]{6})(?![0-9A-Z])");
    static final java.util.regex.Pattern DATE = java.util.regex.Pattern.compile("(20[0-9]{2}-[01][0-9]-[0-3][0-9])");
    static final int MAX_ROWS = 20;

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

    static boolean loopback(String address) {
        if (address == null) return false;
        try { return InetAddress.getByName(address).isLoopbackAddress(); } catch (UnknownHostException e) { return false; }
    }
}
