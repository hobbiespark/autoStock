package com.autostock.trading;

import com.autostock.common.event.Fill;
import com.autostock.common.event.Side;
import com.autostock.common.util.MarketConstants;
import com.autostock.common.util.Price;
import com.autostock.common.util.Quantity;
import com.autostock.common.util.StockCode;
import com.autostock.common.util.StockNames;
import com.autostock.execution.BrokerBalance;
import com.autostock.execution.BrokerHolding;
import com.autostock.execution.BrokerOutstandingOrder;
import com.autostock.execution.BrokerPort;
import com.autostock.portfolio.PositionBook;
import com.autostock.portfolio.StrategyBook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 체결통보 누락 복구(2026-10-16) — 브로커 잔고와 "결과가 안 들어온 주문"을 맞춰 본다.
 *
 * <p><b>배경</b>: 체결은 WS 주문체결통보(type 00)로만 들어온다({@link OrderNoticeHandler}). 10/16 14:58 재기동 때 시세 REG를
 * 종목마다 보내다 요청 건수 초과(105110)로 체결통보 REG까지 거절돼, 15:26 ON-CLOSE 매수 3건의 접수·체결 통보가 오지
 * 않았다. 그러면 주문은 SUBMITTED로 남고, 계좌 장부(PositionBook)·전략별 장부(StrategyBook)에 보유가 없어 다음 날 아침
 * 매도가 나가지 않는다. 체결내역 조회 TR은 아직 실측 전이라({@link ReconciliationService} TODO) 잔고로 판단한다.
 *
 * <p><b>판정</b>(종목별): 결과 미확정 주문 = 상태 {@link #OPEN_STATUSES}, 브로커 주문번호 있음, 접수 뒤
 * {@link #MIN_AGE} 지남(통보가 아직 오는 중일 수 있는 주문은 건드리지 않는다), {@link #LOOKBACK} 이내, 브로커 미체결 목록에 없음.
 * <ul>
 *   <li><b>매수</b>: 브로커 보유 − 전략 장부 합계 = 설명 안 되는 수량. 오래된 매수 주문부터 그 수량만큼 체결로 본다.
 *       단가 = 브로커 매입 평단(없으면 지정가).</li>
 *   <li><b>매도</b>: 전략 장부 합계 − 브로커 보유 = 이미 팔린 수량. 오래된 매도 주문부터 그 전략 보유 한도에서 체결로 본다.
 *       단가 = 지정가(추정 — 실제 체결가는 대개 이보다 유리하다, 실현손익은 보수적으로 잡힌다).</li>
 * </ul>
 * 체결로 본 주문은 {@code applyFill}로 상태를 닫는다 — 뒤늦게 통보가 와도 누적량 비교로 중복 반영되지 않는다.
 * <b>장이 끝난 날의 주문</b>인데 브로커 미체결 목록에 없고 잔고로도 설명되지 않는 남은 수량은 당일 소멸로 보고 취소로 닫는다
 * (지정가 당일 주문 — 열린 주문으로 남으면 그 전략의 같은 종목 매수가 계속 막힌다, RiskGate 미체결 매수 확인).
 *
 * <p>대상 상태에 UNKNOWN·CANCEL_REQUESTED도 넣는다 — 10/16 15:31 미체결 타임아웃 취소가 장 종료 뒤라 실패(RC4058 모의투자
 * 장종료)해 세 주문이 UNKNOWN이 됐다. 대사(ReconciliationService)는 체결내역 TR이 없어 UNKNOWN을 풀지 못한다.
 * 계좌 장부도 빠져 있으면(장중 누락 — 앱이 계속 켜져 있던 경우) {@link Fill}을 발행해 계좌·전략 장부를 같이 맞추고,
 * 계좌 장부는 이미 맞으면(재기동 — 잔고 복원이 맞춰 둠) 전략 장부에만 넣는다.
 *
 * <p><b>도는 때</b>: 기동 90초 뒤(잔고 복원 다음) 한 번, 거래일 15:45(종가 체결 통보가 다 끝난 뒤) 한 번. 장중에는 돌지 않는다.
 * LIVE 모드에서만.
 */
@Component
public class MissedFillRecovery {

    private static final Logger log = LoggerFactory.getLogger(MissedFillRecovery.class);

    static final Set<OrderStatus> OPEN_STATUSES = Set.of(OrderStatus.SUBMITTED, OrderStatus.ACCEPTED,
            OrderStatus.PARTIALLY_FILLED, OrderStatus.UNKNOWN, OrderStatus.CANCEL_REQUESTED);
    static final Duration MIN_AGE = Duration.ofMinutes(3);
    static final Duration LOOKBACK = Duration.ofDays(7);

    private final OrderRepository orderRepository;
    private final BrokerPort brokerPort;
    private final PositionBook positionBook;
    private final StrategyBook strategyBook;
    private final ApplicationEventPublisher publisher;
    private final TradingProperties tradingProperties;
    private final Clock clock;

    public MissedFillRecovery(OrderRepository orderRepository, BrokerPort brokerPort, PositionBook positionBook,
                              StrategyBook strategyBook, ApplicationEventPublisher publisher,
                              TradingProperties tradingProperties, Clock clock) {
        this.orderRepository = orderRepository;
        this.brokerPort = brokerPort;
        this.positionBook = positionBook;
        this.strategyBook = strategyBook;
        this.publisher = publisher;
        this.tradingProperties = tradingProperties;
        this.clock = clock;
    }

    /** 기동 90초 뒤 한 번(잔고 복원 다음). fixedDelay를 하루로 둬 사실상 기동 때만 돈다 — 매일은 15:45가 맡는다. */
    @Scheduled(initialDelay = 90_000, fixedDelay = 86_400_000)
    public void onStartup() {
        recoverSafely("기동");
    }

    /** 거래일 15:45 — 종가 단일가 체결 통보가 다 들어온 뒤. */
    @Scheduled(cron = "0 45 15 * * MON-FRI", zone = "Asia/Seoul")
    public void afterClose() {
        recoverSafely("장 마감 뒤");
    }

    private void recoverSafely(String trigger) {
        if (tradingProperties.mode() != TradingProperties.Mode.LIVE) {
            return;
        }
        try {
            recover(trigger);
        } catch (RuntimeException e) {
            log.warn("체결통보 누락 복구({}) 실패 — 다음 때(기동·15:45) 다시: {}", trigger, e.toString());
        }
    }

    /** @return 체결로 본 주문 수 */
    synchronized int recover(String trigger) {
        Instant now = clock.instant();
        List<OrderEntity> open = orderRepository.findByStatusIn(OPEN_STATUSES).stream()
                .filter(o -> o.getBrokerOrderId() != null && o.getSubmittedAt() != null)
                .filter(o -> o.getSubmittedAt().isAfter(now.minus(LOOKBACK))
                        && o.getSubmittedAt().isBefore(now.minus(MIN_AGE)))
                .sorted(Comparator.comparing(OrderEntity::getSubmittedAt))
                .toList();
        if (open.isEmpty()) {
            return 0;
        }
        Set<String> outstanding = brokerPort.outstandingOrders().stream()
                .map(BrokerOutstandingOrder::brokerOrderId)
                .map(id -> normalized(id.value()))
                .collect(Collectors.toSet());
        List<OrderEntity> candidates = open.stream()
                .filter(o -> !outstanding.contains(normalized(o.getBrokerOrderId().value())))
                .toList();
        if (candidates.isEmpty()) {
            return 0;
        }
        BrokerBalance balance = brokerPort.balance();
        Map<StockCode, BrokerHolding> holdings = new HashMap<>();
        for (BrokerHolding h : balance.holdings()) {
            holdings.put(h.symbol(), h);
        }
        Map<StockCode, List<OrderEntity>> bySymbol = new LinkedHashMap<>();
        for (OrderEntity o : candidates) {
            bySymbol.computeIfAbsent(o.getSymbol(), k -> new ArrayList<>()).add(o);
        }
        int recovered = 0;
        for (Map.Entry<StockCode, List<OrderEntity>> entry : bySymbol.entrySet()) {
            recovered += recoverSymbol(trigger, entry.getKey(), entry.getValue(), holdings.get(entry.getKey()));
        }
        int expired = expireClosedDayOrders(trigger, candidates, now);
        if (recovered == 0 && expired == 0) {
            log.info("체결통보 누락 복구({}) — 결과 미확정 주문 {}건, 잔고로 설명되는 체결 없음(장 마감 전 주문이라 그대로 둔다)",
                    trigger, candidates.size());
        }
        return recovered;
    }

    /**
     * 장이 끝난 날의 주문 중 아직 열려 있는 것(체결 복구 뒤에도)을 당일 소멸로 보고 취소로 닫는다. 브로커 미체결 목록에 없다는 것은
     * 이미 확인했다(후보). 체결분은 그대로 두고 남은 수량만 닫힌다.
     *
     * @return 닫은 주문 수
     */
    private int expireClosedDayOrders(String trigger, List<OrderEntity> candidates, Instant now) {
        ZonedDateTime nowKst = now.atZone(MarketConstants.KST);
        LocalDate today = nowKst.toLocalDate();
        boolean todayClosed = !nowKst.toLocalTime().isBefore(MarketConstants.MARKET_CLOSE);
        int expired = 0;
        for (OrderEntity candidate : candidates) {
            LocalDate day = candidate.getSubmittedAt().atZone(MarketConstants.KST).toLocalDate();
            if (day.isAfter(today) || (day.equals(today) && !todayClosed)) {
                continue;
            }
            OrderEntity saved;
            try {
                saved = OptimisticRetry.run("장 마감 소멸 " + candidate.getClientOrderId(), null,
                        () -> orderRepository.findByClientOrderId(candidate.getClientOrderId()),
                        current -> {
                            if (!OPEN_STATUSES.contains(current.getStatus())) {
                                return null;   // 체결 복구로 이미 닫혔거나 통보가 반영됨
                            }
                            if (current.getStatus() != OrderStatus.UNKNOWN
                                    && current.getStatus() != OrderStatus.CANCEL_REQUESTED) {
                                current.transitionTo(OrderStatus.CANCEL_REQUESTED, now);
                            }
                            current.transitionTo(OrderStatus.CANCELLED, now);
                            return orderRepository.save(current);
                        });
            } catch (RuntimeException e) {
                log.warn("장 마감 소멸 정리({}) 실패: {} ({})", trigger, candidate.getClientOrderId(), e.toString());
                continue;
            }
            if (saved != null) {
                expired++;
                log.info("장 마감 소멸 정리({}): {} {} {} 체결 {}/{}주 — 브로커 미체결 목록·잔고에 없음 → 취소로 닫음 ({})", trigger,
                        saved.getStrategyId(), StockNames.label(saved.getSymbol()), saved.getSide(),
                        saved.getFilledQuantity(), saved.getQuantity(), saved.getClientOrderId());
            }
        }
        return expired;
    }

    private int recoverSymbol(String trigger, StockCode symbol, List<OrderEntity> orders, BrokerHolding holding) {
        long brokerQty = holding == null ? 0 : holding.quantity();
        long strategyQty = strategyBook.holdersOf(symbol).values().stream().mapToLong(Long::longValue).sum();
        PositionBook.Position account = positionBook.get(symbol);
        long accountQty = account == null ? 0 : account.quantity();
        int recovered = 0;

        long unexplained = brokerQty - strategyQty;   // 매수: 브로커에 있는데 전략 장부에 없는 수량
        for (OrderEntity order : orders) {
            if (order.getSide() != Side.BUY || unexplained <= 0) {
                continue;
            }
            long q = Math.min(order.getQuantity() - order.getFilledQuantity(), unexplained);
            if (q <= 0) {
                continue;
            }
            BigDecimal price = holding != null && holding.avgPrice() != null
                    ? holding.avgPrice().value() : order.getLimitPrice();
            boolean accountMissing = brokerQty - accountQty >= q;
            if (apply(trigger, order, q, price, accountMissing, "브로커 매입 평단")) {
                unexplained -= q;
                if (accountMissing) {
                    accountQty += q;
                }
                recovered++;
            }
        }

        long sold = strategyQty - brokerQty;   // 매도: 전략 장부에 있는데 브로커에 없는 수량
        for (OrderEntity order : orders) {
            if (order.getSide() != Side.SELL || sold <= 0) {
                continue;
            }
            long held = strategyBook.position(order.getStrategyId(), symbol).map(PositionBook.Position::quantity)
                    .orElse(0L);
            long q = Math.min(Math.min(order.getQuantity() - order.getFilledQuantity(), sold), held);
            if (q <= 0) {
                continue;
            }
            boolean accountMissing = accountQty - brokerQty >= q;
            if (apply(trigger, order, q, order.getLimitPrice(), accountMissing, "지정가 추정")) {
                sold -= q;
                if (accountMissing) {
                    accountQty -= q;
                }
                recovered++;
            }
        }
        return recovered;
    }

    /**
     * 주문을 체결로 닫고 장부에 넣는다. 주문 갱신이 먼저다 — 뒤늦은 통보는 누적량 비교로 버려진다.
     *
     * @param accountMissing 계좌 장부에도 없다 → Fill 발행(계좌·전략 장부 함께), 아니면 전략 장부에만
     */
    private boolean apply(String trigger, OrderEntity order, long q, BigDecimal price, boolean accountMissing,
                          String priceSource) {
        if (price == null || price.signum() <= 0) {
            log.warn("체결통보 누락 복구({}): {} {} 단가를 몰라 건너뜀 — 수동 확인 필요: {}", trigger,
                    StockNames.label(order.getSymbol()), order.getSide(), order.getClientOrderId());
            return false;
        }
        Instant now = clock.instant();
        OrderEntity saved;
        try {
            saved = OptimisticRetry.run("누락 체결 복구 " + order.getClientOrderId(), order,
                    () -> orderRepository.findByClientOrderId(order.getClientOrderId()),
                    current -> {
                        if (!OPEN_STATUSES.contains(current.getStatus())
                                || current.getFilledQuantity() + q > current.getQuantity()) {
                            return null;   // 그사이 통보가 반영됨
                        }
                        current.applyFill(new Quantity(q), now);
                        return orderRepository.save(current);
                    });
        } catch (RuntimeException e) {
            log.warn("체결통보 누락 복구({}): 주문 상태 갱신 실패 — 장부 반영 안 함: {} ({})", trigger,
                    order.getClientOrderId(), e.toString());
            return false;
        }
        if (saved == null) {
            return false;
        }
        Fill fill = new Fill(order.getClientOrderId(), order.getBrokerOrderId(), order.getSymbol(), order.getSide(),
                new Quantity(q), new Price(price), order.getSubmittedAt());
        if (accountMissing) {
            publisher.publishEvent(fill);
        } else {
            strategyBook.onFill(fill);
        }
        log.warn("체결통보 누락 복구({}): {} {} {} {}주 @ {}({}) — 주문 {} → {}, {}", trigger, order.getStrategyId(),
                StockNames.label(order.getSymbol()), order.getSide(), q, price.toPlainString(), priceSource,
                order.getClientOrderId(), saved.getStatus(),
                accountMissing ? "계좌·전략 장부 반영(Fill 발행)" : "계좌 장부는 잔고 복원으로 이미 맞음 → 전략 장부만 반영");
        return true;
    }

    /** 앞자리 0을 뺀 주문번호 — REST ord_no와 WS 9203의 자릿수 차이를 없앤다(OrderNoticeHandler와 같은 규칙). */
    private static String normalized(String orderNo) {
        return orderNo == null ? "" : orderNo.trim().replaceFirst("^0+(?=.)", "");
    }
}
