package com.autostock.monitor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 종목 선정 이유 판단 스냅샷 Aggregate — {@code signal_decisions} 테이블 매핑(V5 마이그레이션, FE-6).
 *
 * <p>{@code common.event.SignalDecision} 이벤트를 그대로 append하는 1건 = 1행 매핑이다
 * (daily_performance와 달리 upsert가 아니다 — 같은 종목이라도 하루 여러 지점(전략 판단,
 * RiskGate 거부)에서 여러 건 발행될 수 있고, 감사 기록이므로 모두 남긴다).
 * {@code metricsJson}은 {@code common.event.SignalDecision.metrics()}(Map&lt;String,String&gt;)를
 * JSON 문자열로 직렬화해 저장한다 — EventAuditListener가 이벤트 전체를 JSON으로 저장하는
 * 방식과 같은 이유(스키마 진화에 유연).
 */
@Entity
@Table(name = "signal_decisions")
public class SignalDecisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(name = "horizon", nullable = false, length = 16)
    private String horizon;

    @Column(name = "strategy_id", nullable = false, length = 64)
    private String strategyId;

    @Column(name = "symbol", nullable = false, length = 16)
    private String symbol;

    @Column(name = "conclusion", nullable = false, length = 16)
    private String conclusion;

    @Column(name = "reason", nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "metrics_json", nullable = false, columnDefinition = "TEXT")
    private String metricsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** JPA 전용 — 직접 사용 금지. */
    protected SignalDecisionEntity() {
    }

    public SignalDecisionEntity(Instant decidedAt, LocalDate tradeDate, String horizon, String strategyId,
                                String symbol, String conclusion, String reason, String metricsJson) {
        this.decidedAt = decidedAt;
        this.tradeDate = tradeDate;
        this.horizon = horizon;
        this.strategyId = strategyId;
        this.symbol = symbol;
        this.conclusion = conclusion;
        this.reason = reason;
        this.metricsJson = metricsJson;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Instant getDecidedAt() { return decidedAt; }
    public LocalDate getTradeDate() { return tradeDate; }
    public String getHorizon() { return horizon; }
    public String getStrategyId() { return strategyId; }
    public String getSymbol() { return symbol; }
    public String getConclusion() { return conclusion; }
    public String getReason() { return reason; }
    public String getMetricsJson() { return metricsJson; }
    public Instant getCreatedAt() { return createdAt; }
}
