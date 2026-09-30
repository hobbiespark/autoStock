# 시간대 명시 (A5)

- 날짜: 2026-09-30
- 계획: `refactoring-plan.md` A5, 규칙 §2.1 "시간대는 명시", §9 "변환하는 층은 한 곳에"

## 1. 조사 (확인)

- **스키마**: 시각 컬럼은 모두 `TIMESTAMPTZ`, 날짜 컬럼은 모두 `DATE`다(V1~V8). 시간대 없는 `TIMESTAMP`는 없다.
- **엔티티**: `TIMESTAMPTZ` ↔ `Instant`, `DATE` ↔ `LocalDate`만 쓴다. `LocalDateTime`·`OffsetDateTime`·`java.util.Date` 필드는 없다.
- **현재 시각**: `Clock` 빈은 `Clock.systemUTC()`다. 업무 날짜는 `LocalDate.now(clock.withZone(MarketConstants.KST))`처럼 KST를 명시한다(A4). `ZoneId.systemDefault()`, 인자 없는 `LocalDate.now()` 같은 JVM 기본 시간대 의존 호출은 없다(grep).
- **스케줄**: `@Scheduled(cron)` 13개가 모두 `zone = "Asia/Seoul"`을 명시한다.
- **응답 JSON**: 뷰의 시각은 `Instant`라 ISO-8601 UTC(`...Z`)로 나간다. 날짜는 `LocalDate`(`2026-09-30`).
- **운영 DB**: `postgres:16-alpine`(infra/docker-compose.yml), 서버 `timezone` 설정 없음 → 이미지 기본 UTC.
- **JVM**: 운영 PC는 KST다(로그 `+09:00`). `user.timezone`을 따로 주지 않는다.

## 2. 실측 — `hibernate.jdbc.time_zone`이 `DATE`를 하루 밀지 않는가

계획서대로 `hibernate.jdbc.time_zone: UTC`를 넣으면, Hibernate 버전에 따라 JVM이 KST일 때 `LocalDate`가 UTC 달력으로 바뀌어 하루 전 날짜로 저장되는 문제가 알려져 있다. 그래서 넣기 전에 실제 PostgreSQL로 확인했다.

- 방법: 내장 PostgreSQL(zonky embedded-postgres 2.1.0, PG 14, 서버 `timezone=UTC`)에 Flyway로 운영 스키마를 올리고, JVM `-Duser.timezone=Asia/Seoul`로 `@DataJpaTest`를 돌렸다. `MarketHolidayEntity(LocalDate 2026-10-03, Instant 2026-09-30T00:30:00Z)`를 저장하고 SQL로 원시 값을 읽었다. 실험 코드는 커밋하지 않았다.
- 결과(Hibernate 6.6.53):

| 설정 | DB `holiday_date` | DB `synced_at`(UTC) | 조회 |
|---|---|---|---|
| `time_zone` 없음(이전) | `2026-10-03` | `2026-09-30 00:30:00` | 정상 |
| `time_zone: UTC` | `2026-10-03` | `2026-09-30 00:30:00` | 정상 |

→ 이 버전에서는 설정 유무와 관계없이 값이 같다. 넣어도 동작은 바뀌지 않는다.

## 3. 결정

- `spring.jpa.properties.hibernate.jdbc.time_zone: UTC` — 저장 시간대를 명시한다. 지금은 동작이 같고, 나중에 시간대 없는 `TIMESTAMP` 컬럼이 생겨도 JVM 시간대에 따라 달라지지 않는다.
- `spring.jackson.time-zone: UTC` — `Instant`는 원래 UTC라 결과가 같다. 기본값에 기대지 않게 적는다.
- **JVM `user.timezone`은 고정하지 않는다.** 코드가 시간대를 모두 명시하므로 필요 없고, 로그 시각은 운영자가 읽는 KST가 낫다.
- **규칙 테스트 추가**: `ArchitectureRulesTest.cron_스케줄은_시간대를_명시한다` — `@Scheduled(cron)`에 `zone`이 없으면 실패한다. `zone`을 뺀 변이로 실패함을 확인했다.

## 4. 변환 층 (한 곳 원칙)

| 무엇 | 시간대 | 어디서 |
|---|---|---|
| DB 저장·조회 시각 | UTC(`TIMESTAMPTZ` + `Instant`) | JPA, `hibernate.jdbc.time_zone: UTC` |
| 업무 날짜(거래일·휴장일·판단일·만료일) | KST | `MarketConstants.KST`로 계산해 `LocalDate`로 저장 |
| 현재 시각 | UTC `Instant` | `Clock` 빈(`Clock.systemUTC()`) |
| 스케줄 | KST | `@Scheduled(..., zone = "Asia/Seoul")` — 규칙 테스트로 강제 |
| 외부 응답 시각 파싱 | KST로 해석 | 어댑터(예: 키움 `expires_dt`, WS 체결시간 FID 20) |
| API 응답 | UTC ISO-8601 | Jackson(`spring.jackson.time-zone: UTC`) |
| 로그 | JVM 기본(운영 PC KST) | Logback 기본 패턴 |

## 5. 부산물 — Docker 없이 실제 PostgreSQL로 테스트하는 방법

→ 2026-09-30 사용자 결정으로 도입했다(Docker 우선, 없을 때 내장 PG). `db-integration-test.md` 참고. 아래는 결정 전 기록이다.

P3의 Testcontainers 결정과 관련된 정보다. zonky embedded-postgres는 Docker 없이 Maven 의존성만으로 실제 PostgreSQL을 띄운다. 작업 환경(Docker 없음, root)에서도 동작했다. 저장소·Flyway·`ddl-auto: validate`·값 객체 컨버터(`BrokerOrderIdConverter`, `StockCodeConverter`)를 검증하는 통합 테스트에 쓸 수 있다. 도입 여부는 P3와 함께 정한다.

## 6. 변경 파일과 검증

- 수정: `app/src/main/resources/application.yml`
- 테스트: `ArchitectureRulesTest` +1
- `.\gradlew.bat test` 전체 통과(2026-09-30, 557건, 건너뜀 16).
- **미검증**: 운영 PC 재기동 후 DB 값·대시보드 시각 표시(동작이 같을 것으로 **추론**, 위 실측 근거).
