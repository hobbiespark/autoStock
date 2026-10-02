-- 전략 판단 상태 영속화 (실행 계획 1.1, aiDoc/c3-trading-day-cycle.md) — strategy 모듈 소유 테이블.
--
-- C3의 종목별 "마지막 판단일"이 메모리에만 있어, 재기동할 때마다 전 종목을 "첫 판단"으로 보고 다음 09:05에
-- 다시 판단하던 결함(감사 BE-P1-1)을 막는다. 사용자 결정 D-03(2026-10-02 권고안 진행): 판단 주기를 21거래일로
-- 맞추고, 게이트 ② 카운트는 유지, C3 성과는 변경일 기준으로 나눠 본다.
--
-- 판단에 성공한 종목만 갱신한다(데이터 부족·조회 실패는 갱신하지 않아 다음 스케줄에 다시 판단 — 기존 규칙).
-- 비어 있는 채로 배포한다 — 배포 후 첫 09:05에 전 종목을 한 번 판단하고(변경일 기준 시작), 그 뒤로 21거래일마다.
CREATE TABLE strategy_state (
    strategy_id         VARCHAR(64)  NOT NULL,   -- signal_decisions.strategy_id와 같은 값(예: C3-MOMENTUM)
    symbol              VARCHAR(6)   NOT NULL,
    last_decision_date  DATE         NOT NULL,   -- 마지막으로 판단을 내린 거래일(KST)
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (strategy_id, symbol)
);
