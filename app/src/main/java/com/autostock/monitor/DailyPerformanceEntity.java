package com.autostock.monitor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 일별 성과 스냅샷 Aggregate — {@code daily_performance} 테이블 매핑(V4 마이그레이션, FE-2).
 *
 * <p>{@code trade_date} UNIQUE 제약이 하루 1행을 보장한다. {@link DailyPerformanceService}가
 * "있으면 갱신, 없으면 생성" 방식으로 upsert한다(DailyReportScheduler의 15:50 스케줄과 같은
 * 트랜잭션에서 저장) — 재기동 등으로 같은 날 스케줄이 다시 실행돼도 행이 갱신될 뿐 중복
 * 생성되지 않는다.
 */
@Entity
@Table(name = "daily_performance")
public class DailyPerformanceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trade_date", nullable = false, unique = true)
    private LocalDate tradeDate;

    @Column(name = "realized_pnl", nullable = false, precision = 19, scale = 4)
    private BigDecimal realizedPnl;

    @Column(name = "order_count", nullable = false)
    private int orderCount;

    @Column(name = "fill_count", nullable = false)
    private int fillCount;

    @Column(name = "avg_slippage_bps", nullable = false)
    private double avgSlippageBps;

    @Column(name = "max_slippage_bps", nullable = false)
    private double maxSlippageBps;

    @Column(name = "conservative_mode", nullable = false)
    private boolean conservativeMode;

    @Column(name = "kill_switch_engaged", nullable = false)
    private boolean killSwitchEngaged;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** JPA 전용 — 직접 사용 금지. */
    protected DailyPerformanceEntity() {
    }

    /** 새 스냅샷 생성 — upsert의 "없으면 생성" 분기에서만 호출된다. */
    public DailyPerformanceEntity(LocalDate tradeDate) {
        this.tradeDate = tradeDate;
        this.createdAt = Instant.now();
    }

    /** 그날 집계값을 통째로 덮어쓴다 — upsert의 "있으면 갱신" 분기(및 최초 생성 직후)에서 호출. */
    public void update(BigDecimal realizedPnl, int orderCount, int fillCount,
                       double avgSlippageBps, double maxSlippageBps,
                       boolean conservativeMode, boolean killSwitchEngaged) {
        this.realizedPnl = realizedPnl;
        this.orderCount = orderCount;
        this.fillCount = fillCount;
        this.avgSlippageBps = avgSlippageBps;
        this.maxSlippageBps = maxSlippageBps;
        this.conservativeMode = conservativeMode;
        this.killSwitchEngaged = killSwitchEngaged;
    }

    public Long getId() { return id; }
    public LocalDate getTradeDate() { return tradeDate; }
    public BigDecimal getRealizedPnl() { return realizedPnl; }
    public int getOrderCount() { return orderCount; }
    public int getFillCount() { return fillCount; }
    public double getAvgSlippageBps() { return avgSlippageBps; }
    public double getMaxSlippageBps() { return maxSlippageBps; }
    public boolean isConservativeMode() { return conservativeMode; }
    public boolean isKillSwitchEngaged() { return killSwitchEngaged; }
    public Instant getCreatedAt() { return createdAt; }
}
