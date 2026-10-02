-- 공모주 청약 알림 중복 방지 (2026-10-02 운영 발견, aiDoc/small-fixes-2026-10-02.md) — ipo 모듈 소유 ipo_deals에 더한다.
--
-- ① 한 회사가 신고서를 여러 번 내면(정정·발행조건확정) 딜이 rcept_no마다 따로 있어 같은 청약 알림이 딜 수만큼 나갔다.
-- ② 마지막 수집일이 메모리에만 있어 재기동할 때마다 그날 알림을 다시 보냈다(감사 BE-P2-6).
-- 알림을 보내면 그 회사의 모든 딜에 (단계, 날짜)를 남기고, 같은 회사·단계·날짜면 다시 보내지 않는다.
ALTER TABLE ipo_deals
    ADD COLUMN last_alert_phase VARCHAR(16),     -- D-1 또는 START
    ADD COLUMN last_alert_date  DATE;            -- 그 알림을 보낸 날(KST)
