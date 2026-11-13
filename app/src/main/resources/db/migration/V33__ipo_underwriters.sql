-- 공모주 인수인 전부(2026-11-11 사용자 "현재 보유 중인 증권사에서 청약 가능하면 +점수, 보유하지 않은 증권사는 1개월에 1개씩
-- 개설 요청", aiDoc/ipo-subscription-strategy.md 6절) — ipo 모듈 소유.
--
-- lead_manager는 대표 주관사 하나뿐이라 공동주관·인수회사의 보유 계좌로 청약할 수 있는지 알 수 없었다. DART 인수인정보의
-- 회사 이름(actnmn)을 ", "로 이어 남긴다. 기존 행은 비워 둔다 — 다음 배치가 estkRs.json을 다시 읽으면 채워지고, 그 전에는
-- 대표 주관사 하나로 본다(IpoDealEntity.getUnderwriters).
ALTER TABLE ipo_deals
    ADD COLUMN underwriters VARCHAR(512);

COMMENT ON COLUMN ipo_deals.underwriters IS '인수인 이름 전부(대표 먼저, ", " 구분) — 보유 계좌 청약 가능 판단(V33)';
