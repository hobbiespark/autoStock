-- 일별 주문·취소 건수 기록(OTR 관측, 실행 계획 1.7, aiDoc/observability.md) — monitor 모듈 소유 daily_performance에 더한다.
--
-- 15:50 스냅샷이 그날(KST) 접수된 주문(orders.submitted_at)에서 센다. 목표치 없이 기록만 한다
-- (금융위 HFT 규율 검토 대비 — upgrade-2026-10/07 §4-1).
--   submitted_count: 브로커로 보냈거나 보내려던 주문 — CREATED·VALIDATED(전송 전 단계)를 뺀 모든 상태
--   cancelled_count: 취소 요청을 보낸 주문 — 현재 상태가 CANCELLED 또는 CANCEL_REQUESTED
--                    (취소 요청 뒤 체결로 끝난 주문은 현재 상태만으로는 셀 수 없다 — 문서 5절)
ALTER TABLE daily_performance
    ADD COLUMN submitted_count INT NOT NULL DEFAULT 0,
    ADD COLUMN cancelled_count INT NOT NULL DEFAULT 0;

-- 이미 있는 날의 값을 같은 규칙으로 채운다(주문 테이블은 운영 시작부터 남아 있다).
UPDATE daily_performance dp
SET submitted_count = (SELECT count(*) FROM orders o
                       WHERE (o.submitted_at AT TIME ZONE 'Asia/Seoul')::date = dp.trade_date
                         AND o.status NOT IN ('CREATED', 'VALIDATED')),
    cancelled_count = (SELECT count(*) FROM orders o
                       WHERE (o.submitted_at AT TIME ZONE 'Asia/Seoul')::date = dp.trade_date
                         AND o.status IN ('CANCELLED', 'CANCEL_REQUESTED'));
