-- 2026-09-18 운영 재개 전 orders 정리 (PROGRESS 4-1절 C2-1 잔여 SQL + C2-3)
-- 실행: docker compose -f infra\docker-compose.yml exec -T postgres psql -U autostock -d autostock < scripts\sql\20260918_cleanup_orders.sql
-- 전제: 실행 전 대시보드/kt00018로 005930 보유 수량이 19주인지 확인할 것 (SELL-005가 실제로 나갔다면 19 미만).
BEGIN;

-- 대상 확인
SELECT client_order_id, status, quantity, filled_quantity, broker_order_id, submitted_at
  FROM orders
 WHERE client_order_id LIKE '%BUY-004' OR client_order_id LIKE '%SELL-005';

-- (a) BUY-004: 이중계상 시절 filled_quantity=23 → 실체결 19 (C2-1 잔여)
UPDATE orders
   SET filled_quantity = 19, updated_at = now()
 WHERE client_order_id = '20260911-DASHBOARD-MANUAL-005930-BUY-004'
   AND filled_quantity = 23;

-- (b) SELL-005: 9/11 SUBMITTING 단계 타임아웃 → UNKNOWN, brokerOrderId 없음(접수 미확인).
--     당일 주문은 장 마감으로 소멸했고 보유 19주가 그대로이므로 "접수되지 않은 주문"으로 종결.
--     UNKNOWN → REJECTED 는 OrderStatus 전이표상 합법 전이.
UPDATE orders
   SET status = 'REJECTED', updated_at = now()
 WHERE client_order_id = '20260911-DASHBOARD-MANUAL-005930-SELL-005'
   AND status = 'UNKNOWN'
   AND broker_order_id IS NULL;

-- 결과 확인 (UNKNOWN 0건이어야 함)
SELECT client_order_id, status, quantity, filled_quantity FROM orders WHERE status = 'UNKNOWN';
SELECT client_order_id, status, filled_quantity FROM orders WHERE client_order_id LIKE '%BUY-004';

COMMIT;
