package com.autostock.ipo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/** DART 거꾸로 채우기 진행 위치 — {@code ipo_backfill_progress}(V35). {@code coveredFrom}부터 첫 실행 날까지 읽었다. */
@Entity
@Table(name = "ipo_backfill_progress")
public class IpoBackfillProgressEntity {

    @Id
    @Column(name = "name", length = 32)
    private String name;

    @Column(name = "target_from", nullable = false)
    private LocalDate targetFrom;

    @Column(name = "covered_from", nullable = false)
    private LocalDate coveredFrom;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA 전용 — 직접 사용 금지. */
    protected IpoBackfillProgressEntity() {
    }

    IpoBackfillProgressEntity(String name, LocalDate targetFrom, LocalDate coveredFrom, Instant now) {
        this.name = name;
        this.targetFrom = targetFrom;
        this.coveredFrom = coveredFrom;
        this.updatedAt = now;
    }

    void advance(LocalDate coveredFrom, Instant now) {
        this.coveredFrom = coveredFrom;
        this.updatedAt = now;
    }

    boolean done() {
        return !coveredFrom.isAfter(targetFrom);
    }

    public String getName() { return name; }
    public LocalDate getTargetFrom() { return targetFrom; }
    public LocalDate getCoveredFrom() { return coveredFrom; }
    public Instant getUpdatedAt() { return updatedAt; }
}
