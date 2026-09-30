# 주문·IPO 딜 동시 갱신 조사 (R2)

- 날짜: 2026-09-29
- 계획: `refactoring-plan.md` R2
- 상태: **R2 커밋 `fb02d61`, R4 커밋 `3f6dc77`**. 사용자 확정(2026-09-29): R2 수정 진행, R4는 (a) 방식.
- 1~5절은 조사 기록, 7절 이후는 구현 기록이다.

## 1. 조사 범위와 방법

- 대상
  - `trading/OrderEntity`를 갱신하는 모든 경로: `TradingService`, `OrderNoticeHandler`, `ReconciliationService`, `StaleOrderCanceller`
  - `ipo/IpoDealEntity`를 갱신하는 경로: `IpoSyncScheduler`, `IpoDealCommandService`
- 방법: 코드 읽기.
- 근거 수준
  - 운영 로그는 이 PC에 없다. `logs/autostock.log`와 `app/logs`가 존재하지 않는다. 그래서 **실제 발생 여부는 미확인**이다.
  - 아래 경합은 **코드로 성립이 확인된 경로**다. 발생 빈도는 **추론**이다.

## 2. 공통 전제 (코드 확인)

- 주문 갱신 경로 어디에도 `@Transactional`이 없다. 따라서 `findBy…`가 돌려준 엔티티는 조회가 끝나면 분리(detached) 상태다.
- `OrderRepository.save`(Spring Data `SimpleJpaRepository`)는 id가 있는 엔티티를 `merge`한다. merge는 들고 있던 사본의 **모든 컬럼으로 행을 덮어쓴다**.
- `OrderEntity`에 `@Version`이 없다. 그래서 "나중에 저장한 사본이 이긴다(last write wins)".
- 경로별 실행 스레드가 다르다. `@EventListener`는 동기 실행이므로 발행자 스레드에서 돈다.
  - 체결·접수 통보: WS 수신 스레드(`KiwoomWebSocketClient` → `OrderNotice` → `OrderNoticeHandler`)
  - 취소: 대시보드 HTTP 스레드 또는 Telegram 폴러(`CancelRequest` → `TradingService.onCancelRequest`)
  - 타임아웃 취소: 스케줄러 스레드(`StaleOrderCanceller`, 60초 주기)
  - 대사: 스케줄러 스레드(5분 주기)와 기동 시
- `OrderStatus` 전이표는 `CANCEL_REQUESTED → FILLED / PARTIALLY_FILLED`를 허용한다. 즉 취소 요청 중에도 체결 반영이 **정상 경로**다.

## 3. 발견 사항

### F1. 취소 중 체결이 덮여 사라짐 — 심각도 높음

**경로(코드 확인)**: `TradingService.onCancelRequest`

1. `findByClientOrderId`로 사본 A를 얻는다(`filledQuantity = 0`, SUBMITTED).
2. CANCEL_REQUESTED로 바꾸고 저장한다.
3. `brokerPort.cancelOrder(...)`를 호출한다. 키움 REST라 수백 ms에서 수 초가 걸린다.
4. 3이 진행되는 동안 체결 통보가 도착한다. `OrderNoticeHandler`는 새 사본 B를 읽고 `applyFill(delta)`로 `filledQuantity = N`, PARTIALLY_FILLED/FILLED로 바꿔 저장한다. 그리고 `Fill` 이벤트를 발행해 PositionBook에 N주를 반영한다.
5. 취소 결과에 따라 사본 A가 저장된다.
   - 브로커 취소 성공 → A를 CANCELLED로 저장한다.
   - 이미 체결돼 취소 거부 → A를 UNKNOWN으로 저장한다.
   - 어느 쪽이든 **`filledQuantity`가 0으로 되돌아간다.**

**결과**

- DB 주문의 체결 수량과 PositionBook이 어긋난다.
- 같은 주문에 누적 체결 통보가 더 오면 `delta = 누적량 − 0`으로 계산돼 **이미 반영한 수량을 다시 반영한다(이중계상).** 운영 1일차 ⑥과 같은 종류의 사고다.
- `ReconciliationService.probeFillStatus`는 스텁이다(항상 empty, TODO 실측). 대사로도 복구되지 않는다.

`StaleOrderCanceller.cancelOne`도 같은 구조다. 다만 사본 저장이 브로커 호출 **전**에 한 번뿐이라, 창은 조회에서 저장까지(ms)로 더 좁다.

