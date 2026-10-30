-- 단타 → 스윙·중기 이월과 3분할 매수(2026-10-30, aiDoc/split-buy-swing-carry-2026-10-30.md) — strategy 모듈 소유 테이블.
--
-- 사용자 지시(2026-10-30): "단타가 스윙, 중, 장기 투자로 이어진다는 생각으로 3분할 매수". 단타(VB-DAY·ON-CLOSE)가 정해진
-- 청산 시각에 비용 후 수익이 아니면 팔지 않고 이 표에 넘긴다. 매 거래일 09:02에 완료된 일봉으로 2·3차 추가 매수·스윙/중기
-- 청산을 판단한다. 보유 수량·평단은 전략 장부(strategy_positions)가 원본이고, 이 표는 단계·차수·이월일만 둔다.
-- 장부에 보유가 없어지면(팔림) 다음 판단에서 행을 지운다. 비어 있는 채로 배포한다.
CREATE TABLE strategy_carry_positions (
    strategy_id     VARCHAR(64)  NOT NULL,   -- strategy_positions.strategy_id와 같은 값(VB-DAY·ON-CLOSE)
    symbol          VARCHAR(6)   NOT NULL,
    stage           VARCHAR(8)   NOT NULL,   -- SWING(1·2차) · MID(3차)
    tranches        INTEGER      NOT NULL,   -- 산 차수 1~3
    carried_on      DATE         NOT NULL,   -- 이월한 거래일(KST) — 보유 기간은 이날 뒤 완료된 봉 수
    last_added_on   DATE,                    -- 마지막 추가 매수 체결일(KST) — 같은 날 부분 체결이 여러 번 와도 차수는 한 번만 센다
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (strategy_id, symbol),
    CONSTRAINT strategy_carry_stage CHECK (stage IN ('SWING', 'MID')),
    CONSTRAINT strategy_carry_tranches CHECK (tranches BETWEEN 1 AND 3)
);
