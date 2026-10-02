package com.autostock.ipo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 공모주 딜 Aggregate — {@code ipo_deals} 테이블 매핑(V6, PLAN.md ADR-9 트랙 E2).
 *
 * <p>rcept_no(DART 접수번호) 기준으로 upsert된다 — 동일 딜의 정정 공시([기재정정])가 들어오면
 * 같은 corp_code로 새 rcept_no가 발급되므로({@link DartClient} 실측 확인: 한울반도체가
 * "[기재정정]증권신고서(지분증권)" 상태) {@link IpoSyncScheduler}는 corp_code 기준 최신
 * rcept_no 행을 유지하는 방식이 아니라, 각 rcept_no를 독립 행으로 upsert한다(감사 추적
 * 목적 — 정정 이력 자체가 남아야 한다는 2절 (4) 원칙). 화면(GET /api/ipo)은 corp_code별
 * 최신 행만 보여줄지 여부를 API/FE 단에서 추가로 정리할 수 있으나, 이번 구현 범위에서는
 * 딜(=rcept_no) 단위 목록을 그대로 반환한다.
 */
@Entity
@Table(name = "ipo_deals")
public class IpoDealEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "corp_code", nullable = false, length = 16)
    private String corpCode;

    @Column(name = "corp_name", nullable = false, length = 128)
    private String corpName;

    @Column(name = "rcept_no", nullable = false, length = 20, unique = true)
    private String rceptNo;

    @Column(name = "offer_price_low")
    private BigDecimal offerPriceLow;

    @Column(name = "offer_price_high")
    private BigDecimal offerPriceHigh;

    @Column(name = "offer_price_confirmed")
    private BigDecimal offerPriceConfirmed;

    @Column(name = "subscription_start")
    private LocalDate subscriptionStart;

    @Column(name = "subscription_end")
    private LocalDate subscriptionEnd;

    @Column(name = "refund_date")
    private LocalDate refundDate;

    @Column(name = "listing_date")
    private LocalDate listingDate;

    @Column(name = "lead_manager", length = 256)
    private String leadManager;

    @Column(name = "institutional_competition_rate")
    private BigDecimal institutionalCompetitionRate;

    @Column(name = "lockup_commit_rate")
    private BigDecimal lockupCommitRate;

    /** 기관경쟁률·확약비율의 출처(V11) — DART(수요예측 결과 자동) | MANUAL(수동 입력) | null(아직 없음). */
    @Enumerated(EnumType.STRING)
    @Column(name = "metrics_source", length = 16)
    private MetricsSource metricsSource;

    /** 자동 입력에 쓴 [발행조건확정] 신고서 접수번호(V11) — 수동 입력이면 null. */
    @Column(name = "metrics_rcept_no", length = 20)
    private String metricsRceptNo;

    /** 공모 종류(V11) — IPO(공모주) | RIGHTS(상장사 유상증자, 화면·권고·알림 제외) | null(아직 판정 못 함, 공모주로 취급). */
    @Enumerated(EnumType.STRING)
    @Column(name = "offering_kind", length = 16)
    private OfferingKind offeringKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private IpoStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "recommendation", nullable = false, length = 16)
    private IpoRecommendation recommendation;

    @Column(name = "recommend_reason", columnDefinition = "TEXT")
    private String recommendReason;

    @Column(name = "applied_qty")
    private Integer appliedQty;

    @Column(name = "deposit")
    private BigDecimal deposit;

    @Column(name = "allocated_qty")
    private Integer allocatedQty;

    @Column(name = "sell_price")
    private BigDecimal sellPrice;

    @Column(name = "sell_date")
    private LocalDate sellDate;

    @Column(name = "memo", columnDefinition = "TEXT")
    private String memo;

    @Column(name = "source", nullable = false, length = 16)
    private String source;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 낙관적 잠금 버전(V8) — 배치와 수동 입력이 같은 딜을 동시에 갱신할 때 나중 저장이 앞 입력을 덮지 않게 한다. */
    @Version
    @Column(nullable = false)
    private long version;

    /** JPA 전용 — 직접 사용 금지. */
    protected IpoDealEntity() {
    }

    /** 시각은 호출부가 주입 Clock에서 넘긴다(A4 ③, aiDoc/clock-injection.md) — 엔티티는 시계에 직접 접근하지 않는다. */
    public IpoDealEntity(String corpCode, String corpName, String rceptNo, String source, Instant now) {
        this.corpCode = corpCode;
        this.corpName = corpName;
        this.rceptNo = rceptNo;
        this.status = IpoStatus.UPCOMING;
        this.recommendation = IpoRecommendation.PENDING;
        this.recommendReason = "지표 미확보 — [발행조건확정] 수요예측 결과 공시 전(공시되면 자동 입력, 수동 입력도 가능)";
        this.source = source;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** DART estkRs.json 상세 조회 결과로 일정·주관사·공모가를 갱신한다(정정 공시 반영). */
    public void applyOfferingDetail(DartClient.OfferingDetail detail, Instant now) {
        this.subscriptionStart = detail.subscriptionStart();
        this.subscriptionEnd = detail.subscriptionEnd();
        this.refundDate = detail.refundDate();
        this.leadManager = detail.leadManager();
        this.offerPriceConfirmed = detail.offerPriceConfirmed();
        touch(now);
    }

    /** IpoSyncScheduler가 매 배치 오늘 날짜 기준으로 재계산한 상태를 반영한다. */
    public void updateStatus(IpoStatus newStatus, Instant now) {
        if (this.status != newStatus) {
            this.status = newStatus;
            touch(now);
        }
    }

    /** 필터 평가 결과를 반영한다(IpoSyncScheduler). */
    public void applyRecommendation(IpoRecommendation recommendation, String reason, Instant now) {
        this.recommendation = recommendation;
        this.recommendReason = reason;
        touch(now);
    }

    /** 기관경쟁률·의무보유확약비율 수동 입력(POST /api/ipo/{id}/metrics). */
    public void applyMetrics(BigDecimal institutionalCompetitionRate, BigDecimal lockupCommitRate, Instant now) {
        applyMetrics(institutionalCompetitionRate, lockupCommitRate, null, now);
    }

    /**
     * 수동 지표 + 상장(예정)일 입력(POST /api/ipo/{id}/metrics). null 파라미터는 기존 값을 유지한다
     * (부분 갱신 — 상장일만 넣을 때 경쟁률이 지워지지 않도록, 2026-09-23). 상장일은 DART 배치가
     * 덮어쓰지 않는다({@link #applyOfferingDetail}는 listingDate를 건드리지 않음).
     */
    public void applyMetrics(BigDecimal institutionalCompetitionRate, BigDecimal lockupCommitRate,
                             LocalDate listingDate, Instant now) {
        if (institutionalCompetitionRate != null) this.institutionalCompetitionRate = institutionalCompetitionRate;
        if (lockupCommitRate != null) this.lockupCommitRate = lockupCommitRate;
        if (listingDate != null) this.listingDate = listingDate;
        if (institutionalCompetitionRate != null || lockupCommitRate != null) {
            // 사람이 넣은 지표는 자동 입력이 덮지 않는다(2026-10-02, aiDoc/ipo-demand-forecast.md)
            this.metricsSource = MetricsSource.MANUAL;
            this.metricsRceptNo = null;
        }
        touch(now);
    }

    /**
     * DART [발행조건확정] 수요예측 결과 자동 입력. 수동 입력이 있으면 덮지 않는다 — 그때는 false.
     * 읽지 못한 지표(null)는 기존 값을 지우지 않는다.
     */
    public boolean applyDemandForecast(BigDecimal institutionalCompetitionRate, BigDecimal lockupCommitRate,
                                       String rceptNo, Instant now) {
        if (metricsSource == MetricsSource.MANUAL) {
            return false;
        }
        if (institutionalCompetitionRate != null) this.institutionalCompetitionRate = institutionalCompetitionRate;
        if (lockupCommitRate != null) this.lockupCommitRate = lockupCommitRate;
        this.metricsSource = MetricsSource.DART;
        this.metricsRceptNo = rceptNo;
        touch(now);
        return true;
    }

    /** 공모 종류 판정을 남긴다 — 같은 값이면 바꾸지 않는다. */
    public void classifyOffering(OfferingKind kind, Instant now) {
        if (kind != null && kind != this.offeringKind) {
            this.offeringKind = kind;
            touch(now);
        }
    }

    /** 상장사 유상증자로 판정된 딜 — 공모주 화면·권고·알림에서 뺀다. 판정 전(null)은 공모주로 둔다. */
    public boolean isRightsOffering() {
        return offeringKind == OfferingKind.RIGHTS;
    }

    /** 지표를 사람이 넣었는지 — 자동 입력 대상에서 뺀다. */
    public boolean hasManualMetrics() {
        return metricsSource == MetricsSource.MANUAL;
    }

    /** 내 청약/배정/매도 기록(POST /api/ipo/{id}/record). null 파라미터는 값을 지우지 않고 유지한다. */
    public void applyRecord(Integer appliedQty, BigDecimal deposit, Integer allocatedQty,
                             BigDecimal sellPrice, LocalDate sellDate, String memo, Instant now) {
        if (appliedQty != null) this.appliedQty = appliedQty;
        if (deposit != null) this.deposit = deposit;
        if (allocatedQty != null) this.allocatedQty = allocatedQty;
        if (sellPrice != null) this.sellPrice = sellPrice;
        if (sellDate != null) this.sellDate = sellDate;
        if (memo != null) this.memo = memo;
        touch(now);
    }

    private void touch(Instant now) {
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public String getCorpCode() { return corpCode; }
    public String getCorpName() { return corpName; }
    public String getRceptNo() { return rceptNo; }
    public BigDecimal getOfferPriceLow() { return offerPriceLow; }
    public BigDecimal getOfferPriceHigh() { return offerPriceHigh; }
    public BigDecimal getOfferPriceConfirmed() { return offerPriceConfirmed; }
    public LocalDate getSubscriptionStart() { return subscriptionStart; }
    public LocalDate getSubscriptionEnd() { return subscriptionEnd; }
    public LocalDate getRefundDate() { return refundDate; }
    public LocalDate getListingDate() { return listingDate; }
    public String getLeadManager() { return leadManager; }
    public BigDecimal getInstitutionalCompetitionRate() { return institutionalCompetitionRate; }
    public BigDecimal getLockupCommitRate() { return lockupCommitRate; }
    public MetricsSource getMetricsSource() { return metricsSource; }
    public String getMetricsRceptNo() { return metricsRceptNo; }
    public OfferingKind getOfferingKind() { return offeringKind; }
    public IpoStatus getStatus() { return status; }
    public IpoRecommendation getRecommendation() { return recommendation; }
    public String getRecommendReason() { return recommendReason; }
    public Integer getAppliedQty() { return appliedQty; }
    public BigDecimal getDeposit() { return deposit; }
    public Integer getAllocatedQty() { return allocatedQty; }
    public BigDecimal getSellPrice() { return sellPrice; }
    public LocalDate getSellDate() { return sellDate; }
    public String getMemo() { return memo; }
    public String getSource() { return source; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** 기관경쟁률·확약비율의 출처. */
    public enum MetricsSource {
        /** [발행조건확정]증권신고서의 수요예측 결과에서 자동 입력. */
        DART,
        /** 화면·API로 사람이 입력 — 자동 입력이 덮지 않는다. */
        MANUAL
    }
}
