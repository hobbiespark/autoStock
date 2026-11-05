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
            case "today_pnl_view" -> new Result("ok", "계좌·손익\n" + pretty(Map.of("account", facade.account(), "trading", facade.trading())));
            case "events_view" -> {
                var events = facade.recentEvents();
                yield new Result("ok", events.isEmpty() ? "최근 신호·체결이 없습니다." : "최근 신호·체결\n" + pretty(events.subList(0, Math.min(10, events.size()))));
            }
            default -> NOT_YET_COMMANDS.contains(commandId) ? new Result("failed", NOT_YET) : null;
        };
        if (result == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new Result("failed", "unknown command"));
        return ResponseEntity.ok(result);
    }

    private String pretty(Object value) {
        try { return json.writeValueAsString(value); } catch (RuntimeException e) { return String.valueOf(value); }
    }

    static boolean loopback(String address) {
        if (address == null) return false;
        try { return InetAddress.getByName(address).isLoopbackAddress(); } catch (UnknownHostException e) { return false; }
    }
}
