package com.autostock.monitor;

import com.autostock.common.event.Side;
import com.autostock.common.event.Signal;
import com.autostock.monitor.view.DashboardView;
import com.autostock.monitor.view.PositionView;
import com.autostock.risk.KillSwitch;
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
 * 대시보드 REST API (경량 FE 백엔드, PLAN 4-1절 / ARCHITECTURE.md 10절 CQRS Lite).
 *
 * <p>조회(Query)는 전부 {@link DashboardFacade}를 거쳐 View DTO로 반환한다 — Domain Entity나
 * Map을 그대로 노출하지 않는다(ARCHITECTURE.md 설계 규칙 13·14·15). 킬스위치 조작·테스트
 * 시그널처럼 "운영 제어" 성격의 Command는 여전히 risk.KillSwitch를 직접 호출한다 — 조회와
 * 달리 상태를 바꾸는 동작이지만, 이 컨트롤러 자체가 곧 그 Command의 Application Service
 * 역할을 하는 경량 구조라 별도 서비스 계층을 새로 두지 않았다(기존 구조 유지).
 *
 * <p>경계 규칙: 매매 흐름 개입은 반드시 이벤트로만 한다 — 테스트 시그널도 Signal 이벤트를
 * 발행할 뿐, RiskGate를 우회하지 않는다.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardFacade facade;
    private final KillSwitch killSwitch;
    private final ApplicationEventPublisher publisher;

    public DashboardController(DashboardFacade facade,
                               KillSwitch killSwitch,
                               ApplicationEventPublisher publisher) {
        this.facade = facade;
        this.killSwitch = killSwitch;
        this.publisher = publisher;
    }

    /**
     * 대시보드 단일 조회 — 포지션·최근 이벤트·매매 상태·시스템 상태를 한 번에 반환한다
     * (ARCHITECTURE.md 10절 "대시보드는 GET /api/dashboard 하나로 조합"). FE는 이 엔드포인트
     * 하나만 2초 주기로 폴링해도 화면 전체를 채울 수 있다.
     */
    @GetMapping
    public DashboardView dashboard() {
        return facade.dashboard();
    }

    /** 포지션 현황 — 기존 개별 엔드포인트 유지(내부는 Facade/View DTO 사용, Map 반환 제거). */
    @GetMapping("/positions")
    public List<PositionView> positions() {
        return facade.positions();
    }

    /** 최근 이벤트 피드 (최신순 최대 100건). */
    @GetMapping("/events")
    public List<EventFeed.FeedItem> events() {
        return facade.recentEvents();
    }

    /** 킬스위치 상태. */
    @GetMapping("/killswitch")
    public KillSwitchView killSwitchStatus() {
        return new KillSwitchView(killSwitch.isEngaged());
    }

    /** 킬스위치 토글 — engage=true: 비상 정지, false: 해제. */
    @PostMapping("/killswitch")
    public KillSwitchView toggleKillSwitch(@RequestBody Map<String, Object> body) {
        boolean engage = Boolean.TRUE.equals(body.get("engage"));
        if (engage) {
            killSwitch.engage("대시보드 수동 조작");
        } else {
            killSwitch.release("dashboard");
        }
        return new KillSwitchView(killSwitch.isEngaged());
    }

    /**
     * 테스트 시그널 발행 — SIM 루프 수동 검증용 (paper 전용).
     * 흐름: 여기서 Signal 발행 → RiskGate 검사 → OrderRequest → SIM 체결 → Fill
     * → PositionBook/EventFeed 갱신. 즉 실제 매매와 완전히 같은 경로를 탄다.
     */
    @PostMapping("/test-signal")
    public TestSignalResponse testSignal(@RequestBody TestSignalRequest request) {
        publisher.publishEvent(new Signal(
                "dashboard-manual",
                request.symbol(),
                Side.valueOf(request.side()),
                new BigDecimal(request.price()),
                1.0,
                Instant.now()));
        return new TestSignalResponse(true);
    }

    /** 테스트 시그널 입력값. side: "BUY" | "SELL" */
    public record TestSignalRequest(String symbol, String side, String price) {
    }

    /** 테스트 시그널 응답. */
    public record TestSignalResponse(boolean accepted) {
    }

    /** 킬스위치 상태 응답. */
    public record KillSwitchView(boolean engaged) {
    }
}
