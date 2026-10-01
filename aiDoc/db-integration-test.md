# DB 통합 테스트 (P3 Testcontainers)

- 날짜: 2026-09-30
- 계획: `refactoring-plan.md` P3 표 "Testcontainers 통합 테스트"
- 사용자 결정(2026-09-30): **운영 정확도 우선, Docker가 없을 때도 대응.**

## 1. 결정

- `support/PostgresTestDatabase`가 테스트 JVM당 PostgreSQL 하나를 띄운다.
  1. Docker가 있으면(운영 PC의 Docker Desktop, GitHub Actions ubuntu 러너) Testcontainers로 **운영과 같은 이미지** `postgres:16-alpine`(infra/docker-compose.yml)을 쓴다.
  2. 없으면(Claude 작업 환경, Docker를 켜지 않은 PC) zonky 내장 PostgreSQL을 쓴다. 바이너리는 `embedded-postgres-binaries-bom:16.15.0`으로 **운영과 같은 16.15**(2026-09-30 기동 로그 `PostgreSQL 16.15`)에 고정했다. 서버 시간대는 이미지 기본과 같은 UTC.
  - 어느 쪽으로 검증됐는지 로그 한 줄(`DB 통합 테스트 PostgreSQL: ...`)과 실패 메시지(`PostgresTestDatabase.kind()`)에 남긴다.
- 테스트 부모 `support/PostgresDataJpaTest`(`@DataJpaTest` + 실제 DB). 컨텍스트가 뜨는 것 자체가 Flyway 마이그레이션과 `ddl-auto: validate` 검증이다. 하위 클래스는 컨텍스트 캐시로 한 번만 뜬다.
- 테스트 이름 끝은 `DbTest`. 일반 `test` 작업에서 같이 돈다(CI 명령 변경 없음).
- 쓰지 않던 H2(`testRuntimeOnly`)를 제거했다(P3 표의 "쓰지 않는 H2는 제거"). 제거 후 전체 테스트 통과.

### 버린 대안

- Testcontainers만: Docker 없는 환경에서 DB 테스트가 전부 건너뛰어져, Claude 작업 환경에서 DB 관련 변경을 검증할 수 없다.
- 내장 PG만: 운영 이미지(alpine, musl)와 빌드가 다르다. 버전은 맞춰도 Docker가 있는 곳에서는 운영 이미지가 더 정확하다.
- H2: 운영 DB와 SQL·타입이 달라 검증 가치가 낮다(기존에도 쓰지 않았다).

## 2. 첫 테스트 — 그동안 "미검증"이던 항목

| 테스트 | 검증 | 이전 상태 |
|---|---|---|
| `SchemaAndTimeZoneDbTest` Flyway 적용 | V1~V8 모두 성공, 엔티티 ↔ 스키마 일치(validate) | 재기동 시 로그로만 확인 |
| `SchemaAndTimeZoneDbTest` DATE·TIMESTAMPTZ | `LocalDate`·`Instant`가 변환 없이 저장(A5) | A5 실측은 일회성 실험 |
| `OrderRepositoryDbTest` 값 객체 컬럼 | `StockCode`·`BrokerOrderId` 컨버터가 기존 문자열 컬럼에 저장, `findByBrokerOrderId(BrokerOrderId)` 파생 쿼리 | A1 조각 1·4 **미검증** |
| `OrderRepositoryDbTest` 낙관적 잠금 | 같은 주문을 두 트랜잭션에서 고치면 나중 저장이 `ObjectOptimisticLockingFailureException` | R2 **미검증**(`order-concurrency.md`) |
| `IpoDealRepositoryDbTest` 낙관적 잠금(2026-10-01 추가) | 배치 재계산과 수동 입력이 같은 딜을 고치면 배치의 나중 저장이 `OptimisticLockingFailureException`(하위 `ObjectOptimisticLockingFailureException`)으로 거부되고 먼저 저장한 수동 지표가 남는다. 저장마다 `version`이 오른다 | R2 IPO 딜 **미검증**(`order-concurrency.md`) |

- 변이 확인: `OrderEntity`의 `@Version`을 빼면 잠금 테스트가 "예외가 나지 않았다"로 실패한다(실행 확인). `IpoDealEntity`도 같다(2026-10-01 실행 확인 — 2건 모두 실패).

## 3. 실행 결과

- Claude 작업 환경(Docker 없음): 내장 16.15로 4건 통과. 전체 `test` 561건 통과(건너뜀 16).
- 운영 PC(2026-09-30 23:12, `.\gradlew.bat test` 4분 20초): **Docker 경로 확인** — 로그 `DB 통합 테스트 PostgreSQL: docker postgres:16-alpine`, DB 테스트 4건 통과. 전체 561건(app 507, common 54) 통과, 건너뜀 0(PC에는 키움 키·시세 데이터가 있어 스모크·실데이터 실험까지 실행). CI(ubuntu-latest)도 Docker 경로를 탄다.

## 4. 앞으로

- DB가 걸린 변경(마이그레이션, 엔티티, 저장소 쿼리)은 `PostgresDataJpaTest`를 상속한 `*DbTest`로 검증한다.
- 후보: 이벤트 스토어 JSON(`EventAuditListener`) 저장 형식, `DailyPerformance` upsert. (IPO 딜 `@Version`은 2026-10-01 추가)
