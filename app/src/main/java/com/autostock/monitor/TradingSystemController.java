package com.autostock.monitor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Supplier;

/**
 * 자동매매 시작/정지 Command API (ARCHITECTURE.md 10절).
 *
 * <p>응답은 항상 명령을 받은 직후의 전이 상태(STARTING/STOPPING)다. 실제로 RUNNING/STOPPED에
 * 도달했는지는 {@link TradingSystemManager}가 백그라운드에서 마저 진행하므로, FE는
 * {@code GET /api/dashboard}를 재조회해서 확인해야 한다 — <b>Backend가 Source of Truth</b>다.
 * 현재 상태에서 허용되지 않는 명령(예: 이미 RUNNING인데 start)은 409로 응답한다.
 */
@RestController
@RequestMapping("/api/trading")
public class TradingSystemController {

    private final TradingSystemManager tradingSystemManager;

    public TradingSystemController(TradingSystemManager tradingSystemManager) {
        this.tradingSystemManager = tradingSystemManager;
    }

    /** 시작 명령 — STOPPED에서만 허용. */
    @PostMapping("/start")
    public ResponseEntity<StatusResponse> start() {
        return respond(tradingSystemManager::start);
    }

    /** 정지 명령 — RUNNING/DEGRADED에서만 허용. */
    @PostMapping("/stop")
    public ResponseEntity<StatusResponse> stop() {
        return respond(tradingSystemManager::stop);
    }

    /** 명령 실행 공통 처리 — 불법 전이(IllegalStateException)는 409 + 현재 상태로 응답한다. */
    private ResponseEntity<StatusResponse> respond(Supplier<TradingSystemStatus> command) {
        try {
            return ResponseEntity.ok(new StatusResponse(command.get()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new StatusResponse(tradingSystemManager.status()));
        }
    }

    /** 응답 바디: {"status": "STARTING"} 형태. */
    public record StatusResponse(TradingSystemStatus status) {
    }
}
