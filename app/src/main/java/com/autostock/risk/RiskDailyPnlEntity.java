package com.autostock.risk;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 일별 실현손익 누계 — {@code risk_daily_pnl}(V9, Phase 0.2). 거래일(KST)당 한 행이다.
 * {@link DailyPnlTracker}가 값이 바뀔 때마다 저장하고 기동 시 오늘 행으로 복원한다({@link RiskStateStore}).
 */
@Entity
@Table(name = "risk_daily_pnl")
public class RiskDailyPnlEntity {

    @Id
    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(name = "realized_pnl", nullable = false, precision = 19, scale = 4)
    private BigDecimal realizedPnl;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA 전용 — 직접 사용 금지. */
    protected RiskDailyPnlEntity() {
    }

    RiskDailyPnlEntity(LocalDate tradeDate) {
        this.tradeDate = tradeDate;
    }

    /** 시각은 호출부가 주입 Clock에서 넘긴다(A4 ③) — 엔티티는 시계에 직접 접근하지 않는다. */
    void update(BigDecimal realizedPnl, Instant now) {
        this.realizedPnl = realizedPnl;
        this.updatedAt = now;
    }

    public LocalDate getTradeDate() { return tradeDate; }
    public BigDecimal getRealizedPnl() { return realizedPnl; }
    public Instant getUpdatedAt() { return updatedAt; }
}