### F2. 대사 중 체결·접수가 덮임 — 심각도 중간

**경로(코드 확인)**: `ReconciliationService.reconcile`

1. `findByStatusIn(UNKNOWN, SUBMITTED)`로 사본 목록을 얻는다.
2. `brokerPort.outstandingOrders()`를 호출한다(수 초).
3. UNKNOWN이었던 사본을 SUBMITTED로 저장한다.

- 2가 진행되는 동안 WS 통보로 ACCEPTED나 체결이 반영되면 3에서 덮인다.
- 대상은 brokerOrderId가 있는 UNKNOWN(취소 실패로 UNKNOWN이 된 주문)뿐이다. 그래서 F1보다 드물다(**추론**).

### F3. 브로커 주문번호 저장 전에 도착한 체결 통보가 버려짐 — 심각도 높음 (별개 결함, 순서 경합)

**경로(코드 확인)**: `TradingService.executeLive`

1. `brokerPort.placeOrder` 응답을 받는다.
2. `markSubmitted(brokerOrderId)`로 바꾸고 저장한다(184행).
3. `brokerOrderIdToRequest.put`을 한다(185행).

WS 체결 통보가 2보다 먼저 오면 `OrderNoticeHandler`는 `findByBrokerOrderId`도 인메모리 매핑도 찾지 못한다. 그러면 `"brokerOrderId 매핑 없는 체결통보 무시"`를 WARN으로 남기고 **통보를 버린다**.

- 지정가가 시장가에 닿아 즉시 체결되거나, REST 응답보다 WS가 빠를 때 생긴다.
- 한 번에 전량 체결되는 주문이면 뒤에 오는 통보가 없다. 그러면 체결이 **영구 누락**되고, 대사로도 복구되지 않는다(F1과 같은 스텁 문제).
- 접수 통보가 먼저 오는 것은 debug 로그만 남고 ACCEPTED를 건너뛴다. 무해하다.
- 이것은 lost update(R2)가 아니라 **이벤트 순서 경합**이다. 계획서에는 새 항목 R4로 추가했다.

### F4. IPO 배치와 수동 입력 — 심각도 낮음

**경로(코드 확인)**: `IpoSyncScheduler.syncOneDeal`

1. `findByRceptNo`로 사본을 얻는다.
2. `dartClient.fetchOfferingDetail`을 호출한다(수 초, R1 이후 최대 20초).
3. 사본을 저장한다.

- 2가 진행되는 동안 `POST /api/ipo/{id}/metrics`로 입력한 기관경쟁률, 확약률, 상장일이 3에서 **옛 값으로 덮인다**.
- 이어지는 `findAll → saveAll` 구간(ms)도 같다.
- 배치는 평일 08:20, 기동 시, 그날 실패했을 때만 돈다. 수동 입력은 사람이 한다. 겹칠 확률은 낮다(**추론**).

## 4. 권고

### R2 수정안 (F1, F2, F4) — 낙관적 잠금 (규칙 §7 "갱신 충돌이 드문 곳은 `@Version`")

1. Flyway `V8`: `orders.version BIGINT NOT NULL DEFAULT 0`, `ipo_deals.version BIGINT NOT NULL DEFAULT 0`
2. `OrderEntity`와 `IpoDealEntity`에 `@Version private long version;`을 단다.
3. 충돌(`ObjectOptimisticLockingFailureException`) 처리를 경로별로 정한다.
   - `OrderNoticeHandler`: 다시 읽고 전체를 1회 재처리한다. delta는 DB 누적치 기준이므로 재처리해도 안전하다. Fill 발행은 저장 성공 **뒤**에만 한다(현재는 저장 실패 catch 뒤에도 발행 → 순서를 조정해야 함, 주의).
   - `onCancelRequest` / `StaleOrderCanceller`: 브로커 호출 뒤 최종 저장에서 충돌하면 다시 읽는다.
     - 이미 FILLED나 PARTIALLY_FILLED로 진행됐으면 그 상태를 존중한다. 취소 성공이면 잔량 취소로 보고, 전량 체결이면 FILLED를 유지한다.
     - 그 외에는 다시 적용한다.
   - `ReconciliationService`: 충돌하면 이번 회차는 그 주문을 건너뛴다. 다음 5분 주기에 다시 판단한다.
   - `IpoSyncScheduler`: 딜 단위로 충돌을 건너뛴다(기존 딜별 격리 정책과 같다). `IpoDealCommandService`는 `409`로 답해 사용자가 다시 입력하게 한다.
