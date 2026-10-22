-- 전략별 장부 (2026-10-16, aiDoc/multi-strategy-paper-2026-10-16.md 2절) — portfolio 모듈 소유 테이블.
--
-- 모의투자에서 여러 전략(C3·단타·종가→익일·차트 5종)이 한 계좌로 함께 매매한다. 계좌 장부(PositionBook, 메모리)는
-- 종목 단위라 "어느 전략이 몇 주를 들었나"를 모른다. 체결(Fill)의 주문번호(ClientOrderId)에 든 전략 ID로
-- (전략, 종목)별 보유를 따로 센다. 재기동하면 strategy_positions에서 읽는다.

CREATE TABLE strategy_positions (
    strategy_id   VARCHAR(64)    NOT NULL,   -- ClientOrderId의 전략 ID(예: C3-MOMENTUM, VB-DAY, TA-H1)
    symbol        VARCHAR(6)     NOT NULL,
    quantity      BIGINT         NOT NULL,   -- 보유 수량(양수) — 0이 되면 행을 지운다
    avg_price     NUMERIC(18, 4),            -- 평균 매수 단가. NULL = 평단 모름(계좌 장부와 같은 규칙)
    opened_at     TIMESTAMPTZ    NOT NULL,   -- 이 보유가 0에서 처음 생긴 체결 시각
    updated_at    TIMESTAMPTZ    NOT NULL,
    PRIMARY KEY (strategy_id, symbol),
    CONSTRAINT strategy_positions_quantity_positive CHECK (quantity > 0)
);

COMMENT ON TABLE strategy_positions IS '전략별 현재 보유 — 체결(Fill)의 ClientOrderId 전략 ID로 집계(V20, portfolio.StrategyBook)';

CREATE TABLE strategy_fills (
    id               BIGSERIAL      PRIMARY KEY,
    strategy_id      VARCHAR(64)    NOT NULL,
    symbol           VARCHAR(6)     NOT NULL,
    side             VARCHAR(4)     NOT NULL,   -- BUY / SELL
    quantity         BIGINT         NOT NULL,   -- 이번 체결 증분 수량
    price            NUMERIC(18, 4) NOT NULL,   -- 체결가(통보의 증분 체결가)
    client_order_id  VARCHAR(80)    NOT NULL,
    avg_price_before NUMERIC(18, 4),            -- 매도 직전 평단(매수는 NULL)
    realized_pnl     NUMERIC(18, 2),            -- 매도만: (체결가 − 평단) × 수량 − 매수·매도 수수료 − 거래세(추정). 평단을 모르면 NULL
    filled_at        TIMESTAMPTZ    NOT NULL
);

CREATE INDEX strategy_fills_strategy_time ON strategy_fills (strategy_id, filled_at);
CREATE INDEX strategy_fills_time ON strategy_fills (filled_at);

COMMENT ON TABLE strategy_fills IS '전략별 체결 기록과 매도 실현손익(추정 비용 차감) — 전략별 성과 집계 원본(V20)';
