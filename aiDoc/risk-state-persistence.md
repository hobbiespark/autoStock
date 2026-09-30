# 킬스위치·일 손실·원가 장부 재기동 복원 (Phase 0.2)

- 날짜: 2026-10-01
- 계획: `upgrade-2026-10/11-execution-plan.md` 0.2, 감사 `upgrade-2026-10/09-audit-be.md` BE-P0-2
- 사용자 확정: D-05 (a) — 킬스위치·일 손실·원가 장부 모두 복원(2026-10-01)

## 1. 목적

킬스위치와 일 손실 누계가 메모리에만 있었다.

- 재기동하면(start_autostock.bat의 auto-start 포함) 비상 정지가 저절로 풀렸다. 설계 원칙 "해제는 사람만"(`KillSwitch`, RUNBOOK 7절)이 재기동 한 번으로 깨졌다.
- 일 손실 누계가 0으로 돌아가 장중 재기동 뒤 한도(-2%)가 다시 열렸다.
- 원가 장부(`DailyPnlTracker.lots`)는 `Fill`만 보고, 브로커 잔고 복원 이벤트(`PositionRestored`)는 `PositionBook`만 구독했다. 그래서 재기동 전에 산 종목을 재기동 뒤 팔면 "장부에 없는 종목"으로 처리되어 실현손익이 계산되지 않았다.
- `TradingAutoStarter` 설명의 "킬스위치가 켜진 채 재시작해도 안전"은 사실이 아니었다.

## 2. 결정과 근거

- **V9 마이그레이션(risk 모듈 소유 — `disclosure_blacklist` 선례):**
  - `risk_state`: 단일 행(`CHECK (id = 1)`). 컬럼은 `kill_switch_engaged`, `kill_switch_reason`(마지막 작동 사유 — 해제 뒤에도 남긴다), `changed_at`, `changed_by`(해제자, 작동 때는 NULL), `version`(낙관적 잠금). 초기 행은 해제 상태라서 배포 직후 첫 기동은 지금과 같다.
  - `risk_daily_pnl`: `trade_date`(KST, PK), `realized_pnl NUMERIC(19,4)`, `updated_at`. 장 마감 리포트의 `daily_performance`(V4)는 15:50 한 번 기록이라 장중 복원에 쓸 수 없다.
- **`RiskStateStore`(신규):** 두 테이블 읽기·쓰기를 맡는다. 예외를 삼키지 않고 fail-safe는 호출자가 정한다. 쓰기는 `REQUIRES_NEW`라서 호출자 트랜잭션이 롤백돼도 이미 바뀐 메모리 상태와 DB가 어긋나지 않는다.
- **`KillSwitch`:**
  - 작동·해제 때마다 저장한다. "메모리 전환 → 저장 → 이벤트" 순서가 스레드 사이에서 섞이지 않게 `ReentrantLock`으로 직렬화했다. `isEngaged()`는 락 없이 읽는다(RiskGate 핫패스).
  - 복원은 `@PostConstruct` 때 한다. 기동 완료 리스너들(자동 시작, 시작 절차의 DEGRADED 판정)이 이미 복원된 상태를 보게 하기 위해서다. 알림은 기동 완료(`ApplicationReadyEvent`) 뒤에 `KillSwitchChanged(true, "재기동 복원: <원 사유>")`로 한 번 보낸다(텔레그램 CRITICAL·이벤트 피드).
  - 저장 실패: 메모리 상태를 우선하고(작동·해제를 되돌리지 않음) ERROR를 남긴다.
  - 복원 실패(읽기 예외): 상태를 모르면 막는 쪽으로 간다 — 작동 상태로 시작하고 사유를 "상태 복원 실패 — 확인 후 수동 해제 필요"로 알린다.
- **`DailyPnlTracker`:**
  - 누계가 바뀔 때마다 오늘 행을 저장한다(락 안에서, 체결 순서대로).
  - `@PostConstruct` 때 **KST 오늘** 행으로 복원하고, 다른 날짜 행은 읽지 않는다.
  - 복원만으로는 킬스위치를 켜지 않는다. 한도 도달로 켜졌던 킬스위치는 그 자체가 복원되고, 사람이 해제했다면 그 판단을 존중한다. 다음 손실 체결에서 다시 검사한다.
  - 저장·복원이 실패해도 ERROR만 남기고 메모리 계산을 계속한다.
- **원가 장부 시드:** `DailyPnlTracker`가 `PositionRestored`를 구독한다. `PositionBook`과 같은 규칙이다 — `putIfAbsent`(이 JVM에서 체결로 만든 장부가 더 최신), 평단 미상(null)이면 시드하지 않는다.
- **`TradingSystemManager`:** 시작 절차 끝에서 `KillSwitch.isEngaged()`를 직접 확인해 켜져 있으면 RUNNING → DEGRADED로 간다. 복원 알림 이벤트는 상태가 STOPPED나 STARTING일 때 도착해 `on()`이 무시하기 때문이다. 해제되면 기존 `on()`이 RUNNING으로 되돌린다. monitor → risk 의존은 이미 있다(`DashboardController`).
- **`TradingAutoStarter`:** 설명을 사실대로 고쳤다.

## 3. 버린 대안과 보류

