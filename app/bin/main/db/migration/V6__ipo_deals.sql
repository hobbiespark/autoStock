-- 공모주 반자동 파이프라인(PLAN.md ADR-9, 트랙 E2) — ipo 모듈 소유 테이블.
--
-- DART list.json(pblntf_ty=C, 증권신고서(지분증권))으로 감지한 딜을 rcept_no 기준으로
-- upsert한다. estkRs.json(증권신고서 지분증권 주요정보)이 실측 결과 공모가 "밴드"(희망 하단/
-- 상단)를 구조화 필드로 제공하지 않아(docs/measured/dart_estkRs_20260911.json 참고 — 확정가
-- slprc 하나만 내려온다) offer_price_low/high는 대부분 NULL로 남고 offer_price_confirmed만
-- 채워지는 경우가 많다 — 과대약속 금지(ADR-9). 상장일(listing_date)도 estkRs가 제공하지 않아
-- NULL 허용 + 수동 입력 경로를 열어 둔다. 기관경쟁률·의무보유확약률도 DART가 구조화 제공하지
-- 않아(실측 확인) POST /api/ipo/{id}/metrics 수동 입력으로만 채워진다.
CREATE TABLE ipo_deals (
    id                              BIGSERIAL       PRIMARY KEY,
    corp_code                       VARCHAR(16)     NOT NULL,
    corp_name                       VARCHAR(128)    NOT NULL,
    rcept_no                        VARCHAR(20)     NOT NULL,
    offer_price_low                 NUMERIC(14,2),
    offer_price_high                NUMERIC(14,2),
    offer_price_confirmed           NUMERIC(14,2),
    subscription_start              DATE,
    subscription_end                DATE,
    refund_date                     DATE,
    listing_date                    DATE,
    lead_manager                    VARCHAR(256),
    institutional_competition_rate  NUMERIC(10,2),
    lockup_commit_rate              NUMERIC(6,4),
    status                          VARCHAR(16)     NOT NULL DEFAULT 'UPCOMING',
    recommendation                  VARCHAR(16)     NOT NULL DEFAULT 'PENDING',
    recommend_reason                TEXT,
    applied_qty                     INTEGER,
    deposit                         NUMERIC(16,2),
    allocated_qty                   INTEGER,
    sell_price                      NUMERIC(14,2),
    sell_date                       DATE,
    memo                            TEXT,
    source                          VARCHAR(16)     NOT NULL DEFAULT 'DART',
    created_at                      TIMESTAMPTZ     NOT NULL,
    updated_at                      TIMESTAMPTZ     NOT NULL,
    CONSTRAINT uq_ipo_deals_rcept_no UNIQUE (rcept_no)
);

-- IpoSyncScheduler가 상태별 목록/알림 대상을 조회하고, GET /api/ipo?status= 가 그대로 쓴다.
CREATE INDEX idx_ipo_deals_status ON ipo_deals (status);
CREATE INDEX idx_ipo_deals_subscription_start ON ipo_deals (subscription_start);
