package com.autostock.ipo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * 지난 공모 하나의 인수인 — {@code ipo_underwriting_history}(V35, 2026-11-13, 결정 D-29). 계좌 개설 요청이 1·3·5년 실적을 센다.
 * DART에서 거꾸로 채운다({@link IpoUnderwriterBackfill}). 회사마다 한 행.
 */
@Entity
@Table(name = "ipo_underwriting_history")
public class IpoUnderwritingHistoryEntity {

    static final String SOURCE_BACKFILL = "BACKFILL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "corp_code", nullable = false, length = 16)
    private String corpCode;

    @Column(name = "corp_name", nullable = false, length = 128)
    private String corpName;

    @Column(name = "rcept_no", nullable = false, length = 20)
    private String rceptNo;

    @Column(name = "filed_on", nullable = false)
    private LocalDate filedOn;

    @Column(name = "subscription_start")
    private LocalDate subscriptionStart;

    @Column(name = "underwriters", nullable = false, length = 512)
    private String underwriters;

    @Column(name = "source", nullable = false, length = 16)
    private String source;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA 전용 — 직접 사용 금지. */
    protected IpoUnderwritingHistoryEntity() {
    }

    IpoUnderwritingHistoryEntity(String corpCode, String corpName, String rceptNo, LocalDate filedOn, LocalDate subscriptionStart,
                                 List<String> underwriters, Instant now) {
        this.corpCode = corpCode;
        this.corpName = corpName;
        this.source = SOURCE_BACKFILL;
        update(rceptNo, filedOn, subscriptionStart, underwriters, now);
    }

    /** 같은 회사의 더 나중 공모로 덮는다. */
    void update(String rceptNo, LocalDate filedOn, LocalDate subscriptionStart, List<String> underwriters, Instant now) {
        this.rceptNo = rceptNo;
        this.filedOn = filedOn;
        this.subscriptionStart = subscriptionStart;
        String joined = String.join(", ", underwriters);
        this.underwriters = joined.length() > 512 ? joined.substring(0, 512) : joined;
        this.updatedAt = now;
    }

    /** 실적을 세는 날 — 청약 시작일, 모르면 신고서 접수일. */
    public LocalDate countedOn() {
        return subscriptionStart != null ? subscriptionStart : filedOn;
    }

    public List<String> getUnderwriterList() {
        return underwriters == null || underwriters.isBlank() ? List.of()
                : Arrays.stream(underwriters.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    public Long getId() { return id; }
    public String getCorpCode() { return corpCode; }
    public String getCorpName() { return corpName; }
    public String getRceptNo() { return rceptNo; }
    public LocalDate getFiledOn() { return filedOn; }
    public LocalDate getSubscriptionStart() { return subscriptionStart; }
    public String getSource() { return source; }
    public Instant getUpdatedAt() { return updatedAt; }
}
