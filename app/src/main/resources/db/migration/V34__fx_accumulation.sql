-- 외화 적립 알림(VALUNIVO 4-6, aiDoc/fx-accumulation-alert.md 8장, D-28) — fx 모듈 소유.
-- 앱은 환전 주문을 내지 않는다. 데브가 은행 앱에서 한 예약·체결·직접매수를 기록하고, 공개 환율(ECOS)로 검토 알림을 낸다.
-- 거래·주문·리스크 테이블과 관계가 없다.

CREATE TABLE fx_reservation (
    id                BIGSERIAL     PRIMARY KEY,
    currency          VARCHAR(3)    NOT NULL,
    requested_on      DATE          NOT NULL,
    request_rate      NUMERIC(12,4) NOT NULL,     -- 신청할 때 은행 앱에 보인 살 때 환율(원/1달러, 원/100엔)
    reserve_rate      NUMERIC(12,4) NOT NULL,     -- 예약(목표) 환율
    amount_krw        BIGINT        NOT NULL,
    ref_rate          NUMERIC(12,4),              -- 입력 때 ECOS 최신값. 못 받았으면 비고, 이 예약은 검토 알림을 내지 않는다
    ref_observed_on   DATE,
    spread            NUMERIC(12,4),              -- request_rate - ref_rate
    expires_on        DATE          NOT NULL,
    status            VARCHAR(16)   NOT NULL,
    filled_on         DATE,
    filled_rate       NUMERIC(12,4),
    last_review_below BOOLEAN,                    -- 앞 판정에서 추정 환율이 기준선 아래였는지(설계 6장 C6)
    created_at        TIMESTAMPTZ   NOT NULL,
    updated_at        TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_fx_reservation_currency CHECK (currency IN ('USD', 'JPY')),
    CONSTRAINT ck_fx_reservation_status CHECK (status IN ('OPEN', 'FILLED', 'CANCELLED')),
    CONSTRAINT ck_fx_reservation_positive CHECK (request_rate > 0 AND reserve_rate > 0 AND amount_krw > 0),
    CONSTRAINT ck_fx_reservation_rate CHECK (reserve_rate < request_rate),
    CONSTRAINT ck_fx_reservation_filled CHECK ((status = 'FILLED') = (filled_on IS NOT NULL AND filled_rate IS NOT NULL))
);

COMMENT ON TABLE fx_reservation IS '외화 목표환율 예약 기록 — 데브 입력(V34, 4-6)';

CREATE INDEX ix_fx_reservation_status ON fx_reservation (status, currency);

CREATE TABLE fx_direct_buy (
    id          BIGSERIAL     PRIMARY KEY,
    currency    VARCHAR(3)    NOT NULL,
    bought_on   DATE          NOT NULL,
    rate        NUMERIC(12,4) NOT NULL,
    amount_krw  BIGINT        NOT NULL,
    status      VARCHAR(16)   NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_fx_direct_buy_currency CHECK (currency IN ('USD', 'JPY')),
    CONSTRAINT ck_fx_direct_buy_status CHECK (status IN ('DONE', 'CANCELLED')),
    CONSTRAINT ck_fx_direct_buy_positive CHECK (rate > 0 AND amount_krw > 0)
);

COMMENT ON TABLE fx_direct_buy IS '외화 직접매수 기록 — 데브 입력(V34, 4-6)';

CREATE INDEX ix_fx_direct_buy_bought_on ON fx_direct_buy (bought_on);

CREATE TABLE fx_alert_log (
    id         BIGSERIAL    PRIMARY KEY,
    kind       VARCHAR(16)  NOT NULL,
    currency   VARCHAR(3)   NOT NULL,             -- 월초·월말 알림은 '-'
    alert_key  VARCHAR(32)  NOT NULL,             -- REVIEW: 'R<예약 id>|<ECOS 관측일>', MONTH_*: 'YYYY-MM'
    sent_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_fx_alert_log_kind CHECK (kind IN ('REVIEW', 'MONTH_START', 'MONTH_END')),
    CONSTRAINT uk_fx_alert_log UNIQUE (kind, currency, alert_key)
);

COMMENT ON TABLE fx_alert_log IS '보낸 외화 알림 — 종류·통화·기준값마다 한 번(V34, ipo_alert_log와 같은 방식)';
