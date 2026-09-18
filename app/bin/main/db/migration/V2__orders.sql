-- 주문 Aggregate 영속화 (PLAN.md ADR-6). OrderEntity의 상태 전이(transitionTo)는 애플리케이션
-- 코드에서 강제되지만, client_order_id UNIQUE 제약이 중복 주문 방지의 최종 방어선이다
-- (1차 방어선은 TradingService의 인메모리 Set — ADR-6 재편으로 execution → trading 이동).
--
-- 아직 어떤 환경에도 배포된 적 없는 마이그레이션이라(Phase 2 진행 중), 별도 V3를 추가하지
-- 않고 이 파일 자체를 ADR-6 스펙(11상태·client_order_id·filled_quantity·strategy_id)에 맞게
-- 개정한다.
CREATE TABLE orders (
    id               BIGSERIAL    PRIMARY KEY,
    client_order_id  VARCHAR(128) UNIQUE NOT NULL,   -- ClientOrderId 포맷 문자열(예: 20260813-BREAKOUT-005930-BUY-001)
    broker_order_id  VARCHAR(64),                     -- LIVE 접수 완료 전까지는 NULL(SIM은 즉시 채워짐)
    symbol           VARCHAR(16)  NOT NULL,
    side             VARCHAR(8)   NOT NULL,           -- BUY / SELL (Side enum name())
    quantity         BIGINT       NOT NULL,
    filled_quantity  BIGINT       NOT NULL DEFAULT 0, -- 누적 체결 수량 (OrderEntity.applyFill)
    limit_price      NUMERIC(19,4),
    -- 11상태 상태기계(OrderStatus, PLAN.md ADR-6 6절):
    --   CREATED / VALIDATED / SUBMITTING / SUBMITTED / ACCEPTED / PARTIALLY_FILLED / FILLED
    --   CANCEL_REQUESTED / CANCELLED / REJECTED / UNKNOWN(타임아웃 등으로 결과 불명 — Reconciliation이 해소)
    status           VARCHAR(20)  NOT NULL,
    strategy_id      VARCHAR(64)  NOT NULL,           -- 어느 전략이 낸 주문인지(ClientOrderId에도 포함되지만 조회 편의상 별도 컬럼)
    submitted_at     TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL
);

-- 체결통보(brokerOrderId만 들고 옴) 처리 시 원 주문을 찾는 조회 경로 — OrderNoticeHandler의
-- DB 폴백 조회와 재시작 복원 대사에 쓰인다.
CREATE INDEX idx_orders_broker_order_id ON orders (broker_order_id);

-- ReconciliationService(UNKNOWN·SUBMITTED 대사)와 StaleOrderCanceller(SUBMITTED 타임아웃)가
-- 공통으로 특정 상태 집합을 조회하므로 상태 컬럼에 인덱스를 둔다.
CREATE INDEX idx_orders_status ON orders (status);
