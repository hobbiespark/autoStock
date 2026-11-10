package com.autostock.monitor;

import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * VALUNIVO 3-4 C4(D338, 기획 12 5.2): 화면 "명령 목록". 목록은 명령 원천({@link AppCommandCatalog})의 {@code app_fe} 명령이고,
 * 조회 명령만 실행 부품({@link AppCommandRunner}, 화면 경로)으로 바로 실행한다. 변경 명령(긴급 정지·재개)은 실행 부품이 거절하고
 * 대시보드 킬스위치 카드로 안내한다(화면 확인 대화가 이미 있다). 다른 화면 API와 같은 로컬 화면용이며 거래·주문 권한은 없다.
 */
@RestController
@RequestMapping("/api/commands")
public class AppCommandsController {
    /** 화면용 명령 항목. 예상 평문은 플랫폼용 설명서({@code /api/app/v1/manifest})에만 있다(VALUNIVO 812·D339). */
    public record Item(String id, String name, String description, String risk, List<AppCommandCatalog.Arg> args,
                       String botName, String nivoName, boolean runnable) {}

    public record RunRequest(String text) {}

    private final AppCommandCatalog catalog;
    private final AppCommandRunner runner;

    public AppCommandsController(AppCommandCatalog catalog, AppCommandRunner runner) {
        this.catalog = Objects.requireNonNull(catalog);
        this.runner = Objects.requireNonNull(runner);
    }

    @GetMapping
    public List<Item> list() {
        return catalog.on("app_fe").stream()
                .map(c -> new Item(c.id(), c.name(), c.description(), c.risk(), c.args(),
                        c.entries().contains("app_bot") ? c.directName() : null,
                        "/nivo" + c.directName().substring(1).replaceFirst("^autostock_", "_autostock_"), c.query()))
                .toList();
    }

    @PostMapping("/{commandId}")
    public ResponseEntity<AppCommandRunner.Result> run(@PathVariable String commandId, @RequestBody(required = false) RunRequest body) {
        var command = catalog.find(commandId).filter(c -> c.entries().contains("app_fe"));
        if (command.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new AppCommandRunner.Result("failed", "unknown command"));
        var result = runner.run(commandId, body == null ? "" : body.text(), AppCommandRunner.Route.APP_FE);
        if (result == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new AppCommandRunner.Result("failed", "unknown command"));
        return ResponseEntity.status("refused".equals(result.status()) ? HttpStatus.CONFLICT : HttpStatus.OK).body(result);
    }
}
