package com.autostock.fx;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 보낸 외화 알림 한 건 — {@code fx_alert_log}(V34). 종류·통화·기준값마다 한 번만 보낸다(유일 제약, {@code ipo_alert_log}와 같은 방식).
 */
@Entity
@Table(name = "fx_alert_log")
public class FxAlertLogEntity {

    public static final String KIND_REVIEW = "REVIEW";
    public static final String KIND_MONTH_START = "MONTH_START";
    public static final String KIND_MONTH_END = "MONTH_END";
    /** 월초·월말 알림의 통화 칸. */
    public static final String ALL_CURRENCIES = "-";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "alert_key", nullable = false, length = 32)
    private String alertKey;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    /** JPA 전용 — 직접 사용 금지. */
    protected FxAlertLogEntity() {
    }

    public FxAlertLogEntity(String kind, String currency, String alertKey, Instant sentAt) {
        this.kind = kind;
        this.currency = currency;
        this.alertKey = alertKey;
        this.sentAt = sentAt;
    }

    /** 검토 알림의 기준값 — 같은 예약·같은 ECOS 관측일에는 한 번. */
    public static String reviewKey(long reservationId, LocalDate observedOn) {
        return "R" + reservationId + "|" + observedOn;
    }

    /** 월초·월말 알림의 기준값. */
    public static String monthKey(YearMonth month) {
        return month.toString();
    }

    public Long getId() { return id; }
    public String getKind() { return kind; }
    public String getCurrency() { return currency; }
    public String getAlertKey() { return alertKey; }
    public Instant getSentAt() { return sentAt; }
}
