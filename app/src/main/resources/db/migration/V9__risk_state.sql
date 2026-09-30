-- 리스크 상태 영속화 (Phase 0.2, aiDoc/risk-state-persistence.md) — risk 모듈 소유 테이블.
--
-- 킬스위치와 일 손실 누계가 메모리에만 있어 재기동(auto-start 포함)하면 비상 정지가 풀리고 일 손실이
-- 0이 되던 결함(감사 BE-P0-2)을 막는다. 사용자 결정 D-05(2026-10-01): 킬스위치·일 손실·원가 장부 모두 복원.
-- (원가 장부는 테이블 없이 브로커 잔고 복원 이벤트 PositionRestored로 시드한다.)

-- 킬스위치 상태 — 단일 행(id = 1). risk.KillSwitch가 작동·해제 때마다 갱신하고, 기동 시 이 행으로 복원한다.
-- 해제는 여전히 사람만 한다(KillSwitch 클래스 설명).
CREATE TABLE risk_state (
    id                   SMALLINT     PRIMARY KEY CHECK (id = 1),
    kill_switch_engaged  BOOLEAN      NOT NULL,
    kill_switch_reason   TEXT,                    -- 마지막 작동 사유(해제 뒤에도 남겨 무엇을 해제했는지 기록)
    changed_at           TIMESTAMPTZ  NOT NULL,
    changed_by           TEXT,                    -- 해제한 사람(dashboard·telegram). 작동 때는 NULL — 작동 주체는 사유 문구에 있다
    version              BIGINT       NOT NULL DEFAULT 0
);

-- 배포 직후 첫 기동은 지금과 같이 해제 상태로 시작한다.
INSERT INTO risk_state (id, kill_switch_engaged, kill_switch_reason, changed_at, changed_by)
VALUES (1, FALSE, NULL, now(), 'V9 초기값');

-- 일별 실현손익 누계(KST 거래일) — risk.DailyPnlTracker가 값이 바뀔 때마다 갱신하고, 기동 시 오늘 행으로 복원한다.
-- 장 마감 리포트의 daily_performance(V4)와 별개다 — 그쪽은 15:50 1회 기록이라 장중 재기동 복원에 쓸 수 없다.
CREATE TABLE risk_daily_pnl (
    trade_date    DATE           PRIMARY KEY,
    realized_pnl  NUMERIC(19,4)  NOT NULL,
    updated_at    TIMESTAMPTZ    NOT NULL
);
