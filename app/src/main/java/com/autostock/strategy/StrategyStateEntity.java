package com.autostock.strategy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 전략 판단 상태 — {@code strategy_state}(V13, 실행 계획 1.1). (전략, 종목)마다 마지막 판단일 한 행.
 * {@link C3LiveStrategy}가 판단에 성공할 때 저장하고, 재기동 뒤 첫 실행에서 읽어 판단 주기를 이어간다.
 */
@Entity
@Table(name = "strategy_state")
@IdClass(StrategyStateEntity.Key.class)
public class StrategyStateEntity {

    @Id
    @Column(name = "strategy_id", nullable = false, length = 64)
    private String strategyId;

    @Id
    @Column(name = "symbol", nullable = false, length = 6)
    private String symbol;

    @Column(name = "last_decision_date", nullable = false)
    private LocalDate lastDecisionDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * 낙관적 잠금. 래퍼 타입이라 새 행(null)은 persist, 기존 행은 merge로 구분된다 — 키를 직접 넣는 엔티티라
     * 원시 타입이면 항상 merge로 가고, 행이 없을 때 Hibernate 6.6이 예외를 던진다({@code risk.RiskStateEntity}와 같은 이유).
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** JPA 전용 — 직접 사용 금지. */
    protected StrategyStateEntity() {
    }

    StrategyStateEntity(String strategyId, String symbol, Instant now) {
        this.strategyId = strategyId;
        this.symbol = symbol;
        this.createdAt = now;
    }

    void decided(LocalDate date, Instant now) {
        this.lastDecisionDate = date;
        this.updatedAt = now;
    }

    public String getStrategyId() { return strategyId; }
    public String getSymbol() { return symbol; }
    public LocalDate getLastDecisionDate() { return lastDecisionDate; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }

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
