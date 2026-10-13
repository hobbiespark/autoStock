-- 일 손실 한도용 손익 (2026-10-13, aiDoc/upgrade-2026-10/16-log-review-2026-10-13.md) — risk 모듈 소유.
--
-- 예전에는 일 손실 한도를 매입 평단 대비 실현손익으로 쟀다. 며칠에 걸쳐 쌓인 평가손실이 청산하는 날 한꺼번에
-- "오늘 손실"이 되어, 10/13 09:05 C3 국면 OFF 강제 청산에서 -1,097만 원(한도 -977만 원)으로 킬스위치가 켜졌다.
-- 이제 risk.DailyPnlTracker가 밤사이 보유분은 전일 종가, 오늘 산 분량은 매수가 대비로 잰 값을 따로 저장하고
-- 킬스위치는 그 값으로 판단한다. realized_pnl(매입 평단 대비)은 리포트·화면용으로 그대로 둔다.
-- 예전 행은 NULL — 복원할 때 realized_pnl로 대신한다(보수적).
ALTER TABLE risk_daily_pnl ADD COLUMN limit_pnl NUMERIC(19,4);

COMMENT ON COLUMN risk_daily_pnl.limit_pnl IS '일 손실 한도용 손익 — 밤사이 보유분은 전일 종가, 오늘 산 분량은 매수가 대비(V17). NULL이면 realized_pnl로 대신';
