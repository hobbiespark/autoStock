package com.autostock.execution;

import com.autostock.common.event.Side;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 주문 Aggregate — {@code orders} 테이블 매핑. 상태는 오직 {@link OrderStatus}의
 * 전이표를 통과한 값으로만 바뀐다(PLAN.md ADR-6 6·7절).
 *
 * <p><b>왜 setter가 없는가</b> — status 필드에 직접 대입할 수 있게 열어두면, 언젠가
 * 누군가 상태기계를 우회해 "SUBMITTED에서 바로 FILLED로" 같은 불법 전이를 코드
 * 어딘가에서 슬쩍 저지르게 된다. {@link #transitionTo(OrderStatus)}만 열어두고 그
 * 안에서 {@link OrderStatus#canTransitionTo(OrderStatus)}를 강제하면, 잘못된 전이는
 * 컴파일이 아니라 즉시 런타임 예외로 드러난다 — 매매 안전에서는 이게 더 낫다
 * (조용히 잘못된 상태로 넘어가는 것보다 시끄럽게 죽는 편이 사고 추적이 쉽다).
 */
@Entity
@Table(name = "orders")
public class OrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 논리 주문 멱등키 — DB UNIQUE 제약(V2__orders.sql)이 중복 주문의 최종 방어선. */
    @Column(name = "client_order_id", nullable = false, unique = true)
    private String clientOrderId;

    /** 브로커 주문번호 — LIVE 접수 완료 전까지는 null(SIM은 즉시 채워짐). */
    @Column(name = "broker_order_id")
    private String brokerOrderId;

    @Column(nullable = false)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Side side;

    @Column(nullable = false)
    private long quantity;

    /** 누적 체결 수량. applyFill()로만 증가한다. */
    @Column(name = "filled_quantity", nullable = false)
    private long filledQuantity;

    @Column(name = "limit_price", precision = 19, scale = 4)
    private BigDecimal limitPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "strategy_id", nullable = false)
    private String strategyId;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA 전용 — 직접 사용 금지. */
    protected OrderEntity() {
    }

    /** 새 주문 생성 — 초기 상태는 항상 {@link OrderStatus#CREATED}. */
    public OrderEntity(String clientOrderId, String symbol, Side side, long quantity,
                       BigDecimal limitPrice, String strategyId) {
        this.clientOrderId = clientOrderId;
        this.symbol = symbol;
        this.side = side;
        this.quantity = quantity;
        this.filledQuantity = 0L;
        this.limitPrice = limitPrice;
        this.strategyId = strategyId;
        this.status = OrderStatus.CREATED;
        Instant now = Instant.now();
        this.submittedAt = now;
        this.updatedAt = now;
    }

    /**
     * 상태를 전이한다. {@link OrderStatus#canTransitionTo(OrderStatus)}가 false를
     * 돌려주는 전이는 IllegalStateException으로 즉시 거부된다 — 메시지에 from→to를
     * 남겨 어떤 잘못된 전이가 시도됐는지 로그만 보고도 알 수 있게 한다.
     */
    public void transitionTo(OrderStatus newStatus) {
        if (!status.canTransitionTo(newStatus)) {
            throw new IllegalStateException(
                    "불법 주문 상태 전이: " + status + " → " + newStatus + " (clientOrderId=" + clientOrderId + ")");
        }
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }

    /** 브로커 접수 성공 처리 — brokerOrderId를 채우고 SUBMITTED로 전이한다. */
    public void markSubmitted(String brokerOrderId) {
        this.brokerOrderId = brokerOrderId;
        transitionTo(OrderStatus.SUBMITTED);
    }

    /**
     * 체결 수량을 누적 반영한다. 누적 체결이 주문 수량에 도달하면 FILLED,
     * 아니면 PARTIALLY_FILLED로 전이한다.
     *
     * <p>같은 주문에 여러 번 호출될 수 있다(부분체결 통보가 여러 번 오는 경우) —
     * 그래서 OrderStatus.PARTIALLY_FILLED→PARTIALLY_FILLED 자기 자신 전이도
     * 합법으로 등록돼 있다.
     *
     * @param qty 이번에 새로 체결된 수량(누적치 아님)
     */
    public void applyFill(long qty) {
        if (qty <= 0) {
            throw new IllegalArgumentException("체결 수량은 0보다 커야 한다: " + qty);
        }
        this.filledQuantity += qty;
        OrderStatus target = filledQuantity >= quantity ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
        transitionTo(target);
    }

    public Long getId() { return id; }
    public String getClientOrderId() { return clientOrderId; }
    public String getBrokerOrderId() { return brokerOrderId; }
    public String getSymbol() { return symbol; }
    public Side getSide() { return side; }
    public long getQuantity() { return quantity; }
    public long getFilledQuantity() { return filledQuantity; }
    public BigDecimal getLimitPrice() { return limitPrice; }
    public OrderStatus getStatus() { return status; }
    public String getStrategyId() { return strategyId; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
