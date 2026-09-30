package com.autostock.risk;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * 킬스위치 상태 — {@code risk_state} 단일 행(V9, Phase 0.2). {@link KillSwitch}가 작동·해제 때마다 저장하고
 * 기동 시 복원한다({@link RiskStateStore}).
 *
 * <p>사유({@code kill_switch_reason})는 마지막 작동 사유다 — 해제해도 지우지 않아 "무엇을 해제했는지" 남는다.
 * 해제자({@code changed_by})는 해제 때만 기록한다(작동 주체는 사유 문구에 들어 있다).
 */
@Entity
@Table(name = "risk_state")
public class RiskStateEntity {

    /** 단일 행 키 — V9가 {@code CHECK (id = 1)}로 강제한다. */
    static final short SINGLETON_ID = 1;

    @Id
    @Column(name = "id", nullable = false)
    private Short id;

    @Column(name = "kill_switch_engaged", nullable = false)
    private boolean killSwitchEngaged;

    @Column(name = "kill_switch_reason", columnDefinition = "TEXT")
    private String killSwitchReason;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    @Column(name = "changed_by", columnDefinition = "TEXT")
    private String changedBy;

    /**
     * 낙관적 잠금(V8과 같은 방식). 래퍼 타입이라 새 행(null)은 persist, 기존 행은 merge로 구분된다 —
     * 원시 타입이면 id를 직접 넣는 이 엔티티는 항상 merge로 가고, 행이 없을 때 Hibernate 6.6이 예외를 던진다.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** JPA 전용 — 직접 사용 금지. */
    protected RiskStateEntity() {
    }

    /** 행이 없을 때(수동 삭제 등) 새로 만드는 단일 행 — 해제 상태. 시각은 호출부가 주입 Clock에서 넘긴다. */
    static RiskStateEntity newSingleton(Instant now) {
        RiskStateEntity state = new RiskStateEntity();
        state.id = SINGLETON_ID;
        state.killSwitchEngaged = false;
        state.changedAt = now;
        return state;
    }

    void engage(String reason, Instant now) {
        this.killSwitchEngaged = true;
        this.killSwitchReason = reason;
        this.changedBy = null;
        this.changedAt = now;
    }

    void release(String operator, Instant now) {
        this.killSwitchEngaged = false;
        this.changedBy = operator;
        this.changedAt = now;
    }

    public Short getId() { return id; }
    public boolean isKillSwitchEngaged() { return killSwitchEngaged; }
    public String getKillSwitchReason() { return killSwitchReason; }
    public Instant getChangedAt() { return changedAt; }
    public String getChangedBy() { return changedBy; }
    public Long getVersion() { return version; }
}
