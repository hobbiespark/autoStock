package com.autostock.monitor;

import com.autostock.common.event.CancelRequest;
import com.autostock.common.event.Side;
import com.autostock.common.event.Signal;
import com.autostock.common.util.KiwoomNumbers;
import com.autostock.market.MarketQueryService;
import com.autostock.monitor.view.DashboardView;
import com.autostock.monitor.view.PositionView;
import com.autostock.risk.KillSwitch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 대시보드 REST API (경량 FE 백엔드, PLAN 4-1절 / ARCHITECTURE.md 10절 CQRS Lite).
 *
 * <p>조회(Query)는 전부 {@link DashboardFacade}를 거쳐 View DTO로 반환한다 — Domain Entity나
 * Map을 그대로 노출하지 않는다(ARCHITECTURE.md 설계 규칙 13·14·15). 킬스위치 조작·테스트
 * 시그널처럼 "운영 제어" 성격의 Command는 여전히 risk.KillSwitch를 직접 호출한다 — 조회와
 * 달리 상태를 바꾸는 동작이지만, 이 컨트롤러 자체가 곧 그 Command의 Application Service
 * 역할을 하는 경량 구조라 별도 서비스 계층을 새로 두지 않았다(기존 구조 유지).
 *
 * <p>경계 규칙: 매매 흐름 개입은 반드시 이벤트로만 한다 — 테스트 시그널은 Signal,
 * 주문 취소(운영 1일차 ⑤)는 {@link CancelRequest} 이벤트를 발행할 뿐이며 RiskGate·
 * TradingService를 우회하지 않는다.
 *
 * <p>시세(quote, 운영 1일차 ⑧)는 market.MarketQueryService(공개 API)를 통해 조회한다 —
 * Kiwoom 응답 Map은 이 컨트롤러 안에서만 다루고 밖에는 {@link QuoteView}만 내보낸다.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    /** ka10001/ka10004 응답 키 실측 확정 전까지, 첫 응답의 키 목록을 1회 로그로 남긴다. */
    private static final AtomicBoolean QUOTE_KEYS_LOGGED = new AtomicBoolean(false);

    private final DashboardFacade facade;
    private final KillSwitch killSwitch;
    private final ApplicationEventPublisher publisher;
    private final MarketQueryService marketQueryService;

    public DashboardController(DashboardFacade facade,
                               KillSwitch killSwitch,
                               ApplicationEventPublisher publisher,
                               MarketQueryService marketQueryService) {
        this.facade = facade;
        this.killSwitch = killSwitch;
        this.publisher = publisher;
        this.marketQueryService = marketQueryService;
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
     * 테스트 시그널 발행 — 수동 검증용 (paper 전용).
     * 흐름: 여기서 Signal 발행 → RiskGate 검사 → OrderRequest → 체결 → Fill
     * → PositionBook/EventFeed 갱신. 즉 실제 매매와 완전히 같은 경로를 탄다.
     *
     * <p>quantity(운영 1일차 ⑦)는 선택값 — 비우면 RiskGate 자동 사이징(매수: 예산 비율,
     * 매도: 전량 청산), 지정하면 RiskGate가 상한(예산 캡·보유량 캡) 안에서 그 수량을 쓴다.
     */
    @PostMapping("/test-signal")
    public TestSignalResponse testSignal(@RequestBody TestSignalRequest request) {
        Long fixedQuantity = parseQuantity(request.quantity());
        publisher.publishEvent(new Signal(
                "dashboard-manual",
                request.symbol(),
                Side.valueOf(request.side()),
                new BigDecimal(request.price()),
                1.0,
                fixedQuantity,
                Instant.now()));
        return new TestSignalResponse(true);
    }

    /**
     * 주문 취소 요청 (운영 1일차 ⑤ — kt10003 실측 확정 2026-09-11 근거).
     * CancelRequest 이벤트만 발행한다 — 실제 취소 가능 여부 판정·브로커 호출은
     * trading.TradingService 소관(비동기). FE는 잠시 후 주문 이력을 재조회해 결과를 본다.
     */
    @PostMapping("/orders/{clientOrderId}/cancel")
    public TestSignalResponse cancelOrder(@PathVariable String clientOrderId) {
        publisher.publishEvent(new CancelRequest(clientOrderId, "dashboard", Instant.now()));
        return new TestSignalResponse(true);
    }

    /**
     * 종목 시세 조회 (운영 1일차 ③·⑧) — 시가/고가/저가/현재가(ka10001) + 최우선
     * 매수/매도 호가(ka10004). 폼에서 종목 입력 시 표시하고, 기준가 기본값(현재가)에도 쓴다.
     *
     * <p><b>TODO 실측</b>: 두 TR의 응답 필드명은 문서 기반 추정 — 후보 키를 순서대로
     * 탐색하고, 못 찾으면 null(FE는 "-" 표시). 첫 호출의 응답 키 목록을 로그로 남겨
     * 실측 확정에 쓴다(값은 로그하지 않는다 — 시세값은 민감하지 않지만 로그 소음 방지).
     */
    @GetMapping("/quote/{symbol}")
    public QuoteView quote(@PathVariable String symbol) {
        Map<String, Object> price = marketQueryService.stockPrice(symbol);
        Map<String, Object> book;
        try {
            book = marketQueryService.orderBook(symbol);
        } catch (Exception e) {
            // 호가 TR 미검증 — 실패해도 기본정보만으로 응답한다(방어)
            log.warn("호가(ka10004) 조회 실패 — 기본정보만 반환: {} ({})", symbol, e.getMessage());
            book = Map.of();
        }
        if (QUOTE_KEYS_LOGGED.compareAndSet(false, true)) {
            log.info("[실측] ka10001 응답 키: {}", price.keySet());
            log.info("[실측] ka10004 응답 키: {}", book.keySet());
        }
        return new QuoteView(
                symbol,
                firstText(price, "stk_nm", "stock_nm", "isu_nm"),
                firstPrice(price, "cur_prc", "stk_prpr", "prpr"),
                firstPrice(price, "open_pric", "stk_oprc", "oprc"),
                firstPrice(price, "high_pric", "stk_hgprc", "hgprc"),
                firstPrice(price, "low_pric", "stk_lwprc", "lwprc"),
                firstPrice(book, "sel_fpr_bid", "sel_1th_pre_bid", "ask_1", "sel_bid_1"),
                firstPrice(book, "buy_fpr_bid", "buy_1th_pre_bid", "bid_1", "buy_bid_1"));
    }

    /**
     * 후보 키를 순서대로 찾아 가격으로 파싱한다. 키움 시세값은 "+258000"/"-257500"처럼
     * 전일대비 부호가 접두로 붙는다(WS FID 10 실측과 동일 규약) — 절대값으로 정규화한다.
     */
    private static BigDecimal firstPrice(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object raw = map.get(key);
            if (raw != null && !String.valueOf(raw).isBlank()) {
                BigDecimal value = KiwoomNumbers.toBigDecimal(raw).abs();
                if (value.signum() > 0) {
                    return value;
                }
            }
        }
        return null;
    }

    /** 후보 키를 순서대로 찾아 문자열로 반환한다(종목명 등). 없으면 null. */
    private static String firstText(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object raw = map.get(key);
            if (raw != null && !String.valueOf(raw).isBlank()) {
                return String.valueOf(raw).trim();
            }
        }
        return null;
    }

    /** 수량 문자열 파싱 — 빈 값/미지정은 null(자동 사이징), 숫자 오류는 400 대신 null 처리하지 않고 예외. */
    private static Long parseQuantity(String quantity) {
        if (quantity == null || quantity.isBlank()) {
            return null;
        }
        return Long.parseLong(quantity.trim());
    }

    /** 테스트 시그널 입력값. side: "BUY" | "SELL", quantity: 선택(빈 값 = 자동 사이징) */
    public record TestSignalRequest(String symbol, String side, String price, String quantity) {
    }

    /** 테스트 시그널 응답. */
    public record TestSignalResponse(boolean accepted) {
    }

    /** 킬스위치 상태 응답. */
    public record KillSwitchView(boolean engaged) {
    }

    /** 종목 시세 응답(운영 1일차 ⑧) — 필드 미확정(실측 전)은 null, FE는 "-" 표시. */
    public record QuoteView(String symbol, String name, BigDecimal currentPrice,
                            BigDecimal openPrice, BigDecimal highPrice, BigDecimal lowPrice,
                            BigDecimal bestAsk, BigDecimal bestBid) {
    }
}
