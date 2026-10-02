# 감사 신뢰성 — 문구 정정·실패 카운터·스키마 버전·발행 로그 컬럼 (Phase 1.8)

- 날짜: 2026-10-02(금) 장마감 후
- 계획: `upgrade-2026-10/11-execution-plan.md` 1.8(BE-P1-7·P2-12), 5.0과 같은 작업(발행 로그 컬럼 — 둘 중 먼저 오는 쪽에서 한 번만)
- 사용자 결정: **D-07 권고안 (a)** — 2026-10-02 19:41 "권고 로 적용 함". `event_publication`에 널 허용 3컬럼만 추가(Expand)

## 1. 목적

- **틀린 안심 문구(BE-P1-7).** `EventAuditListener` 설명이 "비동기라 유실될 수 있지만 Modulith 이벤트 발행 로그가 보완한다"고 했다. 그 표(`event_publication`)는 한 번도 기록되지 않는다(`@ApplicationModuleListener` 0건). 보완 장치가 있다고 믿게 만드는 문구였다.
- **실패가 보이지 않음.** 감사 저장 실패는 ERROR 로그뿐이었다.
- **스키마 버전 고정(BE-P2-12).** 모든 행을 `schema_version = 1`로 기록했다. `Signal`은 `fixedQuantity`가 더해진 v2인데도 1로 남아, 리플레이·분석에서 형식을 구분할 수 없었다.
- **이관 대비(D-07).** Modulith 2.x는 `event_publication`에 `status`·`completion_attempts`·`last_resubmission_date`를 요구한다.

## 2. 결정과 근거

- **문구 정정:** "유실을 보완하는 장치는 없다. 감사 스토어는 사람이 보는 추적이고, 주문·체결의 최종 사실은 주문 테이블과 브로커(대사)에 있다"로 바꿨다.
- **카운터 `audit.write.failure{type}`:** 직렬화·저장 실패 때 이벤트 타입별로 센다. 알림은 계획(B4)대로 보류.
- **타입별 스키마 버전 표 `EventAuditListener.SCHEMA_VERSIONS`:** `Signal`=2, 나머지(MarketTick·SignalDecision·OrderRequest·Fill·MacroIndicator·NewsSentiment)=1 — 각 이벤트 record 설명의 "(스키마 vN)"과 맞췄다. 과거 행은 그대로 둔다(2026-10-02 전 행은 모두 1).
  - 테스트가 감사 대상 타입(리스너 메서드 7개)이 모두 표에 있는지 확인한다. 새 감사 대상을 더하고 표를 빠뜨리면 실패한다.
- **V15:** `event_publication`에 `status TEXT`, `completion_attempts INT`, `last_resubmission_date TIMESTAMPTZ`(모두 널 허용). 지금 Modulith 1.4는 이 컬럼을 모르고 무시한다. Boot 4.1·Modulith 2 이관(5.1) 때 쓴다. 5.0에서 다시 하지 않는다.

## 3. 버린 대안과 보류

- **발행 로그 실사용(D-07 (c)):** 발행 경로에 트랜잭션을 들여야 하는 대공사다. 보류.
- **`spring-modulith-starter-jpa` 제거(D-07 (b)):** Kafka 외부화(ADR-2) 여지를 없앤다. 버렸다.
- **과거 `Signal` 행을 2로 고치기:** 언제부터 v2 형식이었는지 행마다 판정해야 하고, 원본 불변(append-only) 원칙과 맞지 않는다.

## 4. 변경 파일

- 운영: `audit/EventAuditListener`(설명·버전 표·실패 카운터, 생성자에 `MeterRegistry`), `db/migration/V15__event_publication_modulith2_columns.sql`(신규)
- 테스트: `EventAuditListenerTest`(신규 3건: 타입별 버전 Signal 2·Fill 1, 저장 실패 시 무예외·카운터, 감사 대상이 모두 버전 표에 있음), `SchemaAndTimeZoneDbTest` +1(실제 PostgreSQL: 세 컬럼·형식·널 허용, 최신 버전 15)

## 5. 함정과 주의

- 이벤트에 필드를 더해 형식이 바뀌면 그 record 설명의 "(스키마 vN)"과 `SCHEMA_VERSIONS`를 함께 올린다(규약-1: 기존 필드 이름·JSON 형식 변경은 금지, 추가만).
- V15는 적용 뒤 고치지 않는다(Flyway 체크섬).

## 6. 롤백

- 커밋을 되돌린다. V15 컬럼은 남겨도 무해하다(널 허용, 아무도 읽지 않음).

## 7. 검증 상태

- 컨테이너 전체 테스트 통과(`aiDoc/phase1-offhours-2026-10-02.md`).
- 미검증(재기동 후): 기동 로그 `Migrating schema "public" to version "15 - event publication modulith2 columns"`, 10/6 장중 `SELECT event_type, schema_version, count(*) FROM event_store WHERE recorded_at > now() - interval '1 day' GROUP BY 1, 2;`에서 Signal이 2로 남는지.
