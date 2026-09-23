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
     * <p><b>실측 확정 (2026-09-18, mockapi — docs/measured/tr_probe_20260918_ka10001/ka10004.json)</b>:
     * ka10001 = stk_nm / cur_prc / open_pric / high_pric / low_pric (부호 접두 "+258500" 형식),
     * ka10004 = sel_fpr_bid(최우선 매도호가) / buy_fpr_bid(최우선 매수호가), 잔량은 sel_fpr_req /
     * buy_fpr_req, 2~10차는 sel_2th_pre_bid…/buy_2th_pre_bid…, 총잔량 tot_sel_req / tot_buy_req,
     * 기준시각 bid_req_base_tm(HHmmss). 값이 비면 null(FE는 "-" 표시).
     *
     * <p>ka10001 실패도 500이 아니라 빈 QuoteView로 응답한다(2026-09-23 실측: 장전 07:53에
     * 키움이 {@code [1631]/[1632] 서비스를 처리하는 중에 오류}를 30회 돌려줘 스택 30개가 로그를
     * 덮었다). 폼의 기준가 기본값이 비면 사용자가 직접 입력해야 하므로 오히려 안전하다
     * (9/18 "기본값 그대로 193주 매수" 사고 참고). 로그는 한 줄 WARN, 스택 없음.
     */
    @GetMapping("/quote/{symbol}")
    public QuoteView quote(@PathVariable String symbol) {
        Map<String, Object> price;
        try {
            price = marketQueryService.stockPrice(symbol);
        } catch (Exception e) {
            log.warn("기본정보(ka10001) 조회 실패 — 빈 시세 반환: {} ({})", symbol, e.getMessage());
            price = Map.of();
        }
        Map<String, Object> book;
        try {
            book = marketQueryService.orderBook(symbol);
        } catch (Exception e) {
            // 호가 TR 실패(rate limit 등)해도 기본정보만으로 응답한다(방어)
            log.warn("호가(ka10004) 조회 실패 — 기본정보만 반환: {} ({})", symbol, e.getMessage());
            book = Map.of();
        }
        return new QuoteView(
                symbol,
                firstText(price, "stk_nm"),
                firstPrice(price, "cur_prc"),
                firstPrice(price, "open_pric"),
                firstPrice(price, "high_pric"),
                firstPrice(price, "low_pric"),
                firstPrice(book, "sel_fpr_bid"),
                firstPrice(book, "buy_fpr_bid"));
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
