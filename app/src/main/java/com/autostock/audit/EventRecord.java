package com.autostock.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * append-only 이벤트 레코드. schemaVersion으로 이벤트 계약 진화 관리.
 */
@Entity
@Table(name = "event_store")
public class EventRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false)
    private int schemaVersion;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(nullable = false)
    private Instant recordedAt;

    protected EventRecord() {
    }

    public EventRecord(String eventType, int schemaVersion, String payload, Instant occurredAt) {
        this.eventType = eventType;
        this.schemaVersion = schemaVersion;
        this.payload = payload;
        this.occurredAt = occurredAt;
        this.recordedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getEventType() { return eventType; }
    public int getSchemaVersion() { return schemaVersion; }
    public String getPayload() { return payload; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getRecordedAt() { return recordedAt; }
}
