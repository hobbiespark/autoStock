-- 종목명 사전 (2026-10-02, aiDoc/stock-names.md) — market 모듈 소유 테이블.
--
-- 사용자 요구: "종목코드와 종목명은 항상 같이 표시". 텔레그램·리포트·대시보드·로그가 종목을 "삼성전자(005930)"로
-- 보여주려면 코드 → 이름 사전이 필요하다. 메모리 사전(common.util.StockNames)만 두면 재기동 직후나 키움 장애 중에
-- 이름이 비므로, 배운 이름을 여기 남겨 다음 기동에 바로 쓴다(market.StockNameDirectory).
-- 표시 전용 — 매매 판단은 이 테이블을 읽지 않는다.
CREATE TABLE stock_names (
    symbol      VARCHAR(12)  PRIMARY KEY,   -- 종목코드(StockCode, 영숫자 대문자 6자리)
    name        VARCHAR(100) NOT NULL,      -- 종목명(키움 stk_nm 또는 DART 기업명)
    source      VARCHAR(16)  NOT NULL,      -- 'KIWOOM'(ka10001·kt00018) | 'DART'(공시 기업명, 키움 이름이 덮는다)
    updated_at  TIMESTAMPTZ  NOT NULL       -- 마지막 확인 시각 — 7일이 지나면 다시 조회한다(종목명 변경 대비)
);