4. 테스트
   - 엔티티 단위: 전이와 누적 불변식
   - 경로별: 충돌 시 재처리와 스킵
   - 실제 `@Version` 동작(동시 저장 → 예외)은 DB가 필요하다. Testcontainers(P3) 결정 전에는 **미검증**으로 남는다.
- 대안(기각)
  - 주문별 인메모리 락: 취소 중 브로커 호출 동안 WS 수신 스레드가 막힌다.
  - 조건부 UPDATE 쿼리: 상태기계 로직이 SQL로 새어 나간다.

### R4 수정안 (F3) — 사용자 결정 필요

- (a) **권고**: 매핑 없는 체결 통보를 짧게 보류한다.
  - 인메모리 대기열(상한 있음)에 넣는다.
  - `TradingService`가 brokerOrderId를 저장한 뒤 매핑 등록 이벤트를 내면, 대기열에서 꺼내 재처리한다.
  - 일정 시간(예: 30초)이 지나도 매핑되지 않으면 기존처럼 WARN으로 남기고 버린다(수동 주문).
- (b) 체결내역 조회 TR(`probeFillStatus` 스텁)을 구현해 대사로 복구한다. 근본적이지만 TR 실측이 먼저 필요하다.
- (a)와 (b)는 함께 쓸 수 있다. (a)는 즉시 반영, (b)는 최종 안전망이다.

## 5. 운영 영향

- 현재 모의 운영(C3)이다. `live` 프로필은 게이트 ② 통과 전 금지다.
- F1과 F3은 **실계좌 전환 전에 반드시 막아야 할 결함**이다.
- 모의 서버도 WS 통보와 취소가 실제처럼 오므로, 모의 운영 중에도 발생할 수 있다.
- 확인 방법: 운영 로그에서 아래를 검색한다.
  - `brokerOrderId 매핑 없는 체결통보 무시`(F3 흔적)
  - 같은 clientOrderId에 대해 `취소 완료`나 `취소 실패` 직전 체결 로그가 있는지(F1 흔적)

## 6. 남은 일

- 운영 로그 위치 확인 및 위 흔적 검색(사용자에게 로그 경로 문의)
- 재기동 후 확인
  - Flyway V8 적용과 `ddl-auto: validate` 통과
  - 주문 1건의 정상 흐름(VALIDATED → SUBMITTING → SUBMITTED 저장)에서 충돌 예외가 나지 않는지
  - 로그 `주문 동시 갱신 충돌`과 `보류했던 체결통보 재처리`의 빈도
- 실제 DB에서 `@Version` 충돌 동작 검증: Testcontainers(P3) 결정 뒤

## 7. 구현 — R2 낙관적 잠금 (커밋 `fb02d61`)

### 결정과 근거

- `V8__optimistic_lock_version.sql`: `orders.version`, `ipo_deals.version`(`BIGINT NOT NULL DEFAULT 0`). 앞으로만 가는 마이그레이션이다(§8).
- `OrderEntity`와 `IpoDealEntity`에 `@Version long version`.
  - 원시 타입이라 Spring Data의 `isNew` 판정은 기존처럼 id로 한다.
- **함정: 저장 반환값을 이어 써야 한다.** `save`(merge)는 버전이 오른 새 사본을 돌려준다. 옛 인스턴스를 다시 저장하면 반드시 충돌한다.
  - `TradingService.executeLive`: `entity = save(entity)`로 바꿨다.
  - `onCancelRequest`도 같다.
  - 이것을 빼먹으면 정상 주문이 전부 실패한다. 테스트 `LIVE_주문은_버전이_오른_저장_반환본으로_이어서_저장한다`로 고정했다.
