-- 낙관적 잠금 버전 컬럼 — 동시 갱신에서 나중 저장이 앞 변경(체결 수량 등)을 덮는 것을 막는다.
-- orders: 체결 통보(WS)·취소·타임아웃 취소·대사가 서로 다른 스레드에서 같은 주문을 갱신한다.
-- ipo_deals: 공모주 배치와 수동 입력(POST /api/ipo/{id}/metrics)이 같은 딜을 갱신한다.
ALTER TABLE orders ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ipo_deals ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
