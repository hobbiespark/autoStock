package com.autostock.monitor;

import com.autostock.common.util.MarketConstants;
import com.autostock.common.util.StockNames;
import com.autostock.monitor.view.OrderHistoryItemView;
import com.autostock.monitor.view.OrderHistoryView;
import com.autostock.trading.OrderEntity;
import com.autostock.trading.OrderRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 주문 이력 조회 API (FE-1, PLAN.md ADR-10 확장표) — {@code GET /api/orders?days=7}.
 *
 * <p>trading 모듈의 {@link OrderRepository}를 직접 조회한다 — DashboardFacade가 portfolio/risk
 * 모듈을 직접 참조하는 기존 패턴과 동일하게, monitor는 이미 trading 모듈을 참조한다
 * (package-info.java 참고: TradingSystemManager가 시작 절차에서
 * {@code trading.ReconciliationService}를 직접 호출). 이 컨트롤러는 단일 소스(trading)만
 * 조회해 View DTO로 옮겨 담을 뿐이라 별도 Facade를 두지 않았다(불필요한 분산 패턴 금지,
 * ARCHITECTURE.md 규칙 20) — Domain Entity({@link OrderEntity})는 여기서만 등장하고
 * 컨트롤러 경계를 넘지 않는다(CQRS Lite, ARCHITECTURE.md 13·14절).
 */
@RestController
@RequestMapping("/api/orders")
public class OrderHistoryController {

    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_DAYS = 90;

    private final OrderRepository orderRepository;
    private final Clock clock;

    public OrderHistoryController(OrderRepository orderRepository, Clock clock) {
        this.orderRepository = orderRepository;
        this.clock = clock;
    }

    /**
     * 최근 N일 주문 — 오늘(KST)을 포함한 N개 달력 날짜(KST 자정부터)다(실행 계획 1.9, BE-P2-11). 예전에는 "지금부터 N×24시간
     * 전"이라 경계가 조회 시각마다 움직였고, 화면의 "최근 1일"이 어제 오후 주문을 섞거나 오늘 새벽 주문을 날짜와 다르게 보여 줬다.
     */
    @GetMapping
    public OrderHistoryView orders(@RequestParam(required = false) Integer days) {
        int effectiveDays = clampDays(days);
        LocalDate firstDay = LocalDate.now(clock.withZone(MarketConstants.KST)).minusDays(effectiveDays - 1L);
        Instant since = firstDay.atStartOfDay(MarketConstants.KST).toInstant();
        List<OrderHistoryItemView> items = orderRepository
                .findBySubmittedAtGreaterThanEqualOrderBySubmittedAtDesc(since)
                .stream()
                .map(OrderHistoryController::toView)
                .toList();
        return new OrderHistoryView(items);
    }

    /** days 파라미터 검증 — 기본 7일, 최대 90일. 음수·0·과대값은 범위 안으로 보정한다. */
    private static int clampDays(Integer days) {
        if (days == null || days <= 0) {
            return DEFAULT_DAYS;
        }
        return Math.min(days, MAX_DAYS);
    }

    private static OrderHistoryItemView toView(OrderEntity order) {
        return new OrderHistoryItemView(
                order.getClientOrderId(),
                order.getSymbol().value(),
                StockNames.nameOf(order.getSymbol()).orElse(null),
                order.getSide().name(),
                order.getQuantity(),
                order.getFilledQuantity(),
                order.getLimitPrice(),
                order.getStatus().name(),
                order.getStrategyId(),
                order.getSubmittedAt());
    }
}