- `trading/OptimisticRetry`(package-private 헬퍼): 충돌이 나면 최신 주문을 다시 읽어 같은 변경을 다시 적용한다(최대 3회). 쓰는 곳이 5곳이다(규칙 §2.1 3의 법칙).
- 경로별 처리
  - `OrderNoticeHandler`
    - 체결: 충돌 시 최신 누적치로 증분을 다시 계산한다.
    - 인메모리 누적금액 맵(`cumulativeNotional`)은 저장 성공 뒤에만 갱신한다. 재시도할 때 직전 값이 오염되지 않게 하기 위해서다.
    - Fill 발행 조건(상태 전이 실패여도 발행)은 기존 그대로다.
    - 접수: 같은 방식으로 재시도한다.
  - `TradingService.onCancelRequest`
    - CANCEL_REQUESTED 저장이 충돌하면 최신 상태로 취소 가능 여부를 다시 판정한다.
    - 브로커 호출 뒤에는 **들고 있던 사본이 아니라 최신 주문을 다시 읽어** 결과를 적용한다(`applyCancelOutcome`, F1의 직접 해결).
    - 성공인데 그 사이 부분체결됐으면: 체결분을 보존하고, PARTIALLY_FILLED → CANCEL_REQUESTED → CANCELLED로 간다.
    - 이미 전량 체결됐으면: FILLED를 유지한다(UNKNOWN으로 되돌리지 않음).
    - 브로커 호출 예외와 상태 반영 예외를 분리했다. 예전 코드는 CANCELLED 저장 예외까지 "취소 실패"로 오인할 수 있었다.
  - `StaleOrderCanceller`: 충돌 시 다시 읽는다. SUBMITTED가 아니면 취소하지 않는다.
  - `ReconciliationService`: 충돌 시 최신 상태로 다시 판정한다(`updateLatest`).
  - `IpoSyncScheduler`
    - 딜 동기화 충돌은 INFO로 남기고 건너뛴다(예상된 경합이라 ERROR가 아니다, §6).
    - 재계산 `saveAll`을 딜별 `save`로 바꿔 충돌한 딜만 건너뛴다.
  - `IpoController`: 수동 입력 충돌은 `409`로 답한다. B1(전역 예외 처리) 전이라 컨트롤러 로컬에서 처리했다.

### 버린 대안

- 주문별 인메모리 락: 취소 중 브로커 호출 동안 WS 수신 스레드가 막힌다.
- 조건부 UPDATE 쿼리: 상태기계 로직이 SQL로 샌다.
- 테스트 목에 맞춘 `save` 반환값 null 폴백: 운영 코드에 테스트 사정을 들이게 된다. 대신 테스트가 `save`를 인자 반환으로 스텁한다(실제 JPA도 저장본을 반환한다).

### 함정과 주의

- 새로 주문 상태를 바꾸는 코드는 `OptimisticRetry.run`을 거치고 `save` 반환값을 써야 한다.
- `OptimisticRetry`에 넘기는 변경 함수에는 저장 전에 되돌릴 수 없는 부수효과(이벤트 발행, 브로커 호출, 인메모리 갱신)를 넣지 않는다. 재시도하면 다시 실행된다.
- 3회 연속 충돌하면 예외가 호출부로 간다. 체결 통보는 WS 리스너 예외로 로그에 남고, 그 통보는 반영되지 않는다. 다음 누적 통보가 오면 복구된다.
- `OrderNoticeHandlerTest.java`는 저장소에 CRLF로 커밋돼 있다. sed로 편집하면 LF로 바뀌어 파일 전체가 diff로 잡힌다(커밋 전에 복원함).

## 8. 구현 — R4 체결 통보 보류 (커밋 `3f6dc77`)

### 결정과 근거

- 사용자 확정 (a) 방식이다.
- `trading/PendingOrderNotices`(package-private, `OrderNoticeHandler`가 소유)
  - 주문번호가 인메모리에도 DB에도 없는 체결 통보를 버리지 않고 보류한다.
  - 보류 시간 30초(`HOLD`, 키움 REST 상한 15초 + 여유), 상한 200건.
- `OrderNoticeHandler.replayPendingNotices()`(`@Scheduled(fixedDelay = 1_000)`)
  - 보류한 주문번호가 등록됐으면 꺼내 `onOrderNotice`로 재처리한다.
  - 만료된 통보는 기존 문구 `brokerOrderId 매핑 없는 체결통보 무시`로 WARN을 남기고 버린다.
