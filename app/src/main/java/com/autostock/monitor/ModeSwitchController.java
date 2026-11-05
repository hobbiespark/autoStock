package com.autostock.monitor;

import com.autostock.risk.KillSwitch;
import com.autostock.trading.OpenOrders;
import com.autostock.trading.OrderRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * VALUNIVO 612(D301): 모의(paper)↔실전(live) 전환. 화면 버튼과 확인 문구로만 한다(니보 명령 없음).
 * - 실행 중 키를 바꾸지 않는다. 원하는 모드를 운영 설정 파일에 쓰고 안전 종료하면, 도우미가 앱이 내려간 뒤 기존 기동 도구로 다시 띄운다.
 *   기동 준비(load_env.ps1)가 그 파일로 프로필과 접속 DB(실전은 DB_NAME_live)를 정한다(한 프로세스 한 모드, 실전 기록 분리).
 * - 전환 전 확인: 루프백, 확인 문구, 미체결 주문 0건. 전환 때 매매를 멈추고 킬스위치를 켠다(DB에 저장돼 재기동 뒤에도 켜진 채 시작).
 * - 전환은 데브가 화면에서 누르는 동작이다(D260 ② 실제 돈). 설정(mode-file·restart-script)이 없으면 전환하지 않는다.
 */
@RestController
@RequestMapping("/api/dashboard/mode")
public class ModeSwitchController {
    public static final String CONFIRM_LIVE = "실전 전환";
    public static final String CONFIRM_PAPER = "모의 전환";
    private static final Logger log = LoggerFactory.getLogger(ModeSwitchController.class);

    public record ModeView(String mode, boolean switchable) {}
    public record SwitchRequest(String target, String confirm) {}
    public record SwitchResult(boolean accepted, String message) {}

    /** 재기동 도우미 실행과 앱 종료(시험에서 바꿔 끼운다). */
    public interface Restarter { void restartAfterExit(String script) throws IOException; void exitSoon(); }

    private final Environment environment;
    private final OrderRepository orders;
    private final KillSwitch killSwitch;
    private final TradingSystemManager trading;
    private final String modeFile;
    private final String restartScript;
    private Restarter restarter;

    public ModeSwitchController(Environment environment, OrderRepository orders, KillSwitch killSwitch, TradingSystemManager trading,
                                ConfigurableApplicationContext context,
                                @Value("${autostock.mode-switch.mode-file:}") String modeFile,
                                @Value("${autostock.mode-switch.restart-script:}") String restartScript) {
        this.environment = Objects.requireNonNull(environment); this.orders = Objects.requireNonNull(orders);
        this.killSwitch = Objects.requireNonNull(killSwitch); this.trading = Objects.requireNonNull(trading);
        this.modeFile = modeFile == null ? "" : modeFile.strip(); this.restartScript = restartScript == null ? "" : restartScript.strip();
        this.restarter = new Restarter() {
            @Override public void restartAfterExit(String script) throws IOException {
                new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script).start();
            }
            @Override public void exitSoon() {
                Thread exit = new Thread(() -> {
                    try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    System.exit(SpringApplication.exit(context, () -> 0));
                }, "mode-switch-exit");
                exit.setDaemon(false);
                exit.start();
            }
        };
    }

    void restarter(Restarter value) { this.restarter = Objects.requireNonNull(value); }

    String current() { return environment.acceptsProfiles(Profiles.of("live")) ? "live" : "paper"; }

    @GetMapping
    public ModeView mode() { return new ModeView(current(), !modeFile.isEmpty() && !restartScript.isEmpty()); }

    @PostMapping
    public ResponseEntity<SwitchResult> switchMode(@RequestBody(required = false) SwitchRequest request, HttpServletRequest http) {
        if (!PlatformAppCommandController.loopback(http.getRemoteAddr())) return refuse(HttpStatus.FORBIDDEN, "이 PC에서만 전환할 수 있습니다.");
        if (modeFile.isEmpty() || restartScript.isEmpty()) return refuse(HttpStatus.SERVICE_UNAVAILABLE, "전환 설정(mode-file·restart-script)이 없어 전환하지 않았습니다.");
        String target = request == null || request.target() == null ? "" : request.target().strip();
        if (!target.equals("live") && !target.equals("paper")) return refuse(HttpStatus.BAD_REQUEST, "전환 대상은 live 또는 paper입니다.");
        if (target.equals(current())) return refuse(HttpStatus.CONFLICT, "이미 " + (target.equals("live") ? "실전" : "모의") + " 모드입니다.");
        String expected = target.equals("live") ? CONFIRM_LIVE : CONFIRM_PAPER;
        if (request.confirm() == null || !request.confirm().strip().equals(expected)) return refuse(HttpStatus.BAD_REQUEST, "확인 문구 '" + expected + "'를 정확히 입력해 주세요.");
        int open = orders.findByStatusIn(OpenOrders.STATUSES).size();
        if (open > 0) return refuse(HttpStatus.CONFLICT, "미체결 주문이 " + open + "건 있어 전환하지 않았습니다. 정리한 뒤 다시 시도해 주세요.");
        try { trading.stop(); } catch (IllegalStateException alreadyStopped) { /* 이미 멈춰 있으면 그대로 */ }
        killSwitch.engage("모드 전환(" + current() + " → " + target + ")");
        try {
            Path file = Path.of(modeFile), temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.createDirectories(file.getParent());
            Files.writeString(temp, "{\"mode\":\"" + target + "\"}", StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            restarter.restartAfterExit(restartScript);
        } catch (IOException | RuntimeException e) {
            log.warn("모드 전환 준비 실패 — 앱은 그대로 둔다(킬스위치 켜짐): {}", e.getClass().getSimpleName());
            return refuse(HttpStatus.INTERNAL_SERVER_ERROR, "전환 준비에 실패해 그대로 두었습니다(킬스위치 켜짐).");
        }
        log.warn("모드 전환 요청 수락: {} → {} (매매 정지·킬스위치 켬, 곧 재기동)", current(), target);
        restarter.exitSoon();
        return ResponseEntity.accepted().body(new SwitchResult(true, (target.equals("live") ? "실전" : "모의")
            + " 모드로 다시 시작합니다. 킬스위치가 켜진 채 시작하니, 확인 뒤 해제해야 주문이 나갑니다."));
    }

    private static ResponseEntity<SwitchResult> refuse(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new SwitchResult(false, message));
    }
}
