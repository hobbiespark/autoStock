-- 일별 성과 스냅샷(monitor 모듈) — FE-2 성과 추이 화면(PLAN.md ADR-10 확장표)의 데이터 소스.
--
-- DailyReportScheduler의 평일 15:50(KST) 장 마감 리포트 발송 시점에 그날 하루의 요약을
-- upsert로 적재한다(trade_date UNIQUE — 같은 날 재기동 등으로 스케줄이 다시 돌아도 갱신만
-- 되고 중복 행이 생기지 않는다). 원본 이벤트(Fill 등)는 이미 이벤트 스토어에 append-only로
-- 영속화되어 있으므로(PLAN ADR-2), 이 테이블은 "그날의 집계 스냅샷"이라는 파생 데이터다 —
-- 언제든 이벤트 스토어 리플레이로 재계산 가능하지만, 화면에서 매번 재계산하지 않도록 별도
-- 저장한다.
CREATE TABLE daily_performance (
    id                  BIGSERIAL       PRIMARY KEY,
    trade_date           DATE            UNIQUE NOT NULL,
    realized_pnl         NUMERIC(19,4)   NOT NULL,
    order_count           INT             NOT NULL,
    fill_count            INT             NOT NULL,
    avg_slippage_bps     DOUBLE PRECISION NOT NULL,
    max_slippage_bps     DOUBLE PRECISION NOT NULL,
    conservative_mode    BOOLEAN         NOT NULL,
    kill_switch_engaged BOOLEAN         NOT NULL,
    created_at            TIMESTAMPTZ     NOT NULL
);

-- GET /api/performance/daily?days=N 이 기간 범위(trade_date >= 기준일)로 조회하므로
-- trade_date 인덱스를 둔다(UNIQUE 제약이 이미 인덱스를 생성하지만 명시적으로 남겨 의도를
-- 드러낸다 — PostgreSQL은 UNIQUE 제약 생성 시 인덱스를 자동 생성하므로 실질적으로는 중복
-- 인덱스 없이 UNIQUE 제약의 인덱스를 그대로 재사용한다).
