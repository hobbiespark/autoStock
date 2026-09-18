-- 종목 선정 이유(지평별) 판단 스냅샷(monitor 모듈) — FE-6 화면(PLAN.md ADR-10 확장표)의
-- 데이터 소스이자, "왜 오늘 이 종목을 사고/안 샀나"를 재구성할 수 있어야 한다는 감사
-- 요구사항(2절 (4))의 구현이다.
--
-- strategy 모듈(C3LiveStrategy 09:05 판단 루프)과 risk 모듈(RiskGate 게이트 거부 지점)
-- 양쪽에서 SignalDecision 이벤트가 발행되고, 이 테이블은 그 이벤트를 그대로 append한다
-- (daily_performance처럼 "그날의 집계"가 아니라, 이벤트 스토어(V1)처럼 append-only 원본에
-- 가깝다 — 다만 event_store는 전체 이벤트 유형을 뭉뚱그려 저장하고 이 테이블은 FE-6 화면이
-- 바로 조회할 수 있도록 SignalDecision 전용 컬럼으로 구조화한다는 점이 다르다).
CREATE TABLE signal_decisions (
    id            BIGSERIAL       PRIMARY KEY,
    decided_at    TIMESTAMPTZ     NOT NULL,
    trade_date    DATE            NOT NULL,
    horizon       VARCHAR(16)     NOT NULL,
    strategy_id   VARCHAR(64)     NOT NULL,
    symbol        VARCHAR(16)     NOT NULL,
    conclusion    VARCHAR(16)     NOT NULL,
    reason        TEXT            NOT NULL,
    metrics_json  TEXT            NOT NULL,
    created_at    TIMESTAMPTZ     NOT NULL
);

-- GET /api/decisions?date=YYYY-MM-DD 가 trade_date로 조회해 horizon,symbol 순으로
-- 정렬 반환하므로(FE가 화면에서 재그룹) (trade_date, horizon) 복합 인덱스를 둔다.
CREATE INDEX idx_signal_decisions_trade_date_horizon ON signal_decisions (trade_date, horizon);
