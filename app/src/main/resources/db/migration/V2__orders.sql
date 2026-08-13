-- 주문 영속화(Phase 2 잔여): ExecutionService의 인메모리 멱등키/브로커주문번호 캐시는
-- 앱이 재시작되면 사라진다. 이 테이블이 그 공백을 메운다 — 재시작 복원(OrderRecoveryService)과
-- 미체결 타임아웃 취소(StaleOrderCanceller)의 근거 데이터이자, idempotency_key UNIQUE 제약이
-- 중복 주문 방지의 최종 방어선이다(1차 방어선은 ExecutionService의 인메모리 Set).
CREATE TABLE orders (
    id               BIGSERIAL    PRIMARY KEY,
    idempotency_key  VARCHAR(128) UNIQUE NOT NULL,
    broker_order_id  VARCHAR(64),                    -- LIVE 접수 완료 전까지는 NULL(SIM은 즉시 채워짐)
    symbol           VARCHAR(16)  NOT NULL,
    side             VARCHAR(8)   NOT NULL,           -- BUY / SELL (Side enum name())
    quantity         BIGINT       NOT NULL,
    limit_price      NUMERIC(19,4),
    status           VARCHAR(16)  NOT NULL,           -- SUBMITTED / FILLED / CANCELLED / REJECTED
    submitted_at     TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL
);

-- 체결통보(brokerOrderId만 들고 옴) 처리 시 원 주문을 찾는 조회 경로 — OrderNoticeHandler의
-- DB 폴백 조회(ExecutionService.findByBrokerOrderId)와 재시작 복원 대사에 쓰인다.
CREATE INDEX idx_orders_broker_order_id ON orders (broker_order_id);

-- OrderRecoveryService(재시작 복원)와 StaleOrderCanceller(미체결 타임아웃)가 공통으로
-- "SUBMITTED 상태인 주문 전부"를 조회하므로 상태 컬럼에 인덱스를 둔다.
CREATE INDEX idx_orders_status ON orders (status);
