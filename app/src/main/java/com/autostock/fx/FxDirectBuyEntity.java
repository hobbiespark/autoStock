package com.autostock.fx;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** 외화 직접매수 한 건 — {@code fx_direct_buy}(V34). 데브가 은행 앱에서 산 뒤 입력한다. */
@Entity
@Table(name = "fx_direct_buy")
public class FxDirectBuyEntity {

    public enum Status { DONE, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3)
    private FxCurrency currency;

    @Column(name = "bought_on", nullable = false)
    private LocalDate boughtOn;

    @Column(name = "rate", nullable = false, precision = 12, scale = 4)
    private BigDecimal rate;

    @Column(name = "amount_krw", nullable = false)
    private long amountKrw;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA 전용 — 직접 사용 금지. */
    protected FxDirectBuyEntity() {
    }

    public FxDirectBuyEntity(FxCurrency currency, LocalDate boughtOn, BigDecimal rate, long amountKrw, Instant now) {
        this.currency = Objects.requireNonNull(currency);
        this.boughtOn = Objects.requireNonNull(boughtOn);
        this.rate = Objects.requireNonNull(rate);
        this.amountKrw = amountKrw;
        this.status = Status.DONE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 잘못 넣은 기록 — 지우지 않고 취소 상태로 둔다. */
    public void cancel(Instant now) {
        if (status != Status.DONE) {
            throw new IllegalStateException("이미 취소된 직접매수입니다: B" + id);
        }
        this.status = Status.CANCELLED;
        this.updatedAt = now;
    }

    /** 노출액 계산용 값({@link FxExposure}). */
    public FxExposure.Buy exposureView() {
        return new FxExposure.Buy(status == Status.DONE, boughtOn, amountKrw);
    }

    public Long getId() { return id; }
    public FxCurrency getCurrency() { return currency; }
    public LocalDate getBoughtOn() { return boughtOn; }
    public BigDecimal getRate() { return rate; }
    public long getAmountKrw() { return amountKrw; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
