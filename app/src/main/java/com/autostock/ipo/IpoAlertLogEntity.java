package com.autostock.ipo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 보낸 공모주 알림 한 건 — {@code ipo_alert_log}(V18, 2026-10-13). 회사·단계·기준값마다 한 번만 보낸다(유일 제약).
 *
 * <p>V16은 딜마다 "마지막 알림 단계와 날짜" 하나만 남겨 단계가 늘면 앞 단계 기록이 지워졌다. 단계(신규·수요예측 결과·청약 D-1·시작·마감·
 * 환불·상장 D-1·상장)가 여덟 개로 늘어 단계별 기록으로 바꿨다. 기준값({@code alert_key})은 그 단계를 정하는 값이다 — 청약 시작일,
 * [발행조건확정] 접수번호 등. 정정 신고로 일정이 바뀌면 새 기준값이라 다시 알린다.
 */
@Entity
@Table(name = "ipo_alert_log")
public class IpoAlertLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "corp_code", nullable = false, length = 16)
    private String corpCode;

    @Column(name = "phase", nullable = false, length = 16)
    private String phase;

    @Column(name = "alert_key", nullable = false, length = 32)
    private String alertKey;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    /** JPA 전용 — 직접 사용 금지. */
    protected IpoAlertLogEntity() {
    }

    public IpoAlertLogEntity(String corpCode, String phase, String alertKey, Instant sentAt) {
        this.corpCode = corpCode;
        this.phase = phase;
        this.alertKey = alertKey;
        this.sentAt = sentAt;
    }

    /** 같은 알림인지 견주는 열쇠 — 회사|단계|기준값. */
    String key() {
        return key(corpCode, phase, alertKey);
    }

    static String key(String corpCode, String phase, String alertKey) {
        return corpCode + "|" + phase + "|" + alertKey;
    }

    public Long getId() { return id; }
    public String getCorpCode() { return corpCode; }
    public String getPhase() { return phase; }
    public String getAlertKey() { return alertKey; }
    public Instant getSentAt() { return sentAt; }
}
