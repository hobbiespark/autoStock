-- 2026-09 추석 연휴 수동 등록 (특일 API 실측 2026-09-18: 9/24·25·26 isHoliday=Y). 휴장일 배치는 매월 15일에만 돌아 이번 달분은 자동 반영되지 않는다.
-- 실행: docker compose -f infra\docker-compose.yml exec -T postgres psql -U autostock -d autostock < scripts\sql\20260918_holidays_202609.sql (cmd)
--       PowerShell: Get-Content scripts\sql\20260918_holidays_202609.sql -Raw | docker compose -f infra\docker-compose.yml exec -T postgres psql -U autostock -d autostock
INSERT INTO market_holidays (holiday_date, name, source, is_market_closure, synced_at) VALUES
  ('2026-09-24', '추석', 'MANUAL', FALSE, now()),
  ('2026-09-25', '추석', 'MANUAL', FALSE, now()),
  ('2026-09-26', '추석', 'MANUAL', FALSE, now())
ON CONFLICT (holiday_date) DO NOTHING;

-- KRX 휴장일 공지에서 9/28(월) 대체공휴일이 확인되면 아래 주석을 풀어 실행:
-- INSERT INTO market_holidays (holiday_date, name, source, is_market_closure, synced_at)
--   VALUES ('2026-09-28', '대체공휴일(추석)', 'MANUAL', FALSE, now()) ON CONFLICT (holiday_date) DO NOTHING;

SELECT holiday_date, name, source FROM market_holidays WHERE holiday_date >= '2026-09-01' ORDER BY holiday_date;
