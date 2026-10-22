package com.autostock.ipo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 공모주 한 곳의 판단 재료와 상장일 성과 — {@code ipo_listing_history}(V19, 2026-10-15 청약 추천 전략).
 *
 * <p>청약 추천({@link IpoSubscriptionAdvisor})의 시장 맥락을 여기서 센다 — 확약은 "직전 20건 중앙값" 대비, 분위기는 "직전 상장 5곳의
 * 상장일 종가 수익률 평균". 딜 표(ipo_deals)는 정정 신고서마다 한 행이고 앱이 켜진 뒤(2026-09)의 딜만 있어서, 회사마다 한 행인
 * 이 표를 따로 둔다. 2025-04~2026-10 공모주 85곳은 V19가 넣는다({@code source=SEED}, 검증 자료 data/ipo/history와 같은 값).
 * 그 뒤로는 앱이 채운다({@code source=APP}) — 수요예측 결과를 읽으면 판단 재료를, 상장일 15:40에 시가·종가를.
 */
@Entity
@Table(name = "ipo_listing_history")
public class IpoListingHistoryEntity {

    @Id
    @Column(name = "corp_code", length = 16)
    private String corpCode;

    @Column(name = "corp_name", nullable = false, length = 128)
    private String corpName;

    @Column(name = "stock_code", length = 12)
    private String stockCode;

    /** 판단 시점 — [발행조건확정] 접수일(수동 입력이면 입력한 날). */
    @Column(name = "confirmed_on")
    private LocalDate confirmedOn;

    @Column(name = "competition_rate")
    private BigDecimal competitionRate;

    @Column(name = "lockup_rate")
    private BigDecimal lockupRate;

    @Column(name = "offer_price")
    private BigDecimal offerPrice;

    @Column(name = "band_low")
    private BigDecimal bandLow;

    @Column(name = "band_high")
    private BigDecimal bandHigh;

    @Column(name = "float_ratio")
    private BigDecimal floatRatio;

    @Column(name = "listing_date")
    private LocalDate listingDate;

    @Column(name = "listing_open")
    private BigDecimal listingOpen;

    @Column(name = "listing_close")
    private BigDecimal listingClose;

    /** 앱이 매긴 청약 추천 — 상장하면 더 바꾸지 않는다(전진 검증: 알린 등급과 상장일 성과를 견준다). */
    @Column(name = "advice_grade", length = 16)
    private String adviceGrade;

    @Column(name = "advice_score")
    private Integer adviceScore;

    @Column(name = "source", nullable = false, length = 16)
    private String source;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA 전용 — 직접 사용 금지. */
    protected IpoListingHistoryEntity() {
    }

    public IpoListingHistoryEntity(String corpCode, String corpName, Instant now) {
        this.corpCode = corpCode;
        this.corpName = corpName;
        this.source = "APP";
        this.updatedAt = now;
    }

    /** 수요예측 결과가 나온 딜의 판단 재료 — 비어 있는 값은 기존 값을 지우지 않는다. */
    void applyInputs(String corpName, LocalDate confirmedOn, BigDecimal competitionRate, BigDecimal lockupRate,
                     BigDecimal offerPrice, BigDecimal bandLow, BigDecimal bandHigh, BigDecimal floatRatio, Instant now) {
        applyInputs(corpName, confirmedOn, competitionRate, lockupRate, offerPrice, bandLow, bandHigh, floatRatio, null, null, now);
    }

    void applyInputs(String corpName, LocalDate confirmedOn, BigDecimal competitionRate, BigDecimal lockupRate,
                     BigDecimal offerPrice, BigDecimal bandLow, BigDecimal bandHigh, BigDecimal floatRatio,
                     String adviceGrade, Integer adviceScore, Instant now) {
        this.corpName = corpName;
        if (adviceGrade != null && listingDate == null) {
            this.adviceGrade = adviceGrade;
            this.adviceScore = adviceScore;
        }
        if (confirmedOn != null) this.confirmedOn = confirmedOn;
        if (competitionRate != null) this.competitionRate = competitionRate;
        if (lockupRate != null) this.lockupRate = lockupRate;
        if (offerPrice != null) this.offerPrice = offerPrice;
        if (bandLow != null) this.bandLow = bandLow;
        if (bandHigh != null) this.bandHigh = bandHigh;
        if (floatRatio != null) this.floatRatio = floatRatio;
        this.updatedAt = now;
    }

    /** 상장일 성과(15:40 키움 일봉). */
    void applyListing(String stockCode, LocalDate listingDate, BigDecimal open, BigDecimal close, BigDecimal offerPrice,
                      Instant now) {
        if (stockCode != null) this.stockCode = stockCode;
        if (listingDate != null) this.listingDate = listingDate;
        if (open != null) this.listingOpen = open;
        if (close != null) this.listingClose = close;
        if (this.offerPrice == null && offerPrice != null) this.offerPrice = offerPrice;
        this.updatedAt = now;
    }

    /** 상장일 종가 수익률(0.42 = +42%) — 공모가·종가를 모르면 null. */
    BigDecimal closeReturn() {
        if (offerPrice == null || offerPrice.signum() <= 0 || listingClose == null) {
            return null;
        }
        return listingClose.divide(offerPrice, 6, RoundingMode.HALF_UP).subtract(BigDecimal.ONE);
    }

    public String getCorpCode() { return corpCode; }
    public String getCorpName() { return corpName; }
    public String getStockCode() { return stockCode; }
    public LocalDate getConfirmedOn() { return confirmedOn; }
    public BigDecimal getCompetitionRate() { return competitionRate; }
    public BigDecimal getLockupRate() { return lockupRate; }
    public BigDecimal getOfferPrice() { return offerPrice; }
    public BigDecimal getBandLow() { return bandLow; }
    public BigDecimal getBandHigh() { return bandHigh; }
    public BigDecimal getFloatRatio() { return floatRatio; }
    public LocalDate getListingDate() { return listingDate; }
    public BigDecimal getListingOpen() { return listingOpen; }
    public BigDecimal getListingClose() { return listingClose; }
    public String getAdviceGrade() { return adviceGrade; }
    public Integer getAdviceScore() { return adviceScore; }
    public String getSource() { return source; }
    public Instant getUpdatedAt() { return updatedAt; }
}
