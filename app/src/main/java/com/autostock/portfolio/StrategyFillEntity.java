package com.autostock.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 전략별 체결 기록 — {@code strategy_fills}(V20). 매도는 실현손익(평단 기준, 추정 비용 차감)을 함께 남긴다.
 * 전략별 성과(승률·평균 손익) 집계의 원본이다(aiDoc/multi-strategy-paper-2026-10-16.md 4절).
 */
@Entity
@Table(name = "strategy_fills")
public class StrategyFillEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "strategy_id", nullable = false, length = 64)
    private String strategyId;

    @Column(name = "symbol", nullable = false, length = 6)
    private String symbol;

    @Column(name = "side", nullable = false, length = 4)
    private String side;

    @Column(name = "quantity", nullable = false)
    private long quantity;

    @Column(name = "price", nullable = false, precision = 18, scale = 4)
    private BigDecimal price;

    @Column(name = "client_order_id", nullable = false, length = 80)
    private String clientOrderId;

    @Column(name = "avg_price_before", precision = 18, scale = 4)
    private BigDecimal avgPriceBefore;

    @Column(name = "realized_pnl", precision = 18, scale = 2)
    private BigDecimal realizedPnl;

    @Column(name = "filled_at", nullable = false)
    private Instant filledAt;

    /** JPA 전용 — 직접 사용 금지. */
    protected StrategyFillEntity() {
    }

    StrategyFillEntity(String strategyId, String symbol, String side, long quantity, BigDecimal price,
                       String clientOrderId, BigDecimal avgPriceBefore, BigDecimal realizedPnl, Instant filledAt) {
        this.strategyId = strategyId;
        this.symbol = symbol;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
        this.clientOrderId = clientOrderId;
        this.avgPriceBefore = avgPriceBefore;
        this.realizedPnl = realizedPnl;
        this.filledAt = filledAt;
    }

    public Long getId() { return id; }
    public String getStrategyId() { return strategyId; }
    public String getSymbol() { return symbol; }
    public String getSide() { return side; }
    public long getQuantity() { return quantity; }
    public BigDecimal getPrice() { return price; }
    public String getClientOrderId() { return clientOrderId; }
    public BigDecimal getAvgPriceBefore() { return avgPriceBefore; }
    public BigDecimal getRealizedPnl() { return realizedPnl; }
    public Instant getFilledAt() { return filledAt; }
}
