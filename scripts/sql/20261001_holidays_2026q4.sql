-- 2026년 4분기 휴장일 확인·보강 (aiDoc/upgrade-2026-10/11-execution-plan.md 0.0, 07 §7).
-- 공휴일 규정 기반: 10/5(월, 개천절 10/3 토요일의 대체공휴일)·10/9(금, 한글날)·12/25(금). 특일 API 배치로 이미 들어와 있으면 그대로 둔다(ON CONFLICT DO NOTHING).
-- 12/31(목) 연말휴장은 특일 API에 없다(거래소 자체 휴장) → MANUAL + is_market_closure=TRUE. KRX 12월 공지로 최종 확인.
-- 실행 시점: 앱을 켜기 전(또는 실행 후 앱 재기동) — MarketCalendarService가 연도별 캐시를 쓰므로 실행 중인 앱에는 재기동 전까지 반영되지 않는다.
-- 실행: docker compose -f infra\docker-compose.yml exec -T postgres psql -U autostock -d autostock < scripts\sql\20261001_holidays_2026q4.sql (cmd)
--       PowerShell: Get-Content scripts\sql\20261001_holidays_2026q4.sql -Raw | docker compose -f infra\docker-compose.yml exec -T postgres psql -U autostock -d autostock

-- 1) 실행 전 현황(결과를 남겨 두면 무엇이 새로 들어갔는지 알 수 있다)
SELECT holiday_date, name, source, is_market_closure FROM market_holidays
 WHERE holiday_date BETWEEN '2026-09-24' AND '2026-12-31' ORDER BY holiday_date;

-- 2) 보강 등록
INSERT INTO market_holidays (holiday_date, name, source, is_market_closure, synced_at) VALUES
  ('2026-10-05', '대체공휴일(개천절)', 'MANUAL', FALSE, now()),
  ('2026-10-09', '한글날', 'MANUAL', FALSE, now()),
  ('2026-12-25', '기독탄신일', 'MANUAL', FALSE, now()),
  ('2026-12-31', '연말휴장', 'MANUAL', TRUE, now())
ON CONFLICT (holiday_date) DO NOTHING;

-- 3) 실행 후 확인 — 10/5·10/9·12/25·12/31 네 줄이 모두 보여야 한다
SELECT holiday_date, name, source, is_market_closure FROM market_holidays
 WHERE holiday_date BETWEEN '2026-10-01' AND '2026-12-31' ORDER BY holiday_date;
