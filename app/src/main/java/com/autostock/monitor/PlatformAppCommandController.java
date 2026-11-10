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
import java.util.Objects;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * VALUNIVO 610(D300): 플랫폼(니보)이 전달하는 할 일의 표준 입구. 앱 연결 승인 = 할 일 실행 승인이고, 처리·위험 확인은 이 앱이 한다.
 * - {@code POST /api/app/v1/commands/{할일 ID}}, 루프백 요청만, {@code Authorization: Bearer <토큰 파일 값>}(일정 시간 비교).
 * - 답: {@code {"status":"ok|refused|failed","text":"…"}}. 플랫폼은 글을 자료로만 보인다.
 * - 거래 재개는 여기서 풀지 않는다. 확인 코드 절차가 있는 서명 경로(니보 {@code /nivo_autostock_trading_resume})를 안내한다.
 * - 아직 처리하지 못하는 할 일은 failed로 그렇다고 답한다(지어내지 않는다).
 * VALUNIVO 3-4 C3(D338): 처리는 {@link AppCommandRunner}(봇·화면과 같은 부품)가 하고, 이 입구는 인증·판 확인만 한다.
 * {@code GET /api/app/v1/manifest}는 명령 원천({@link AppCommandCatalog})의 설명서 1.1을 같은 인증으로 낸다.
 * 본문의 {@code command_version}이 원천의 판과 다르면 실행하지 않고 거절한다(옛 버튼 보호, 기획 12 5.4).
 * {@code autostock.platform.commands.enabled=true}일 때만 생긴다. 토큰·요청 본문은 로그에 남기지 않는다.
 */
@RestController
@RequestMapping("/api/app/v1")
@ConditionalOnProperty(prefix = "autostock.platform.commands", name = "enabled", havingValue = "true")
public class PlatformAppCommandController {
    static final String RESUME_GUIDE = AppCommandRunner.RESUME_GUIDE;
    static final String NOT_YET = AppCommandRunner.NOT_YET;
    static final String VERSION_CHANGED = "명령 판이 바뀌었습니다. /nivo_autostock_help 를 보세요. 실행하지 않았습니다.";
    static final List<String> NOT_YET_COMMANDS = List.of("quote_view", "ipo_view", "daily_perf_view", "daily_report_view", "request_history_view", "decisions_view");

    /** {@code command_version}은 VALUNIVO 3-4 C2(797)부터 플랫폼이 판을 선언한 명령에만 싣는다(없으면 null). */
    public record Body(String request_id, String space, String command, String text, Integer command_version) {}
    public record Result(String status, String text) {}

    private final AppCommandRunner runner;
    private final AppCommandCatalog catalog;
    private final byte[] expected;

    @Autowired
    public PlatformAppCommandController(AppCommandRunner runner, AppCommandCatalog catalog,
                                        @Value("${autostock.platform.commands.token-file}") String tokenFile) throws IOException {
        this.runner = Objects.requireNonNull(runner);
        this.catalog = Objects.requireNonNull(catalog);
        String token = Files.readString(Path.of(tokenFile), StandardCharsets.UTF_8).strip();
        if (token.isEmpty() || token.chars().anyMatch(c -> c < 0x21 || c > 0x7e)) throw new IllegalArgumentException("invalid platform command token file");
        this.expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    /** 시험용: 실행 부품과 원천을 직접 만든다. */
    PlatformAppCommandController(TelegramStockCommands commands, KillSwitch killSwitch, DashboardFacade facade, String tokenFile) throws IOException {
        this(new AppCommandRunner(commands, killSwitch, facade), new AppCommandCatalog(), tokenFile);
    }

    @PostMapping("/commands/{commandId}")
    public ResponseEntity<Result> run(@PathVariable String commandId, @RequestHeader(value = "Authorization", required = false) String authorization,
                                      @RequestBody(required = false) Body body, HttpServletRequest request) {
        var denied = denied(authorization, request);
        if (denied != null) return ResponseEntity.status(denied.getStatusCode()).body(denied.getBody());
        var command = catalog.find(commandId);
        if (command.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new Result("failed", "unknown command"));
        if (body != null && body.command_version() != null && body.command_version() != command.get().version())
            return ResponseEntity.ok(new Result("refused", VERSION_CHANGED));
        var result = runner.run(commandId, body == null ? "" : body.text(), AppCommandRunner.Route.PLATFORM);
        if (result == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new Result("failed", "unknown command"));
        return ResponseEntity.ok(new Result(result.status(), result.text()));
    }

    /** VALUNIVO 3-4 C3: 플랫폼 등록부의 {@code manifest_source: remote}가 읽는 설명서(원천 그대로). */
    @GetMapping("/manifest")
    public ResponseEntity<?> manifest(@RequestHeader(value = "Authorization", required = false) String authorization, HttpServletRequest request) {
        var denied = denied(authorization, request);
        if (denied != null) return denied;
        JsonNode manifest = catalog.manifest();
        return ResponseEntity.ok(manifest);
    }

    private ResponseEntity<Result> denied(String authorization, HttpServletRequest request) {
        if (!loopback(request.getRemoteAddr())) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new Result("refused", "loopback only"));
        if (authorization == null || !MessageDigest.isEqual(expected, authorization.getBytes(StandardCharsets.UTF_8)))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new Result("refused", "unauthorized"));
        return null;
    }

    /** 시험용: 읽기 전용 할 일(처리는 실행 부품). */
    Result readOnly(String commandId, String text) {
        var result = runner.readOnly(commandId, text);
        return result == null ? null : new Result(result.status(), result.text());
    }

    // 시험용 위임(스프링에서는 실행 부품이 직접 주입받는다).
    void dashboard(DashboardController value) { runner.dashboard(value); }
    void performance(PerformanceController value) { runner.performance(value); }
    void orderHistory(OrderHistoryController value) { runner.orderHistory(value); }
    void decisions(DecisionController value) { runner.decisions(value); }
    void dailyReport(DailyReportScheduler value) { runner.dailyReport(value); }
    void ipo(com.autostock.ipo.IpoRecheckService value) { runner.ipo(value); }

    static boolean loopback(String address) {
        if (address == null) return false;
        try { return InetAddress.getByName(address).isLoopbackAddress(); } catch (UnknownHostException e) { return false; }
    }
}
