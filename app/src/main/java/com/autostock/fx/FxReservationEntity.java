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

/**
 * 외화 목표환율 예약 한 건 — {@code fx_reservation}(V34). 데브가 은행 앱에서 신청한 예약을 기록한다.
 *
 * <p>입력 때 그 시각의 ECOS 최신값({@code refRate})을 함께 저장하고, 은행 환율과의 차이({@code spread})로 나중의
 * 은행 매수 환율을 추정한다(설계 4장). ECOS를 못 받았으면 세 값이 비고 이 예약은 검토 알림을 내지 않는다.
 * 체결은 앱이 알 수 없어 데브가 입력한다(설계 7장).
 */
@Entity
@Table(name = "fx_reservation")
public class FxReservationEntity {

    public enum Status { OPEN, FILLED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3)
    private FxCurrency currency;

    @Column(name = "requested_on", nullable = false)
    private LocalDate requestedOn;

    @Column(name = "request_rate", nullable = false, precision = 12, scale = 4)
    private BigDecimal requestRate;

    @Column(name = "reserve_rate", nullable = false, precision = 12, scale = 4)
    private BigDecimal reserveRate;

    @Column(name = "amount_krw", nullable = false)
    private long amountKrw;

    @Column(name = "ref_rate", precision = 12, scale = 4)
    private BigDecimal refRate;

    @Column(name = "ref_observed_on")
    private LocalDate refObservedOn;

    @Column(name = "spread", precision = 12, scale = 4)
    private BigDecimal spread;

    @Column(name = "expires_on", nullable = false)
    private LocalDate expiresOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Column(name = "filled_on")
    private LocalDate filledOn;

    @Column(name = "filled_rate", precision = 12, scale = 4)
    private BigDecimal filledRate;

    @Column(name = "last_review_below")
    private Boolean lastReviewBelow;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA 전용 — 직접 사용 금지. */
    protected FxReservationEntity() {
    }

    /**
     * 새 예약. 입력 규칙({@link FxReviewRule#reservationProblem})을 통과한 값만 넘긴다 — DB 제약도 같은 규칙을 한 번 더 막는다.
     *
     * @param refRate       입력 때 ECOS 최신값(못 받았으면 {@code null})
     * @param refObservedOn 그 값의 관측일(못 받았으면 {@code null})
     */
    public FxReservationEntity(FxCurrency currency, LocalDate requestedOn, BigDecimal requestRate, BigDecimal reserveRate,
                               long amountKrw, BigDecimal refRate, LocalDate refObservedOn, LocalDate expiresOn, Instant now) {
        this.currency = Objects.requireNonNull(currency);
        this.requestedOn = Objects.requireNonNull(requestedOn);
        this.requestRate = Objects.requireNonNull(requestRate);
        this.reserveRate = Objects.requireNonNull(reserveRate);
        this.amountKrw = amountKrw;
        this.refRate = refRate;
        this.refObservedOn = refRate == null ? null : refObservedOn;
        this.spread = refRate == null ? null : requestRate.subtract(refRate);
        this.expiresOn = Objects.requireNonNull(expiresOn);
        this.status = Status.OPEN;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 데브가 체결을 입력했다. 열린 예약만 체결할 수 있다. */
    public void fill(LocalDate on, BigDecimal rate, Instant now) {
        requireOpen();
        this.status = Status.FILLED;
        this.filledOn = Objects.requireNonNull(on);
        this.filledRate = Objects.requireNonNull(rate);
        this.updatedAt = now;
    }

    /** 잘못 넣었거나 은행에서 취소한 예약 — 지우지 않고 취소 상태로 둔다(설계 9장). */
    public void cancel(Instant now) {
        requireOpen();
        this.status = Status.CANCELLED;
        this.updatedAt = now;
    }

    /** 검토 판정 뒤 다음 판정에 쓸 상태(설계 6장 C6). */
    public void recordReview(Boolean below, Instant now) {
        this.lastReviewBelow = below;
        this.updatedAt = now;
    }

    private void requireOpen() {
        if (status != Status.OPEN) {
            throw new IllegalStateException("열린 예약이 아닙니다: R" + id + " " + status);
        }
    }

    /** 노출액 계산용 값({@link FxExposure}). */
    public FxExposure.Reservation exposureView() {
        return new FxExposure.Reservation(status, filledOn, amountKrw);
    }

    public Long getId() { return id; }
    public FxCurrency getCurrency() { return currency; }
    public LocalDate getRequestedOn() { return requestedOn; }
    public BigDecimal getRequestRate() { return requestRate; }
    public BigDecimal getReserveRate() { return reserveRate; }
    public long getAmountKrw() { return amountKrw; }
    public BigDecimal getRefRate() { return refRate; }
    public LocalDate getRefObservedOn() { return refObservedOn; }
    public BigDecimal getSpread() { return spread; }
    public LocalDate getExpiresOn() { return expiresOn; }
    public Status getStatus() { return status; }
    public LocalDate getFilledOn() { return filledOn; }
    public BigDecimal getFilledRate() { return filledRate; }
    public Boolean getLastReviewBelow() { return lastReviewBelow; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
