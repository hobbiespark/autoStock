package com.autostock.strategy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 단타 → 스윙·중기 이월 상태 — {@code strategy_carry_positions}(V31, 2026-10-30). (전략, 종목)마다 한 행.
 * 수량·평단은 전략 장부가 원본이고 여기에는 단계·차수·이월일만 둔다. {@link SwingCarryManager}만 쓴다.
 */
@Entity
@Table(name = "strategy_carry_positions")
@IdClass(StrategyStateEntity.Key.class)
public class StrategyCarryEntity {

    @Id
    @Column(name = "strategy_id", nullable = false, length = 64)
    private String strategyId;

    @Id
    @Column(name = "symbol", nullable = false, length = 6)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 8)
    private SwingCarryMath.Stage stage;

    @Column(name = "tranches", nullable = false)
    private int tranches;

    @Column(name = "carried_on", nullable = false)
    private LocalDate carriedOn;

    @Column(name = "last_added_on")
    private LocalDate lastAddedOn;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 낙관적 잠금 — 래퍼 타입인 이유는 {@link StrategyStateEntity#getVersion()}과 같다. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** JPA 전용 — 직접 사용 금지. */
    protected StrategyCarryEntity() {
    }

    StrategyCarryEntity(String strategyId, String symbol, LocalDate carriedOn, Instant now) {
        this.strategyId = strategyId;
        this.symbol = symbol;
        this.stage = SwingCarryMath.Stage.SWING;
        this.tranches = 1;
        this.carriedOn = carriedOn;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 추가 매수 체결 — 같은 날 두 번째 체결(부분 체결)은 차수를 다시 세지 않는다. 3차면 중기 단계. 바뀌었으면 true. */
    boolean added(LocalDate day, Instant now) {
        if (day.equals(lastAddedOn) || tranches >= 3) {
            return false;
        }
        tranches++;
        lastAddedOn = day;
        if (tranches >= 3) {
            stage = SwingCarryMath.Stage.MID;
        }
        updatedAt = now;
        return true;
    }

    public String getStrategyId() { return strategyId; }
    public String getSymbol() { return symbol; }
    public SwingCarryMath.Stage getStage() { return stage; }
    public int getTranches() { return tranches; }
    public LocalDate getCarriedOn() { return carriedOn; }
    public LocalDate getLastAddedOn() { return lastAddedOn; }
    public Long getVersion() { return version; }
}
