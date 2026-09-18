-- 공시 기반 매수 배제 목록 영속화 (PLAN.md ADR-14, 트랙 G1) — risk 모듈 소유 테이블.
--
-- macrointel.DisclosureBlacklistSyncScheduler가 매 평일 08:35 KST DART 주요사항보고서(list.json,
-- pblntf_ty=B)에서 유상증자·CB·BW·EB 발행 결정 공시를 감지해 DisclosureRisk 이벤트를 발행하면,
-- risk.DisclosureBlacklist가 이 테이블에 저장한다("판단·차단은 risk 소유" 원칙 — macrointel은
-- 이 테이블을 전혀 모른다). (symbol, rcept_no) 조합이 자연키다 — 배치 재처리로 같은 공시가
-- 다시 들어와도 중복 저장되지 않는다.
--
-- 등록 기간(expires_on = rcept_dt + retention-days, 기본 180일)은 ADR-14 G1 근거(Loughran &
-- Ritter 1995)의 "SEO 장기 저성과는 6~24개월 지속" 구간 하한을 보수적으로 채택한 값이다
-- (macrointel.DisclosureBlacklistProperties.retentionDays Javadoc 참고). 운영자 수동 add()는
-- 이 테이블에 남기지 않는다(risk.DisclosureBlacklist 클래스 설명 — 무기한이라 DB 영속 대상이
-- 아니고, 재시작 시 사라지는 편이 안전하다는 설계 판단).
CREATE TABLE disclosure_blacklist (
    id                BIGSERIAL       PRIMARY KEY,
    symbol            VARCHAR(12)     NOT NULL,
    corp_name         VARCHAR(128),
    disclosure_type   VARCHAR(32)     NOT NULL,
    rcept_no          VARCHAR(20)     NOT NULL,
    rcept_dt          DATE            NOT NULL,
    expires_on        DATE            NOT NULL,
    created_at        TIMESTAMPTZ     NOT NULL,
    CONSTRAINT uq_disclosure_blacklist_symbol_rcept UNIQUE (symbol, rcept_no)
);

-- DisclosureBlacklist.isBlacklisted/reloadDisclosureCache가 기동 시·만료 정리 후 다시 훑는 조회.
CREATE INDEX idx_disclosure_blacklist_symbol ON disclosure_blacklist (symbol);
CREATE INDEX idx_disclosure_blacklist_expires_on ON disclosure_blacklist (expires_on);
