package com.autostock.risk;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 공시 기반 매수 배제 등록 1건 — {@code disclosure_blacklist} 테이블 매핑(V7, PLAN.md ADR-14
 * 트랙 G1). {@link DisclosureBlacklist}가 재시작 후에도 등록 상태를 잃지 않도록 영속화한다
 * ({@code ipo.IpoDealEntity}와 같은 Aggregate 단위 Repository 원칙, ARCHITECTURE.md 12절).
 *
 * <p>(symbol, rcept_no) 조합이 자연키다 — 같은 공시를 스케줄러가 재처리(배치 재시도, lookback
 * 창 중복)해도 중복 행이 생기지 않도록 DB 유니크 제약으로도 강제한다({@code
 * DisclosureBlacklistRepository.existsBySymbolAndRceptNo}가 애플리케이션 레벨에서 먼저 걸러도
 * 이중 안전장치로 유지).
 */
@Entity
@Table(name = "disclosure_blacklist")
public class DisclosureBlacklistEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "symbol", nullable = false, length = 12)
    private String symbol;

    @Column(name = "corp_name", length = 128)
    private String corpName;

    @Column(name = "disclosure_type", nullable = false, length = 32)
    private String disclosureType;

    @Column(name = "rcept_no", nullable = false, length = 20)
    private String rceptNo;

    @Column(name = "rcept_dt", nullable = false)
    private LocalDate rceptDt;

    @Column(name = "expires_on", nullable = false)
    private LocalDate expiresOn;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** JPA 전용 — 직접 사용 금지. */
    protected DisclosureBlacklistEntity() {
    }

    public DisclosureBlacklistEntity(String symbol, String corpName, String disclosureType,
                                      String rceptNo, LocalDate rceptDt, LocalDate expiresOn) {
        this.symbol = symbol;
        this.corpName = corpName;
        this.disclosureType = disclosureType;
        this.rceptNo = rceptNo;
        this.rceptDt = rceptDt;
        this.expiresOn = expiresOn;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getSymbol() { return symbol; }
    public String getCorpName() { return corpName; }
    public String getDisclosureType() { return disclosureType; }
    public String getRceptNo() { return rceptNo; }
    public LocalDate getRceptDt() { return rceptDt; }
    public LocalDate getExpiresOn() { return expiresOn; }
    public Instant getCreatedAt() { return createdAt; }
}
