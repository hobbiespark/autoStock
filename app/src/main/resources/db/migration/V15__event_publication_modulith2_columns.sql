-- Spring Modulith 2.x 이관 대비 event_publication 컬럼 추가 (실행 계획 1.8·5.0 — 둘 중 먼저 오는 쪽에서 한 번만,
-- 사용자 결정 D-07 (a), 2026-10-02 권고안 진행, aiDoc/audit-reliability.md).
--
-- 이 표(V1)에는 지금 아무것도 쓰이지 않는다(@ApplicationModuleListener 0건 — 감사 BE-P1-7). 컬럼만 늘리는 Expand 단계다:
-- 지금의 Modulith 1.4는 이 컬럼을 모르고(널 허용이라) 무시하고, Boot 4.1·Modulith 2 이관(5.1) 때 Modulith 2가 쓴다.
ALTER TABLE event_publication
    ADD COLUMN status                 TEXT,
    ADD COLUMN completion_attempts    INT,
    ADD COLUMN last_resubmission_date TIMESTAMPTZ;