- 처음 설계는 TradingService가 등록 직후 보류 통보를 `publisher`로 다시 발행하는 방식이었다. 이를 **폐기**했다.
  - 이유 1: `OrderNotice`를 듣는 다른 리스너(향후 감사·피드)가 같은 통보를 두 번 받는다(최소 놀람 위반).
  - 이유 2: 핸들러를 직접 부르면 TradingService와 핸들러 사이에 빈 순환 의존이 생긴다.
  - 1초 폴링으로 바꾸면서 TradingService는 수정하지 않았다. 보류 직후 등록과 엇갈리는 경합도 다음 주기가 잡는다.
- 재처리 통보와 새 WS 통보가 겹쳐도 안전하다.
  - 증분은 DB 누적치 기준이다. 이미 반영된 누적치는 delta ≤ 0으로 무시된다.
  - 저장 충돌은 R2 재시도가 처리한다.
- `OrderNoticeHandler` 생성자에 `Clock`을 추가했다(기존 `ClockConfig` 빈). 만료 판정을 테스트에서 고정할 수 있게 하기 위해서다.

### 함정과 주의

- 반영 지연은 등록 후 최대 1초다.
- 앱 재시작 시 보류함은 사라진다(인메모리). 재시작 직전 30초 안에 온 미매핑 통보는 유실될 수 있다. 대사 복구(`probeFillStatus` 스텁 구현, R4 (b))가 최종 안전망이다.
- 접수 통보는 보류하지 않는다(누락돼도 무해, 기존 동작).

## 9. 변경 파일

- R2: `V8__optimistic_lock_version.sql`, `OrderEntity`, `IpoDealEntity`, `OptimisticRetry`(신규), `OrderNoticeHandler`, `TradingService`, `StaleOrderCanceller`, `ReconciliationService`, `IpoSyncScheduler`, `IpoController`
  - 테스트: `TradingServiceTest`(+4), `OrderNoticeHandlerTest`(+1), `StaleOrderCancellerTest`(+1), `IpoControllerTest`(+1). 네 trading 테스트의 setUp에 `save` 인자 반환 스텁을 추가했다.
- R4: `PendingOrderNotices`(신규), `OrderNoticeHandler`
  - 테스트: `OrderNoticeHandlerTest`(+2, 기존 "매핑 없는 통보는 무시"를 "보류한다"로 개명)

## 10. 롤백

- R2
  - 커밋을 되돌린다.
  - V8 컬럼은 남아도 무해하다(엔티티가 매핑하지 않으면 `validate`는 여분 컬럼을 문제 삼지 않는다).
  - 마이그레이션 파일은 지우지 말고 둔다. Flyway 이력과 어긋나기 때문이다.
- R4: 커밋을 되돌리면 예전처럼 즉시 WARN을 남기고 버린다.

## 11. 검증 상태

- R2: `.\gradlew.bat test` 전체 통과(2026-09-29, R2만 반영한 상태).
  - `TradingServiceTest` 9, `OrderNoticeHandlerTest` 12, `StaleOrderCancellerTest` 4, `ReconciliationServiceTest` 8, `IpoControllerTest` 8, `IpoSyncSchedulerTest` 10, `ModularityTests` 통과
- R4: 커밋 `3f6dc77`. `.\gradlew.bat test` 전체 통과(2026-09-30, `OrderNoticeHandlerTest` 14건).
- 머지(2026-09-30): `origin/main`의 `47ff91d`(장외 대기, `MarketSessionService`)를 머지했다(`2c5d185`).
  - 텍스트 충돌은 없었다.
  - R2에서 추가한 `StaleOrderCancellerTest`가 옛 4인자 생성자를 써서 컴파일이 깨졌다. `8947f7c`에서 고쳤다.
  - 머지 후 전체 테스트 통과. `StaleOrderCanceller`와 `ReconciliationService`에 STANDBY 스킵과 R2 재시도가 모두 들어 있음을 확인했다.
- **Red 미확인**: 새 테스트는 구현 뒤에 작성했다. 수정 전 코드에서 실패하는지는 실행으로 확인하지 않았다. 코드상으로는 옛 사본 저장 경로에서 실패해야 한다(추론).
- **미검증**
  - 실제 PostgreSQL에서 `@Version` 충돌 예외 발생과 Spring 예외 변환. 단위 테스트는 목으로 `ObjectOptimisticLockingFailureException`을 흉내 낸다.
  - V8 적용과 기동.

## 12. 변경 이력

- 2026-09-29: 최초 작성(조사만, 코드 변경 없음)
- 2026-09-30: R2·R4 구현 기록 추가