- **`ApplicationReadyEvent`에서 복원(계획 초안):** 같은 이벤트의 다른 리스너(자동 시작)와 순서가 보장되지 않는다. SIM은 대사 없이 곧바로 RUNNING이 되므로 복원 전에 시작 판정이 날 수 있다. 상태 복원은 `@PostConstruct`, 알림만 기동 완료 뒤로 나눴다.
- **네이티브 upsert(`ON CONFLICT`):** 한 문장이라 가볍다. 하지만 엔티티 매핑이 `ddl-auto: validate`로 검증되는 이점과 `@Version`을 쓰려고 JPA 엔티티를 택했다. 쓰기 빈도는 하루 수 회 수준이다.
- **`@Version`을 원시 `long`으로:** id를 직접 넣는 엔티티라 항상 merge로 가고, 행이 없으면 Hibernate 6.6이 예외를 던진다. 래퍼 `Long`으로 두어 새 행은 persist가 되게 했다.
- **원가 장부 테이블:** 브로커 잔고(kt00018) 평단으로 시드하면 충분하다(D-05 권고안). 개별 매수 체결 수수료까지 보존하는 정밀 장부는 범위 밖이다.

## 4. 변경 파일

- 스키마: `db/migration/V9__risk_state.sql`
- 운영 신규 5개: `risk/RiskStateEntity`, `risk/RiskDailyPnlEntity`, `risk/RiskStateRepository`, `risk/RiskDailyPnlRepository`, `risk/RiskStateStore`
- 운영 수정 4개:
  - `risk/KillSwitch` — 생성자 `(publisher, store, clock)`, 저장·복원·알림
  - `risk/DailyPnlTracker` — 생성자 `(…, killSwitch, store, clock)`, 저장·복원·`onPositionRestored`
  - `monitor/TradingSystemManager` — 생성자에 `KillSwitch`, 시작 시 DEGRADED
  - `monitor/TradingAutoStarter` — 설명만
- 테스트:
  - 신규 `risk/RiskStateStoreDbTest` 5건(실제 PostgreSQL): V9 초기 행, 작동·해제 갱신과 버전, 행 삭제 후 재생성, 두 번째 행 CHECK 거부, 일별 누계 갱신과 날짜 분리
  - `KillSwitchTest` 4 → 13건: 작동·해제 저장, 무변화 미저장, 저장 실패 시 fail-safe, 재기동 복원과 알림 1회, 해제 상태 복원, 행 없음, 읽기 실패 시 작동, 알림 전 해제
  - `DailyPnlTrackerTest` 5 → 14건: 저장, 복원 후 한도 판단, KST 날짜만 읽기, 복원만으로 킬스위치 미작동, 저장·복원 실패 시 계속, 원가 장부 시드와 평단 미상, 기존 장부 보존
  - `TradingSystemManagerTest` 8 → 9건: 켜진 채 시작하면 DEGRADED, 해제 시 RUNNING
  - 생성자 변경 반영: `RiskGateTest`, `RiskGateMacroTest`, `MarketDataStaleListenerTest`, `MacroGuardTest`, `TelegramCommandPollerPollTest`, `DailyReportSchedulerTest`
  - `SchemaAndTimeZoneDbTest`: 최신 마이그레이션 기대값 "8" → "9"

## 5. 함정과 주의

- **운영 영향(의도된 변화):** 킬스위치가 켜진 채 앱을 끄면 다음 기동도 켜진 채 시작한다. 시세 단절(장중 180초)·일 손실·VIX 가드로 켜진 경우도 마찬가지다. 다음 날 09:05 C3 판단 전에 대시보드나 `/resume`으로 **사람이 해제해야** 주문이 나간다. 텔레그램이 켜져 있으면 기동 때 "재기동 복원: …" CRITICAL 알림이 온다. 텔레그램 가동은 0.7.
- 배포 직후 첫 기동은 V9 초기 행(해제) 때문에 지금과 같다.
- DB에서 직접 해제해야 할 때(앱이 뜨지 않는 등): `UPDATE risk_state SET kill_switch_engaged = FALSE, changed_by = 'manual-sql', changed_at = now();` 실행 후 재기동한다. 평소에는 대시보드나 텔레그램으로 해제한다.
- 테스트에서 `new KillSwitch(…)`·`new DailyPnlTracker(…)`는 `@PostConstruct`를 부르지 않는다. 복원을 검증할 때는 `restorePersistedState()`·`restoreToday()`를 직접 부른다.
- 줄바꿈: 기존 파일은 원래 줄바꿈을 유지했다(`TradingAutoStarter`는 LF, 나머지는 CRLF). 새 파일은 CRLF다.

## 6. 롤백

- 코드 커밋을 되돌린다. V9 테이블은 남겨도 무해하다(전진 전용). 되돌린 코드는 테이블을 읽지 않는다.

## 7. 검증 상태

- 단위·DB 테스트: 위 4절 전부 통과(내장 PostgreSQL 16.15, 운영과 같은 버전).
- 전체 컨텍스트 재기동 검증(컨테이너 전용 임시 테스트, 저장소에는 넣지 않음): SIM, 내장 PG, auto-start. 앱을 세 번 연속 기동해 확인했다.
  - ① 빈 DB에 V1~V9 적용, 킬스위치 해제로 시작 → 매수·매도 체결 → 킬스위치 작동 → DEGRADED
  - ② 재기동 → "킬스위치 재기동 복원 — 작동 상태로 시작"과 "실현손익 누계 복원 -11588.5원" 로그 → CRITICAL 알림 "재기동 복원: E2E 테스트" → STARTING → RUNNING → DEGRADED → 해제 → RUNNING
  - ③ 재기동 → 해제 상태 유지, 누계 복원, RUNNING, 알림 없음
- 미검증(운영 PC): Docker PostgreSQL에서 V9 적용(다음 기동 때 Flyway 로그 `Successfully applied 1 migration … v9`). 장외에 킬스위치 ON → 재기동 → ON 유지 → 해제를 실측한다(계획 0.2 "실측").
