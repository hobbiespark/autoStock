-- 지난 공모의 인수인 이력(2026-11-13 사용자 "지난 1,3,5년 ipo를 많이 했다던지 하는 근거로 추천", VALUNIVO 875, 결정 D-29) — ipo 모듈 소유.
--
-- 계좌 개설 요청(D-27, IpoAccountOpenPlanner)이 증권사마다 지난 1·3·5년에 인수한 공모 수를 센다. ipo_deals는 앱이 수집을 시작한 뒤의
-- 딜만 있고, ipo_listing_history(V19 SEED)는 인수인 칸이 없어 DART에서 거꾸로 채운다(IpoUnderwriterBackfill). 회사마다 한 행
-- (공모는 회사당 한 번, 다시 공모하면 새 값으로 덮음). 주문·거래와 관계없다.
CREATE TABLE ipo_underwriting_history (
    id                  BIGSERIAL     PRIMARY KEY,
    corp_code           VARCHAR(16)   NOT NULL,
    corp_name           VARCHAR(128)  NOT NULL,
    rcept_no            VARCHAR(20)   NOT NULL,             -- 인수인을 읽은 증권신고서 접수번호
    filed_on            DATE          NOT NULL,             -- 그 신고서 접수일
    subscription_start  DATE,                               -- 청약 시작일(없으면 접수일로 센다)
    underwriters        VARCHAR(512)  NOT NULL,             -- 인수인 이름 전부(대표 먼저, ", " 구분)
    source              VARCHAR(16)   NOT NULL,             -- BACKFILL
    updated_at          TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uk_ipo_underwriting_history UNIQUE (corp_code)
);

COMMENT ON TABLE ipo_underwriting_history IS '지난 공모(IPO·스팩)의 인수인 — 계좌 개설 추천의 1·3·5년 실적(V35)';

-- 거꾸로 채우기 진행 위치 — 재기동 뒤에도 이어 간다. covered_from까지(포함) 읽었다.
CREATE TABLE ipo_backfill_progress (
    name          VARCHAR(32)  PRIMARY KEY,
    target_from   DATE         NOT NULL,                    -- 여기까지 거슬러 올라가면 끝(첫 실행 날의 5년 전)
    covered_from  DATE         NOT NULL,                    -- 이 날부터 첫 실행 날까지 읽음
    updated_at    TIMESTAMPTZ  NOT NULL
);

COMMENT ON TABLE ipo_backfill_progress IS 'DART 거꾸로 채우기 진행 위치(V35)';
