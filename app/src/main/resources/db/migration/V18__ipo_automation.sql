-- 공모주 자동화 강화 (2026-10-13 사용자 "공모주 기능 자동화 강화 필요", aiDoc/ipo-automation.md) — ipo 모듈 소유.
--
-- ① 확정 공모가: 주요정보(estkRs)의 모집가액(offer_price_confirmed)은 수요예측 전 희망가 하단이고 확정 뒤에도 바뀌지 않는다.
--    [발행조건확정] 원본 "1주당 확정공모가액을 N원으로 결정"에서 읽은 값을 따로 둔다. 희망 밴드는 V6의 offer_price_low/high에 채운다.
-- ② 상장 확인: 키움 종목정보 리스트에서 회사명으로 맞춘 종목코드(상장일은 V6 listing_date).
-- ③ 알림 기록: 단계가 여덟 개(신규·수요예측 결과·청약 D-1·시작·마감·환불·상장 D-1·상장)로 늘어 딜 행의 "마지막 단계" 하나(V16)로는
--    앞 단계 기록이 지워진다. 회사·단계·기준값(청약 시작일, [발행조건확정] 접수번호 등)마다 한 줄. V16 컬럼은 더 쓰지 않는다(지우지 않음).
ALTER TABLE ipo_deals
    ADD COLUMN offer_price_final NUMERIC(19, 4),   -- 확정 공모가(원)
    ADD COLUMN stock_code        VARCHAR(12);      -- 상장 뒤 종목코드

COMMENT ON COLUMN ipo_deals.offer_price_confirmed IS '주요정보 모집가액(slprc) — 수요예측 전 희망가 하단, 확정 뒤에도 그대로(V18 주석)';
COMMENT ON COLUMN ipo_deals.offer_price_final IS '확정 공모가 — [발행조건확정] 원본 문장에서 읽음(V18)';
COMMENT ON COLUMN ipo_deals.stock_code IS '상장 뒤 종목코드 — 키움 종목정보 리스트에서 회사명으로 맞춤(V18)';

CREATE TABLE ipo_alert_log (
    id        BIGSERIAL    PRIMARY KEY,
    corp_code VARCHAR(16)  NOT NULL,
    phase     VARCHAR(16)  NOT NULL,                -- NEW, METRICS, D-1, START, END, REFUND, LISTING-D1, LISTING
    alert_key VARCHAR(32)  NOT NULL,                -- 기준값 — 청약 시작일(YYYY-MM-DD), 접수번호 등. NEW는 '-'
    sent_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_ipo_alert_log UNIQUE (corp_code, phase, alert_key)
);

COMMENT ON TABLE ipo_alert_log IS '보낸 공모주 알림 — 회사·단계·기준값마다 한 번(V18)';

-- V16 표시를 옮긴다 — 배포한 날 이미 보낸 청약 D-1·시작 알림을 다시 보내지 않게. 기준값은 청약 시작일이다
-- (D-1은 보낸 날 다음 날, START는 보낸 날 — 예전 판정이 달력 날짜 기준이었다).
INSERT INTO ipo_alert_log (corp_code, phase, alert_key, sent_at)
SELECT DISTINCT corp_code,
       last_alert_phase,
       to_char(CASE WHEN last_alert_phase = 'D-1' THEN last_alert_date + 1 ELSE last_alert_date END, 'YYYY-MM-DD'),
       (last_alert_date::timestamp AT TIME ZONE 'Asia/Seoul')
FROM ipo_deals
WHERE last_alert_phase IN ('D-1', 'START') AND last_alert_date IS NOT NULL
ON CONFLICT ON CONSTRAINT uk_ipo_alert_log DO NOTHING;
