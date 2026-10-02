-- 공모주 딜: 공모 종류와 지표 출처 (2026-10-02, aiDoc/ipo-demand-forecast.md) — ipo 모듈 소유 테이블.
--
-- 사용자 요구: "기관경쟁률·의무보유확약비율 자동 입력". [발행조건확정]증권신고서의 수요예측 결과에서 두 지표를 읽어 채운다.
-- 사람이 넣은 값은 자동 입력이 덮지 않아야 하므로 출처를 남긴다.
-- 함께 고친 결함: 상장사 유상증자(주주배정 등)가 공모주 딜로 들어와 화면·청약 알림에 나왔다. 공모 종류를 판정해 남기고,
-- 유상증자로 판정된 딜은 행을 지우지 않고 화면·권고·알림에서만 뺀다(기록 보존).
ALTER TABLE ipo_deals ADD COLUMN offering_kind    VARCHAR(16);  -- 'IPO' | 'RIGHTS'(상장사 유상증자) | NULL(판정 전 — 공모주로 취급)
ALTER TABLE ipo_deals ADD COLUMN metrics_source   VARCHAR(16);  -- 'DART'(수요예측 결과 자동) | 'MANUAL'(수동 입력) | NULL
ALTER TABLE ipo_deals ADD COLUMN metrics_rcept_no VARCHAR(20);  -- 자동 입력에 쓴 [발행조건확정] 접수번호

-- 이 마이그레이션 전에 들어간 지표는 수동 입력 API로만 채울 수 있었다 — 사람이 넣은 값으로 표시해 자동 입력이 덮지 않게 한다.
UPDATE ipo_deals SET metrics_source = 'MANUAL'
 WHERE institutional_competition_rate IS NOT NULL OR lockup_commit_rate IS NOT NULL;
