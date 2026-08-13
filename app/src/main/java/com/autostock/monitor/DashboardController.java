package com.autostock.monitor;

import com.autostock.common.event.Side;
import com.autostock.common.event.Signal;
import com.autostock.risk.KillSwitch;
import com.autostock.risk.PositionBook;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 대시보드 REST API (경량 FE 백엔드, PLAN 4-1절).
 *
 * <p>경계 규칙: 조회/운영 제어는 타 모듈의 공개 API(PositionBook, KillSwitch)
 * 직접 참조를 허용하지만, <b>매매 흐름 개입은 반드시 이벤트로만</b> 한다 —
 * 테스트 시그널도 Signal 이벤트를 발행할 뿐, RiskGate를 우회하지 않는다.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final PositionBook positionBook;
    private final KillSwitch killSwitch;
    private final EventFeed eventFeed;
    private final ApplicationEventPublisher publisher;

    public DashboardController(PositionBook positionBook,
                               KillSwitch killSwitch,
                               EventFeed eventFeed,
                               ApplicationEventPublisher publisher) {
        this.positionBook = positionBook;
        this.killSwitch = killSwitch;
        this.eventFeed = eventFeed;
        this.publisher = publisher;
    }

    /** 포지션 현황 — PositionBook 스냅샷. */
    @GetMapping("/positions")
    public List<Map<String, Object>> positions() {
        return positionBook.snapshot().entrySet().stream()
                .map(e -> Map.<String, Object>of(
                        "symbol", e.getKey(),
                        "quantity", e.getValue().quantity(),
                        "avgPrice", e.getValue().avgPrice()))
                .toList();
    }

    /** 최근 이벤트 피드 (최신순 최대 100건). */
    @GetMapping("/events")
    public List<EventFeed.FeedItem> events() {
        return eventFeed.recent();
    }

    /** 킬스위치 상태. */
    @GetMapping("/killswitch")
    public Map<String, Object> killSwitchStatus() {
        return Map.of("engaged", killSwitch.isEngaged());
    }

    /** 킬스위치 토글 — engage=true: 비상 정지, false: 해제. */
    @PostMapping("/killswitch")
    public Map<String, Object> toggleKillSwitch(@RequestBody Map<String, Object> body) {
        boolean engage = Boolean.TRUE.equals(body.get("engage"));
        if (engage) {
            killSwitch.engage("대시보드 수동 조작");
        } else {
            killSwitch.release("dashboard");
        }
        return Map.of("engaged", killSwitch.isEngaged());
    }

    /**
     * 테스트 시그널 발행 — SIM 루프 수동 검증용 (paper 전용).
     * 흐름: 여기서 Signal 발행 → RiskGate 검사 → OrderRequest → SIM 체결 → Fill
     * → PositionBook/EventFeed 갱신. 즉 실제 매매와 완전히 같은 경로를 탄다.
     */
    @PostMapping("/test-signal")
    public Map<String, Object> testSignal(@RequestBody TestSignalRequest request) {
        publisher.publishEvent(new Signal(
                "dashboard-manual",
                request.symbol(),
                Side.valueOf(request.side()),
                new BigDecimal(request.price()),
                1.0,
                Instant.now()));
        return Map.of("accepted", true);
    }

    /** 테스트 시그널 입력값. side: "BUY" | "SELL" */
    public record TestSignalRequest(String symbol, String side, String price) {
    }
}
