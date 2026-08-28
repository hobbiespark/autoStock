package com.autostock.monitor;

import com.autostock.common.event.Fill;
import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.Side;
import com.autostock.common.util.MarketConstants;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 슬리피지 계측 — Perold(1988) Implementation Shortfall의 "가격 격차" 성분을
 * 주문 단위로 기록한다 (PLAN 6절, PROGRESS 4-1 트랙 C2).
 *
 * <p><b>정의</b>: 결정가(decision price) = RiskGate가 주문을 발행한 시점의 기준가
 * ({@link OrderRequest#limitPrice()} — Signal.refPrice에서 옴). 체결가와의 격차를
 * bps로 환산하되, <b>부호는 "양수 = 나에게 불리"</b>로 통일한다:
 * 매수는 (체결가−결정가)/결정가, 매도는 (결정가−체결가)/결정가.
 *
 * <p><b>왜 필요한가</b>: 게이트 ②의 "백테스트 대비 슬리피지 격차 계측·허용범위 내"를
 * 판정하려면 라이브 슬리피지의 실측 분포가 있어야 한다. 백테스트 CostModel의
 * slippagePct(현재 0.05% = 5bps 가정)가 낙관적이었는지 이 수치로 교정한다.
 * 실전 215개 전략에서 라이브 성과가 백테스트 대비 중앙값 73% 하락한 실증
 * (Suhonen et al. 2017, JPM)의 주요 원인 중 하나가 비용·슬리피지 과소평가다.
 *
 * <p><b>영속화하지 않는 이유</b>: OrderRequest/Fill은 이미 이벤트 스토어에 전량
 * 영속화되므로(감사·리플레이 원칙) 슬리피지는 언제든 리플레이로 재계산 가능한
 * 파생 수치다. 여기서는 운영 관측용 "오늘" 집계만 메모리에 유지한다
 * (KST 자정 롤오버 — DailyPnlTracker와 동일 패턴).
 *
 * <p><b>집계에서 제외되는 체결</b>: 재시작·Reconciliation으로 복구된 체결은 이 JVM의
 * 결정가 캐시에 원 주문이 없어 계산 불가 → 로그만 남기고 건너뛴다(수치를 추정으로
 * 오염시키지 않는다).
 */
@Component
public class SlippageTracker {

    private static final Logger log = LoggerFactory.getLogger(SlippageTracker.class);

    /** 결정가 캐시가 무한히 자라는 것을 막는 상한 — 일 주문 상한(기본 30)의 넉넉한 배수. */
    private static final int MAX_PENDING = 500;

    private final Clock clock;
    private final DistributionSummary summary;

    /** idempotencyKey → 결정가·방향. 체결로 소진되거나 자정 롤오버 때 비운다. */
    private final Map<String, PendingOrder> pending = new ConcurrentHashMap<>();

    private final Object lock = new Object();
    private LocalDate day;
    private long fillCount;
    private long totalQuantity;
    private double quantityWeightedBpsSum;   // Σ(bps × 체결수량)
    private double maxBps;
    private String maxBpsSymbol = "";

    private record PendingOrder(BigDecimal decisionPrice, Side side) {
    }

    /** 오늘 요약 — DashboardFacade/DailyReportScheduler가 읽는 불변 스냅샷. */
    public record SlippageSummary(long fills, double avgBps, double maxBps, String maxBpsSymbol) {
    }

    public SlippageTracker(Clock clock, MeterRegistry meterRegistry) {
        this.clock = clock;
        this.day = LocalDate.now(clock.withZone(MarketConstants.KST));
        this.summary = DistributionSummary.builder("order.slippage.bps")
                .description("주문 결정가 대비 체결가 격차 (bps, 양수=불리)")
                .register(meterRegistry);
    }

    @EventListener
    public void onOrderRequest(OrderRequest order) {
        if (order.limitPrice() == null || order.limitPrice().signum() <= 0) {
            return; // 결정가 없는 주문은 계측 불가
        }
        if (pending.size() >= MAX_PENDING) {
            // 비정상 상황(체결/취소 유실 누적) 방어 — 가장 오래된 것부터 지우는 대신
            // 전체를 비운다: 계측은 보조 기능이므로 단순함을 우선한다.
            log.warn("슬리피지 결정가 캐시 상한({}) 도달 — 캐시를 비움", MAX_PENDING);
            pending.clear();
        }
        pending.put(order.idempotencyKey(), new PendingOrder(order.limitPrice(), order.side()));
    }

    @EventListener
    public void onFill(Fill fill) {
        PendingOrder origin = pending.remove(fill.orderIdempotencyKey());
        if (origin == null) {
            log.debug("슬리피지 계측 제외 — 결정가 미보유 체결(재시작/Reconciliation 복구 추정): {}",
                    fill.orderIdempotencyKey());
            return;
        }
        if (fill.fillPrice() == null || fill.fillPrice().signum() <= 0) {
            return;
        }
        BigDecimal diff = fill.fillPrice().subtract(origin.decisionPrice());
        if (origin.side() == Side.SELL) {
            diff = diff.negate(); // 매도는 "결정가보다 싸게 팔린 만큼"이 불리
        }
        double bps = diff.divide(origin.decisionPrice(), MathContext.DECIMAL64)
                .doubleValue() * 10_000.0;

        summary.record(bps);
        synchronized (lock) {
            rolloverIfNeeded();
            fillCount++;
            totalQuantity += fill.filledQuantity();
            quantityWeightedBpsSum += bps * fill.filledQuantity();
            if (bps > maxBps) {
                maxBps = bps;
                maxBpsSymbol = fill.symbol();
            }
        }
        log.info("슬리피지: {} {} {}주 결정가 {} → 체결가 {} = {}bps",
                fill.symbol(), origin.side(), fill.filledQuantity(),
                origin.decisionPrice(), fill.fillPrice(), String.format("%.2f", bps));
    }

    public SlippageSummary todaySummary() {
        synchronized (lock) {
            rolloverIfNeeded();
            double avg = totalQuantity == 0 ? 0.0 : quantityWeightedBpsSum / totalQuantity;
            return new SlippageSummary(fillCount, avg, maxBps, maxBpsSymbol);
        }
    }

    /** KST 날짜가 바뀌면 일일 집계·결정가 캐시를 초기화한다 (DailyPnlTracker와 동일 규칙). */
    private void rolloverIfNeeded() {
        LocalDate today = LocalDate.now(clock.withZone(MarketConstants.KST));
        if (!today.equals(day)) {
            day = today;
            fillCount = 0;
            totalQuantity = 0;
            quantityWeightedBpsSum = 0.0;
            maxBps = 0.0;
            maxBpsSymbol = "";
            pending.clear();
        }
    }
}
