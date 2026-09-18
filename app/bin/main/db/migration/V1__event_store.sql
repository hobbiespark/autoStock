-- 이벤트 스토어: append-only, 감사 추적 + 백테스트 리플레이 원천 (PLAN ADR-2)
CREATE TABLE event_store (
    id             BIGSERIAL PRIMARY KEY,
    event_type     VARCHAR(64)  NOT NULL,
    schema_version INT          NOT NULL,
    payload        TEXT         NOT NULL,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    recorded_at    TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_event_store_occurred ON event_store (occurred_at);
CREATE INDEX idx_event_store_type_occurred ON event_store (event_type, occurred_at);

-- Spring Modulith 이벤트 발행 로그 (트랜잭셔널 아웃박스)
CREATE TABLE event_publication (
    id               UUID PRIMARY KEY,
    listener_id      TEXT        NOT NULL,
    event_type       TEXT        NOT NULL,
    serialized_event TEXT        NOT NULL,
    publication_date TIMESTAMPTZ NOT NULL,
    completion_date  TIMESTAMPTZ
);

CREATE INDEX idx_event_publication_incomplete ON event_publication (completion_date) WHERE completion_date IS NULL;
