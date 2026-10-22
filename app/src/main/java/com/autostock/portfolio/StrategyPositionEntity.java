package com.autostock.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 전략별 현재 보유 — {@code strategy_positions}(V20). (전략, 종목)마다 한 행, 수량이 0이 되면 행을 지운다.
 * {@link StrategyBook}만 쓴다.
 */
@Entity
@Table(name = "strategy_positions")
@IdClass(StrategyPositionEntity.Key.class)
public class StrategyPositionEntity {

    @Id
    @Column(name = "strategy_id", nullable = false, length = 64)
    private String strategyId;

    @Id
    @Column(name = "symbol", nullable = false, length = 6)
    private String symbol;

    @Column(name = "quantity", nullable = false)
    private long quantity;

    @Column(name = "avg_price", precision = 18, scale = 4)
    private BigDecimal avgPrice;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA 전용 — 직접 사용 금지. */
    protected StrategyPositionEntity() {
    }

    StrategyPositionEntity(String strategyId, String symbol, long quantity, BigDecimal avgPrice, Instant openedAt,
                           Instant updatedAt) {
        this.strategyId = strategyId;
        this.symbol = symbol;
        this.quantity = quantity;
        this.avgPrice = avgPrice;
        this.openedAt = openedAt;
        this.updatedAt = updatedAt;
    }

    public String getStrategyId() { return strategyId; }
    public String getSymbol() { return symbol; }
    public long getQuantity() { return quantity; }
    public BigDecimal getAvgPrice() { return avgPrice; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** 복합 키 (strategy_id, symbol). */
    public static class Key implements Serializable {
        private String strategyId;
        private String symbol;

        /** JPA 전용. */
        public Key() {
        }

        public Key(String strategyId, String symbol) {
            this.strategyId = strategyId;
            this.symbol = symbol;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other
                    && Objects.equals(strategyId, other.strategyId)
                    && Objects.equals(symbol, other.symbol);
        }

        @Override
        public int hashCode() {
            return Objects.hash(strategyId, symbol);
        }
    }
}
