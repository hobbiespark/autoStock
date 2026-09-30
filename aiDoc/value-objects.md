# 값 객체 도입 (A1)

- 계획: `refactoring-plan.md` A1, ARCHITECTURE.md 3절(1순위 `ClientOrderId`, `BrokerOrderId`, `StockCode`, `Quantity`, `Price`)
- 진행 방식(사용자 확정 2026-09-30): 한 번에 하지 않고 조각으로 나눈다. 1조각은 trading 내부.

## 조각 1 — trading 내부 `BrokerOrderId`, `Quantity` (2026-09-30)

### 1. 목적

trading에서 `clientOrderId`(우리 멱등키)와 `brokerOrderId`(브로커 주문번호)가 둘 다 `String`이다. 서로 바꿔 넣어도 컴파일된다. R2와 R4에서 손본 조회 경로(`findByBrokerOrderId`, 보류함·누적금액 맵 키)가 이 혼동이 사고로 이어지는 곳이다.

수량 양수 불변식은 `OrderEntity.applyFill` 안의 `if`로만 지켜졌다.

규칙 §2.2 값 객체, §4, ARCH 3절 "중요 도메인 값의 primitive 사용 금지"를 적용한다.

### 2. 규모 실측 (전면 도입을 미룬 근거)

- 공통 이벤트 11개가 종목코드·수량·가격·주문번호를 원시 타입으로 가진다(`OrderRequest`, `Fill`, `Signal`, `MarketTick`, `OrderNotice` 등).
- 운영 코드의 `String symbol` 파일은 26개(8개 모듈)다. 이벤트를 만드는 테스트는 수백 곳이다.
- 이벤트는 `event_store`에 JSON으로 쌓이고 백테스트 리플레이에 쓰인다. 타입을 바꾸면 JSON 호환을 유지해야 한다. 값 객체를 문자열 스칼라로 직렬화하면 된다(**추론**, 미구현).

### 3. 결정과 근거

- **위치:** `common/util/BrokerOrderId`와 `Quantity`(record). 기존 `ClientOrderId`와 같은 자리다.
  - trading에 두면 execution이 쓸 수 없다. execution → trading 참조는 ArchUnit으로 금지돼 있다. 나중에 BrokerPort와 이벤트로 넓힐 때를 대비한 위치다.
- **`BrokerOrderId`:** 빈 값과 공백 포함 값을 거부한다. `toString()`은 값만 돌려준다. 기존 로그 `{}` 출력 형식을 유지하기 위해서다.
- **`Quantity`:** 양수만 허용한다. 주문 수량과 체결 증분에만 쓴다. 누적 체결량(0 가능)에는 쓰지 않는다.
- **`OrderEntity`**
  - 생성자는 `Quantity`, `markSubmitted`는 `BrokerOrderId`, `applyFill`은 `Quantity`를 받는다.
  - `brokerOrderId` 필드는 `BrokerOrderIdConverter`(`@Convert`)로 기존 `varchar` 컬럼에 매핑한다. 스키마 변경은 없다.
  - `getQuantity()`와 `getFilledQuantity()`는 `long`을 유지한다. monitor 조회 화면(View DTO)으로 파급되지 않게 하기 위해서다.
- **trading 입구에서 감싼다.**
  - `OrderNotice.brokerOrderId()`: `OrderNoticeHandler.idOf`
  - `BrokerPort` 응답: `TradingService`, `ReconciliationService.keyOf`
  - `OrderRequest.quantity()`: `new Quantity(...)`
- **trading 내부 맵 키를 `BrokerOrderId`로 바꿨다.** 대상은 `TradingService.brokerOrderIdToRequest`, `OrderNoticeHandler.cumulativeNotional`, `PendingOrderNotices`다.
- **나가는 곳에서 푼다.** `BrokerPort.cancelOrder(...value())`와 `Fill` 이벤트(`.value()`)다.

### 4. 발견한 함정 (중요)

- **`Map.get(Object)`는 컴파일러가 잡지 못한다.** `ReconciliationService`의 `Map<String, BrokerOutstandingOrder>.get(order.getBrokerOrderId())`는 타입을 바꾼 뒤에도 컴파일된다. 하지만 항상 `null`을 반환해 대사가 조용히 망가진다. 맵 키를 `BrokerOrderId`로 바꿔 해결했다.
  - 조회를 일부러 옛 방식(문자열 키)으로 되돌리자 기존 `ReconciliationServiceTest` 2건이 실패했다. 기존 테스트가 이 함정을 잡는다는 것을 실행으로 확인했고, 코드는 원래대로 복구했다.
- **`assertEquals(String, Object)`도 컴파일된다.** `OrderEntityTest`의 `assertEquals("BROKER-1", order.getBrokerOrderId())`를 값 객체 비교로 고쳤다.
- 앞으로 타입을 넓힐 때도 `Map.get`, `equals`, `assertEquals`, `contains`처럼 `Object`를 받는 호출을 **Grep으로 전수 확인**해야 한다.

### 5. 버린 대안

- **StockCode 전면 도입(이벤트 포함):** 변경량이 매우 크고 JSON 호환 설계가 필요하다. 다음 조각에서 한다.
- **`OrderEntity` getter까지 값 객체로:** monitor 조회 경로로 파급된다. 읽기 모델은 원시 타입도 무방하다고 판단했다(ARCH 10절 View DTO).

### 6. 변경 파일

- 신규: `common/util/BrokerOrderId`, `common/util/Quantity`, `trading/BrokerOrderIdConverter`
- 신규 테스트: `common/.../OrderValueObjectsTest`(3, 경계값)
- 수정: `trading/OrderEntity`, `OrderRepository`, `TradingService`, `OrderNoticeHandler`, `PendingOrderNotices`, `ReconciliationService`, `StaleOrderCanceller`
- 테스트: 6개 파일, 호출부 약 50곳에 값 객체를 적용했다(스크립트로 컴파일 오류 줄만 변환).
- import 정렬: 수정 파일의 비-`java` import 블록을 정렬했다(`TradingService` 등 일부 기존 import 순서가 바뀜).

### 7. 롤백

- 커밋을 되돌린다. 스키마 변경은 없다(컨버터가 같은 `varchar`를 쓴다).

### 8. 검증 상태

- `.\gradlew.bat test` 전체 통과(2026-09-30). 결과:
  - `OrderEntityTest` 8
  - `OrderNoticeHandlerTest` 14
  - `TradingServiceTest` 9
  - `ReconciliationServiceTest` 10
  - `StaleOrderCancellerTest` 5
  - `OrderHistoryControllerTest` 4
  - `OrderValueObjectsTest` 3
  - `ArchitectureRulesTest` 8
  - `ModularityTests` 통과
- 대사 조회 함정의 뮤테이션 확인: 위 4절.
- **미검증**
  - 실제 PostgreSQL에서 `BrokerOrderIdConverter`와 `ddl-auto: validate`, `findByBrokerOrderId(BrokerOrderId)` 파생 쿼리 동작. 단위 테스트는 목 저장소다.
  - Testcontainers(P3) 결정 뒤 검증하거나, 재기동 시 기동 로그와 첫 주문·체결로 확인한다.

### 9. 다음 조각 후보

- `BrokerPort`(execution)의 `brokerOrderId`를 값 객체로 바꾸기
- `StockCode`(이벤트 포함, JSON 스칼라 직렬화)
- `Price`

## 조각 2 — BrokerPort(execution)까지 `BrokerOrderId` (2026-09-30)

### 1. 목적

조각 1에서는 문자열을 값 객체로 바꾸는 일을 trading 입구(`ReconciliationService.keyOf`, `TradingService`의 `new BrokerOrderId(result…)`)에서 했고, 나갈 때 `.value()`로 풀었다. 번역 책임을 **키움 어댑터 한 곳**(ACL, 규칙 §2.2)으로 옮겨 trading의 변환 코드를 없앤다.

### 2. 결정과 근거

- `execution/BrokerOrderResult.brokerOrderId`, `BrokerOutstandingOrder.brokerOrderId`, `BrokerPort.cancelOrder(BrokerOrderId, …)`를 값 객체로 바꿨다.
- `KiwoomBrokerAdapter`가 키움 문자열 `ord_no`를 감싼다. 요청에 넣을 때 `orig_ord_no`는 `.value()`로 푼다.
- **동작이 바뀌는 두 곳.** 예전처럼 문자열을 그대로 넘기면 `BrokerOrderId`의 빈 값 거부 때문에 예외가 나므로 명시적으로 정했다.
  1. 주문 응답의 `ord_no`가 빈 문자열이면 `null`일 때와 같이 `KiwoomApiException`을 던진다. TradingService가 UNKNOWN과 대사로 처리한다(기존 설계).
     - 예전에는 빈 주문번호로 SUBMITTED가 돼 체결 통보와 영영 매칭되지 않았다.
  2. 미체결 목록(`ka10075`, 필드명 실측 미확정 TODO)에서 `ord_no`가 없는 행은 WARN을 남기고 **그 행만 건너뛴다.**
     - 행 하나 때문에 예외가 나면 5분마다 도는 대사 전체가 매번 실패하기 때문이다.
     - 예전에는 빈 키가 어떤 주문과도 맞지 않아 결과적으로 무시됐다. 효과는 같고 로그가 추가됐다.
- trading에서 `keyOf`와 `.value()` 변환을 제거했다.

### 3. 변경 파일

- 수정
  - `execution/BrokerOrderResult`, `BrokerOutstandingOrder`, `BrokerPort`, `KiwoomBrokerAdapter`
  - `trading/ReconciliationService`, `TradingService`, `StaleOrderCanceller`
- 테스트
  - 신규 `execution/KiwoomBrokerAdapterTest`(3): 주문번호 번역, 빈 주문번호 응답은 결과 불명, 미체결 목록의 주문번호 없는 행 건너뛰기
  - 수정: `DailyReportSchedulerTest`(가짜 BrokerPort 시그니처), `OrderNoticeHandlerTest`, `TradingServiceTest`, `ReconciliationServiceTest`, `StaleOrderCancellerTest`(`anyString()` 매처 → `any()`)

### 4. 검증 상태

- `.\gradlew.bat test` 전체 통과(2026-09-30). `KiwoomBrokerAdapterTest` 3, `ArchitectureRulesTest` 8, `ModularityTests` 통과.
- `Object` 인자로 조용히 통과하는 호출은 Grep으로 전수 확인했다. 로그는 `toString()`이 값만 내보낸다.
- 새 테스트는 바뀐 API를 대상으로 해서 수정 전 코드로는 실행할 수 없었다(Red 미실행).
- **미검증:** 실제 키움 모의서버 주문·취소·미체결 조회. 재기동 후 첫 주문 로그로 확인한다.

## 조각 3 — `StockCode` 1단계: 타입과 JSON 스칼라 직렬화 (2026-09-30)

### 1. 진행 방식 (사용자 확정 2026-09-30: 단계적 도입)

ARCHITECTURE.md 3절의 "이벤트는 스키마 v2로 단계 도입, 한 번에 전면 교체하지 않는다"를 따른다.

1. 타입과 JSON 기반(이번 조각)
2. 모듈 입구에서 감싸 내부에 적용(trading, execution, risk 등)
3. 공통 이벤트는 마지막에 모듈별로 바꾼다.

### 2. 사전 실측

- 이벤트 JSON은 Spring `ObjectMapper` 빈으로 `event_store`에 **쓰기만** 한다(`audit/EventAuditListener`). 운영 코드에 저장된 JSON을 다시 읽는 곳은 없다(Grep).
- 규모
  - 종목코드가 `String`인 이벤트 11개: `Candle`, `DisclosureBlacklisted`, `DisclosureRisk`, `Fill`, `MarketTick`, `NewsSentiment`, `OrderNotice`, `OrderRequest`, `PositionRestored`, `Signal`, `SignalDecision`
  - 운영 관련 줄 약 120줄
  - 설정과 코드의 종목코드는 모두 6자리 숫자다. 키움 `A` 접두사는 `KiwoomBrokerAdapter.normalizeSymbol`이 벗긴다.

### 3. 결정과 근거

- `common/util/StockCode`(record): 형식 `[0-9A-Z]{6}`(대문자, 접두사 없음). `PATTERN` 상수가 형식 규칙의 단일 원천이다.
  - B2의 `DashboardController.TestSignalRequest`가 같은 정규식을 따로 들고 있었다. `@Pattern(regexp = StockCode.PATTERN)`으로 바꿨다(SSOT).
- `config/JacksonConfig`: `StockCode`와 `BrokerOrderId`는 문자열, `Quantity`는 숫자로 직렬화·역직렬화하는 `Module` 빈을 둔다.
  - 이벤트 필드를 값 객체로 바꿔도 `event_store` JSON 형식(`"symbol":"005930"`)이 유지된다.
  - Boot가 `Module` 빈을 `ObjectMapper`에 자동 등록한다.
  - 역직렬화할 때 형식이 틀리면 값 객체 생성자에서 거부된다.
- common에 Jackson 애너테이션(`@JsonValue` 등)을 쓰지 않았다. common은 순수 자바(`spring-modulith-api` compileOnly만)로 두는 기존 구성을 지키기 위해서다.

### 4. 변경 파일

- 신규: `common/util/StockCode`, `app/config/JacksonConfig`
- 수정: `monitor/DashboardController`(정규식을 `StockCode.PATTERN` 참조로)
- 신규 테스트
  - `common/.../StockCodeTest`(3): 경계값(자릿수, 소문자, `A` 접두사, 공백)
  - `app/config/ValueObjectJsonTest`(3): Boot `JacksonAutoConfiguration` 컨텍스트에서 스칼라 직렬화, 역직렬화, 형식 오류 거부

### 5. 검증 상태

- `.\gradlew.bat test` 전체 통과(2026-09-30). `ValueObjectJsonTest` 3, `StockCodeTest` 3, `RequestValidationTest` 8, `ModularityTests` 통과.
- Red 미실행: 모듈이 없으면 record가 `{"symbol":{"value":"005930"}}`로 직렬화돼 JSON 테스트가 실패할 것으로 **추론**한다.

### 6. 다음 단계

- 2단계: trading `OrderEntity.symbol`과 `BrokerPort`의 symbol에 적용(입구에서 감싸기)
- 이후: risk, strategy, market, monitor, 이벤트

## 조각 4 — `StockCode` 2단계: trading 주문 엔티티와 BrokerPort (2026-09-30)

### 1. 결정과 근거

- `OrderEntity.symbol`을 `StockCode`로 바꿨다. `StockCodeConverter`(`@Convert`)가 기존 `orders.symbol varchar`에 매핑하므로 스키마 변경은 없다.
  - 생성자는 `StockCode`를 받고 `getSymbol()`은 `StockCode`를 돌려준다.
  - monitor 주문 이력 View는 `.value()`로 문자열을 넣는다(조회 모델은 원시 유지, ARCH 10절).
- `BrokerPort.cancelOrder(BrokerOrderId, StockCode, long)`와 `BrokerOutstandingOrder.symbol`을 `StockCode`로 바꿨다. `KiwoomBrokerAdapter`가 `A` 접두사를 벗긴 뒤 감싼다.
- **동작 변경:** 미체결 목록에서 종목코드가 형식에 맞지 않는 행은 WARN을 남기고 그 행만 건너뛴다. 주문번호 없는 행과 같은 정책이며, 한 행 때문에 대사 전체가 실패하지 않게 하기 위해서다.
- trading 입구: `TradingService`가 `OrderRequest.symbol()`(이벤트, 아직 `String`)을 `new StockCode(...)`로 감싼다.
- 그대로 둔 것
  - `placeOrder(OrderRequest)`의 종목코드와 `Fill` 이벤트: 이벤트 단계에서 바꾼다.
  - `PositionRestorer`의 `PositionRestored` 이벤트
  - risk의 `DisclosureBlacklistEntity`, monitor의 `SignalDecisionEntity` 종목코드

### 2. 함정과 주의

- `StockCodeConverter`는 DB에 형식이 틀린 종목코드 행이 있으면 **읽을 때 예외**를 던진다. 조용히 넘기지 않는다(§2.1). 지금까지 저장된 종목코드는 모두 6자리 숫자라고 **추론**한다. 모의 운영 주문은 설정의 종목 5개로만 나갔다. 재기동 후 주문 이력 화면(`GET /api/orders`)이 열리는지로 확인한다.

### 3. 변경 파일

- 신규: `trading/StockCodeConverter`
- 수정
  - `trading/OrderEntity`, `TradingService`
  - `execution/BrokerPort`, `BrokerOutstandingOrder`, `KiwoomBrokerAdapter`
  - `monitor/OrderHistoryController`
- 테스트
  - `KiwoomBrokerAdapterTest` +1(형식 틀린 종목코드 행 건너뛰기)과 기존 테스트에 `A` 접두사 제거 단언 추가
  - 7개 파일의 `OrderEntity`·`BrokerOutstandingOrder` 생성 리터럴과 가짜 BrokerPort 시그니처를 스크립트로 변환했다.

### 4. 검증 상태

- `.\gradlew.bat test` 전체 통과(2026-09-30). `KiwoomBrokerAdapterTest` 4, trading 테스트 전부, `ArchitectureRulesTest` 8, `ModularityTests` 통과.
- `Object` 인자로 조용히 통과하는 `getSymbol()` 비교는 없음을 Grep으로 확인했다.
- **미검증:** 실제 DB에서 `StockCodeConverter` 동작, 재기동 후 주문 이력 조회.

## 조각 5 — `StockCode` 3단계: 첫 이벤트 `OrderRequest.symbol` (2026-09-30)

### 1. 결정과 근거

- 모듈 내부(risk, monitor)보다 **이벤트를 하나씩** 바꾸기로 했다.
  - 모듈 내부는 대부분 이벤트로 받은 종목코드 문자열을 맵 키로 쓴다.
  - 내부부터 바꾸면 리스너마다 감싸기 코드와 `Map.get(Object)` 함정이 모듈마다 생긴다.
- 첫 대상은 주문 경로의 `OrderRequest`다.
  - 생산자: `RiskGate`(Signal의 문자열을 감쌈), `BacktestRunner`, `InverseSwitchWalkForwardRunner`(Candle의 문자열을 감쌈)
  - 소비자: `TradingService`(조각 4의 감싸기 제거), `KiwoomBrokerAdapter.placeOrder`(`.value()`), `BacktestExecutionHandler`(Fill은 아직 문자열이라 `.value()`), monitor 리스너(`%s` 포맷은 `toString()`이 값이라 변경 없음)
- **JSON은 그대로다.** `config/JacksonConfig` 덕분에 `event_store`에는 여전히 `"symbol":"005930"`이 저장된다. 이벤트 계약 버전은 v1로 유지하고 Javadoc에 적었다. `ValueObjectJsonTest`에 실제 `OrderRequest` 직렬화 테스트를 추가해 고정했다.
- `RiskGate`: 바로 위의 `ClientOrderId` 생성이 이미 6자리 형식을 검증하므로, `StockCode` 감싸기로 새로 생기는 실패 경로는 없다.

### 2. 함정과 주의

- **백테스트 픽스처.** 백테스트 러너가 캔들 종목코드로 주문 요청을 만들므로, 테스트의 가짜 종목명 `"TEST"`가 `StockCode` 검증에서 실패했다.
  - 대상: `BacktestRunnerTest`, `WalkForwardRunnerTest`, `VolatilityBreakoutStrategyTest`
  - 픽스처 값만 `"TEST01"`로 바꿨다. 다른 `"TEST"`, `"IDX"` 캔들 테스트는 주문 경로를 타지 않아 그대로 뒀다(전체 테스트로 확인).
  - 실제 백테스트 데이터(`data/`)의 종목은 6자리 코드다(**추론**, 파일은 gitignore라 확인하지 않음). 형식이 다른 종목으로 백테스트하면 주문 생성 시점에 실패한다.
- 작업 중 실수: `BacktestRunnerTest`의 식 중간에 넣은 `//` 주석이 인자를 주석 처리해 컴파일이 깨졌다. 바로 되돌렸다.

### 3. 변경 파일

- 수정
  - `common/event/OrderRequest`
  - `risk/RiskGate`
  - `backtest/BacktestRunner`, `InverseSwitchWalkForwardRunner`, `BacktestExecutionHandler`
  - `trading/TradingService`
  - `execution/KiwoomBrokerAdapter`
- 테스트
  - `OrderRequest` 생성 5곳: `KiwoomBrokerAdapterTest`, `SlippageTrackerTest`, `TradeNotificationListenerTest`, `OrderNoticeHandlerTest`, `TradingServiceTest`
  - 백테스트 픽스처 3개 파일
  - `ValueObjectJsonTest` +1

### 4. 검증 상태

- `.\gradlew.bat test` 전체 통과(2026-09-30). 결과:
  - 백테스트 테스트
  - `RiskGateTest` 11
  - `TradingServiceTest` 9
  - `ValueObjectJsonTest` 4
  - `ArchitectureRulesTest` 8
  - `ModularityTests` 통과
- 운영 코드는 Edit 도구로만 수정했다(셸 스크립트 없음).
- **미검증:** 재기동 후 `event_store`의 `OrderRequest` payload가 예전과 같은지(DB 조회로 확인 가능).

### 5. 다음 이벤트 후보

- `Fill`: trading, monitor, portfolio로 이어진다.
- `Signal`: strategy에서 risk로 간다.
- `OrderNotice`: market에서 trading으로 간다.
- `MarketTick`, `Candle`: 백테스트 영향이 크다.

## 조각 6 — `StockCode`: `Fill` 이벤트 (2026-09-30)

### 1. 결정과 근거

- `Fill.symbol`을 `StockCode`로 바꿨다. JSON 형식은 그대로(`JacksonConfig`)이므로 스키마 v1을 유지한다.
- 생산자
  - `TradingService` SIM: `request.symbol()`을 그대로 넘긴다.
  - `BacktestExecutionHandler`: 그대로 넘긴다.
  - `OrderNoticeHandler`: **WS 통보의 `notice.symbol()` 대신 DB 주문 엔티티의 `order.getSymbol()`을 쓴다.** WS 파서는 FID 9001이 비면 빈 문자열을 넘기므로, 감싸면 예외가 나 체결이 유실될 수 있다. 같은 주문의 종목코드이고 DB 값이 더 신뢰할 수 있다.
- **소비자 맵 키는 문자열로 유지했다(중요).**
  - `portfolio/PositionBook.positions`와 `risk/DailyPnlTracker.lots`는 종목코드 문자열을 키로 쓴다.
  - `PositionBook`은 `RiskGate`(Signal 문자열)와 포지션 복원(`PositionRestored` 문자열)도 조회한다.
  - 키를 `StockCode`로 바꾸면 문자열 조회가 `Map.get(Object)`라서 **컴파일은 되지만 항상 못 찾는다.** 포지션이 없는 것처럼 보이는 사고다.
  - 그래서 소비자 경계에서 `fill.symbol().value()`로 꺼낸다. 맵 키 전환은 그 맵을 쓰는 이벤트(Signal, PositionRestored)가 모두 바뀐 뒤에 모듈 단위로 한다.
- `SlippageTracker.maxBpsSymbol`(String 필드)도 `.value()`를 쓴다. 로그와 `%s` 포맷은 `toString()`이 값이라 그대로다.

### 2. 함정과 주의

- `RiskGateTest.동시_보유_한도_도달시_신규_매수_차단`이 가짜 종목명 `"A"`~`"E"`를 써서 실패했다. 픽스처만 `"000001"`~`"000005"`로 바꿨다(보유 5종목으로 한도에 닿는다는 검증 의미는 같다).
- `OrderNoticeHandlerTest`의 `assertEquals("005930", fill.symbol())`은 컴파일은 되지만 틀린 비교가 되므로 `new StockCode(...)` 비교로 고쳤다.
- 맵 키 함정은 기존 테스트가 지킨다.
  - `PositionBookTest`: Fill 반영 뒤 문자열로 조회한다.
  - `DailyPnlTrackerTest`: 매도 시 `get` 경로를 탄다.

### 3. 변경 파일

- 수정
  - `common/event/Fill`
  - `trading/OrderNoticeHandler`, `TradingService`
  - `backtest/BacktestExecutionHandler`
  - `portfolio/PositionBook`
  - `risk/DailyPnlTracker`
  - `monitor/SlippageTracker`
- 테스트: `new Fill(...)` 15곳(10개 파일, 스크립트 1회), `OrderNoticeHandlerTest` 단언, `RiskGateTest` 픽스처

### 4. 검증 상태

- `.\gradlew.bat test` 전체 통과(2026-09-30). `PositionBookTest` 3, `DailyPnlTrackerTest` 5, `RiskGateTest` 11, `ArchitectureRulesTest` 8, `ModularityTests` 통과.
- **미검증:** 실제 체결 통보로 포지션과 손익이 반영되는지. 재기동 후 첫 체결로 확인한다.

## 조각 7 — `StockCode`: `Signal` 이벤트 (2026-09-30)

### 1. 결정

- `common/event/Signal.symbol`을 `StockCode`로 바꿨다. JSON은 기존처럼 문자열이다(JacksonConfig). 스키마 v2(fixedQuantity 추가)는 그대로다.
- 발행처가 감싼다.
  - `DashboardController` 수동 시그널: `new StockCode(request.symbol())`. 요청은 이미 `@Pattern(StockCode.PATTERN)`으로 걸러지므로 여기서 예외가 날 일은 없다.
  - `C3LiveStrategy.publishBuy/publishSell`: `new StockCode(symbol)`. 전략 내부 맵(`lastDecisionDate` 등)은 문자열 그대로다.
- `RiskGate`의 `new StockCode(signal.symbol())` 감싸기가 사라졌다. OrderRequest에 그대로 넘긴다.
- 문자열 경계는 `.value()`로 꺼낸다.
  - `PositionBook.holds/get`(맵 키가 아직 문자열 — 조각 6 참고)
  - `DisclosureBlacklist.isBlacklisted`
  - `ClientOrderId.generate`
  - `SignalDecision.symbol`(아직 문자열 이벤트)

### 2. 함정과 주의

- `PositionBook.holds/get`은 `String` 파라미터라 `.value()`를 빠뜨리면 컴파일 오류가 난다(Map.get(Object) 함정 아님).
- `C3LiveStrategyTest`의 `assertEquals("000660", signal.symbol())`은 컴파일은 되지만 항상 실패하는 비교라 `new StockCode(...)` 비교로 고쳤다. `SignalDecision.symbol()` 단언은 문자열이라 그대로 둔다.

### 3. 변경 파일

- 수정: `common/event/Signal`, `risk/RiskGate`, `monitor/DashboardController`, `strategy/C3LiveStrategy`
- 테스트: `RiskGateTest`, `RiskGateMacroTest`, `C3LiveStrategyTest`, `ValueObjectJsonTest`(Signal JSON 문자열 유지와 과거 v1 JSON 읽기 추가)

### 4. 검증 상태

- `.\gradlew.bat test` 전체 통과(2026-09-30). `ValueObjectJsonTest` 5.
- **미검증:** 실제 event_store에 쌓인 Signal JSON 형식. 재기동 후 C3 판단일이나 대시보드 테스트 시그널로 확인한다.

## 조각 8 — `StockCode`: `PositionRestored` 이벤트 (2026-09-30)

### 1. 결정

- `common/event/PositionRestored.symbol`을 `StockCode`로 바꿨다. JSON은 문자열 그대로다.
- `execution/PositionRestorer`: 잔고 원소의 종목코드가 형식(`StockCode.PATTERN`)에 맞지 않으면 기존 "해석 실패" WARN 경로로 건너뛴다. 빈 값만 건너뛰던 것을 넓혔다 — 한 원소 때문에 예외가 나 나머지 종목 복원까지 멈추지 않게 한다(`KiwoomBrokerAdapter` 미체결 행과 같은 처리).
- `portfolio/PositionBook.onPositionRestored`: 맵 키가 아직 문자열이라 `.value()`로 꺼낸다.

### 2. 맵 키 전환 준비 상태

- `PositionBook`에 들어오는 이벤트(Fill, PositionRestored)와 조회 신호(Signal)가 모두 `StockCode`가 됐다.
- 남은 문자열 경계: `get(String)`, `holds(String)`, `snapshot()`의 `Map<String, Position>`. 호출부는 `RiskGate`, `C3LiveStrategy`, `DashboardFacade`, `DailyReportScheduler`, `TelegramCommandPoller`. `DailyPnlTracker.lots`는 별개 맵이라 함께 전환한다.
- 키 전환 시 `get/holds` 파라미터를 `StockCode`로 바꾸면 문자열 호출부가 컴파일 오류로 드러난다. 반대로 파라미터를 `Object`류로 두면 `Map.get(Object)` 함정이 생긴다.

### 3. 변경 파일

- 수정: `common/event/PositionRestored`, `execution/PositionRestorer`, `portfolio/PositionBook`(주석 포함)
- 테스트 신규: `PositionRestorerTest` 3(A 접두 제거·형식 오류 원소 건너뜀·SIM 미조회)
- 테스트 추가: `PositionBookTest` 복원 시드와 체결 포지션 우선(putIfAbsent)

### 4. 검증 상태

- `.\gradlew.bat test` 전체 통과(2026-09-30).
- **미검증:** LIVE 기동 시 실제 잔고로 복원되는지. 재기동 로그의 "포지션 복원(브로커 잔고)"로 확인한다.

## 조각 9 — 장부 맵 키 `StockCode` 전환 (2026-09-30)

### 1. 결정

- `portfolio/PositionBook.positions`와 `risk/DailyPnlTracker.lots`의 키를 `StockCode`로 바꿨다. 들어오는 이벤트(Fill, PositionRestored)와 조회 신호(Signal)가 모두 `StockCode`가 된 뒤라 `.value()` 꺼내기가 사라졌다.
- 공개 API: `get(StockCode)`, `holds(StockCode)`, `Map<StockCode, Position> snapshot()`.
  - 파라미터 타입을 바꿨으므로 문자열 호출부는 **컴파일 오류**로 모두 드러났다(RiskGate 3, C3LiveStrategy 2, PositionBookTest 7).
- 호출부
  - `RiskGate`: `signal.symbol()`을 그대로 넘긴다.
  - `C3LiveStrategy`: 설정의 문자열 종목 목록을 `new StockCode(symbol)`로 감싸 조회한다. 전략 내부 맵은 문자열 그대로다.
  - `DashboardFacade`: `PositionView(String symbol)`에 `e.getKey().value()`. 응답 JSON은 그대로다.
  - `DailyReportScheduler`, `TelegramCommandPoller`: `snapshot().forEach`로 `StringBuilder.append(Object)` — `toString()`이 값이라 출력 텍스트가 같다. 코드 변경 없음.

### 2. 함정 점검(Map.get(Object))

- `snapshot()` 반환 맵을 문자열로 `get`하는 곳이 있으면 조용히 null이 된다. main·test를 grep해 `snapshot()` 사용처 3곳 모두 순회(forEach·entrySet)만 함을 확인했다.
- `DailyPnlTracker.recordSell`의 `get/remove/put`은 `StockCode` 지역변수로 바꿨다. `DailyPnlTrackerTest`의 매도 손익 테스트가 이 경로를 지킨다.
- 출력 검증: `DailyReportSchedulerTest`가 리포트에 "005930"이 찍히는지 확인한다. 텔레그램 `/status`는 같은 방식이지만 종목 표기 단언은 없다.

### 3. 변경 파일

- 수정: `portfolio/PositionBook`, `risk/DailyPnlTracker`, `risk/RiskGate`, `strategy/C3LiveStrategy`, `monitor/DashboardFacade`
- 테스트: `PositionBookTest` 조회 인자

### 4. 검증 상태

- `.\gradlew.bat test` 전체 통과(2026-09-30).
- **미검증:** 재기동 후 대시보드 포지션 목록, 텔레그램 `/status`, 일일 리포트의 종목 표기.

## 조각 10 — `StockCode`: `SignalDecision` 이벤트 (2026-09-30)

### 1. 결정

- `common/event/SignalDecision.symbol`을 `StockCode`로 바꿨다. JSON(event_store)은 문자열 그대로다.
- 발행처: `RiskGate.publishRejected`는 `signal.symbol()`을 그대로, `C3LiveStrategy.publishDecision`은 `new StockCode(symbol)`.
- **DB 경계는 문자열 유지:** `SignalDecisionEntity.symbol`(String)과 `DecisionController` 응답은 바꾸지 않았다. `SignalDecisionListener`가 저장할 때 `.value()`로 꺼낸다. 엔티티까지 `StockCodeConverter`로 바꾸는 것은 조회 화면 외에 이득이 없어 보류한다.

### 2. 함정 점검

- `C3LiveStrategyTest`의 `assertEquals("005930", decision.symbol())` 2곳을 `StockCode` 비교로 고쳤다(컴파일은 되지만 항상 실패하는 비교).
- `DecisionControllerTest`의 `view.symbol()`은 엔티티 기반 문자열이라 그대로다.

### 3. 변경 파일

- 수정: `common/event/SignalDecision`, `monitor/SignalDecisionListener`, `risk/RiskGate`, `strategy/C3LiveStrategy`
- 테스트: `SignalDecisionListenerTest` 생성부 3, `C3LiveStrategyTest` 단언 2

### 4. 검증 상태

- `.\gradlew.bat test` 전체 통과(2026-09-30).
- **미검증:** 재기동 후 판단 기록 화면(FE-6)과 signal_decision 테이블의 종목코드.

## 조각 11 — `OrderNotice`: 주문번호·종목코드 번역을 파서(ACL)로 (2026-09-30)

### 1. 조사

- `OrderNotice.symbol`을 읽는 소비자가 없다. `OrderNoticeHandler`는 Fill을 만들 때 DB 주문의 종목코드를 쓴다(조각 6).
- `OrderNotice.brokerOrderId`는 핸들러와 보류함(`PendingOrderNotices`)이 받자마자 `new BrokerOrderId(...)`로 감싸고 있었다. 번역이 소비자 쪽에 흩어져 있었다.
- event_store(`EventAuditListener`)는 OrderNotice를 저장하지 않는다. JSON 형식 영향이 없다.

### 2. 결정

- `brokerOrderId`를 `BrokerOrderId`로, `symbol`을 `StockCode`로 바꿨다.
- 번역은 `market/RealMessageParser`(키움 WS의 ACL)가 한다.
  - 주문번호: 앞뒤 공백은 벗긴다. 비면 기존처럼 버리고, 가운데 공백이 있으면 WARN 후 버린다(어느 주문인지 알 수 없다).
  - 종목코드: 비거나 형식이 틀리면 **null**로 두고 통보는 살린다. 체결 통보를 잃으면 포지션·손익이 틀어지므로 파서에서 예외를 던지지 않는다. 필드 Javadoc에 null 가능을 적었다.
- `OrderNoticeHandler.idOf()`와 보류함의 감싸기가 사라졌다. Fill의 주문번호(아직 String)에는 `.value()`로 넘긴다.

### 3. 동작 변화

- 이전: 주문번호에 앞뒤 공백이 있으면 핸들러에서 `BrokerOrderId` 생성 예외가 났다. 이제 파서가 벗겨 정상 처리한다.
- 이전: 빈 종목코드는 빈 문자열로 전달됐다. 이제 null이다(소비자 없음).

### 4. 변경 파일

- 수정: `common/event/OrderNotice`, `market/RealMessageParser`(로거 추가), `trading/OrderNoticeHandler`, `trading/PendingOrderNotices`
- 테스트: `RealMessageParserTest` 단언 8곳 값 객체 비교로(문자열 비교는 컴파일되지만 항상 실패), 신규 3(주문번호 형식 오류 버림, 앞뒤 공백 제거, 종목코드 빈 값·형식 오류 시 null로 살림). `OrderNoticeHandlerTest` 생성부 5.

### 5. 검증 상태

- `.\gradlew.bat test` 전체 통과(2026-09-30). `RealMessageParserTest` 14, `OrderNoticeHandlerTest` 14.
- **미검증:** 실제 WS 체결 통보로 Fill이 나는지. 재기동 후 첫 체결로 확인한다.

## 조각 12 — `BrokerOrderId`: `Fill` 이벤트 (2026-09-30)

### 1. 결정

- `common/event/Fill.brokerOrderId`를 `BrokerOrderId`로 바꿨다. JSON은 문자열 그대로다(`ValueObjectJsonTest`에 Fill 직렬화 단언 추가).
- 발행처 3곳
  - LIVE `OrderNoticeHandler`: `notice.brokerOrderId()`를 그대로(조각 11 이후 이미 값 객체).
  - SIM `TradingService`: 이미 만든 `BrokerOrderId`를 그대로.
  - 백테스트 `BacktestExecutionHandler`: 고정 표식 `new BrokerOrderId("BACKTEST")` — 공백이 없어 형식 규칙을 통과한다.
- 소비자: `EventFeed`가 `%s`로만 쓴다(`toString()`이 값). 다른 소비자는 주문번호를 읽지 않는다.

### 2. 변경 파일

- 수정: `common/event/Fill`, `trading/OrderNoticeHandler`, `trading/TradingService`, `backtest/BacktestExecutionHandler`
- 테스트: `new Fill(...)` 15곳(스크립트 1회), `OrderNoticeHandlerTest` 단언 1(문자열 비교는 컴파일되지만 항상 실패), `ValueObjectJsonTest` 1 추가

### 3. 검증 상태

- `.\gradlew.bat test` 전체 통과(2026-09-30). `ValueObjectJsonTest` 6.
- **미검증:** 실제 event_store의 Fill JSON 형식.

## 조각 13 — `StockCode`: `MarketTick` 이벤트 (2026-09-30)

### 1. 조사와 범위 결정

- `MarketTick`: 생성 1곳(`RealMessageParser`), 소비 2곳(`EventAuditListener` 감사 저장, `StrategyEngine` trace 로그). 작다.
- `Candle`: main 9개 파일(백테스트 러너·CSV 로더·시세 어댑터·C3). 실데이터 실험 테스트 10개가 `candles.get(0).symbol()`을 문자열 맵 키·`equals`로 쓰고, 그중 일부는 데이터가 없으면 건너뛰어 `Map.get(Object)`·`equals` 함정이 CI에서 잡히지 않는다. 테스트 가짜 종목("TEST", "IDX")도 바꿔야 한다.
- **사용자 결정(2026-09-30): MarketTick만 진행, Candle 보류.** Candle은 백테스트 전용 성격이 강해 이득 대비 위험이 크다.

### 2. 결정

- `MarketTick.symbol`을 `StockCode`로 바꿨다. event_store JSON은 문자열 그대로다.
- 번역은 `RealMessageParser.parseTick`(ACL)이 한다. 종목코드 앞뒤 공백을 벗기고, 형식이 틀리면 버린다.
  - 로그는 **DEBUG**다. 시세는 초당 여러 건이라 WARN이면 로그가 넘친다. 소비자가 감사 저장·trace뿐이라 버려도 매매 영향이 없다(체결 통보와 다른 판단 — 조각 11).
- 실측 근거: `docs/measured` 전문의 0B item 329건이 모두 6자리 숫자("005930")다. 거래소 접미사("_AL" 등)는 관측되지 않았고, 구독도 접미사 없이 한다.

### 3. 변경 파일

- 수정: `common/event/MarketTick`, `market/RealMessageParser`
- 테스트: `RealMessageParserTest` 단언 2 값 객체 비교로, 신규 1(빈 값·4자리·"_AL" 접미 → null)

### 4. 검증 상태

- `.\gradlew.bat test` 전체 통과(2026-09-30). `RealMessageParserTest` 15.
- **미검증:** 장중 WS 시세 수신과 event_store의 MarketTick JSON.

## 조각 14 — `Quantity`: `OrderRequest.quantity`·`Fill.filledQuantity` (2026-09-30)

### 1. 사전 조사 — 0 수량 경로

`Quantity`는 양수만 허용하므로, 이벤트를 만드는 모든 곳이 0 이하를 먼저 거르는지 확인했다.

- `RiskGate`: 사이징 결과 `quantity <= 0`이면 발행 전에 반환.
- `OrderNoticeHandler`: 증분 `delta <= 0`이면 중복·역순 통보로 보고 반환.
- `TradingService`(SIM): 요청 수량 그대로(이미 양수).
- 백테스트 `BacktestRunner`·`InverseSwitchWalkForwardRunner`: 매수는 `qty <= 0`이면 반환, 매도는 보유 수량 `> 0` 조건 안에서만 호출.

### 2. 결정

- 두 필드를 `Quantity`로 바꿨다. JSON은 **숫자** 그대로다(JacksonConfig — `ValueObjectJsonTest`에 단언 추가).
- 원장 산술(`PositionBook`, `DailyPnlTracker`, `SlippageTracker`, 백테스트 러너)은 `.value()`로 꺼낸다. 누적·부호가 섞이는 계산이라 `Quantity`에 산술 메서드를 넣지 않았다(0·음수가 중간값으로 나온다).
- 생성처는 `new Quantity(...)`로 감싼다. `TradingService`의 `new Quantity(request.quantity())` 3곳은 사라졌다.

### 3. 함정과 대응

- **`%d` 포맷:** `String.formatted("%d", quantity)`는 컴파일되지만 실행 중 `IllegalFormatConversionException`이 난다. `EventFeed`(테스트 없음)와 `TradeNotificationListener`에 `.value()`를 넣었다. `EventFeedTest`를 새로 만들었고, `.value()`를 빼는 변이로 테스트가 이 예외로 실패함을 확인했다.
- **`assertEquals(14, order.quantity())`:** `assertEquals(Object, Object)`로 컴파일되어 항상 실패한다. 실행 결과 13건 실패로 드러났고 14곳을 `new Quantity(n)` 비교로 고쳤다(RiskGateTest 5, RiskGateMacroTest 1, OrderNoticeHandlerTest 7, TradingServiceTest 1).
- 나머지 `assertEquals(n, x.quantity())`는 원시 long 필드(`OrderNotice.filledQuantity`, `PositionBook.Position`, `PositionView`, 주문 이력 뷰)라 그대로다. 건너뛰는 실험 테스트에는 이 두 이벤트 수량 단언이 없음을 grep으로 확인했다.
- 로그 `{}`, `String.valueOf`는 `toString()`이 숫자라 결과가 같다. `KiwoomBrokerAdapter`의 `ord_qty`는 뜻을 분명히 하려고 `.value()`를 썼다.

### 4. 변경 파일

- 수정(main 13): `common/event/OrderRequest`, `Fill`, `risk/RiskGate`, `DailyPnlTracker`, `trading/TradingService`, `OrderNoticeHandler`, `portfolio/PositionBook`, `monitor/EventFeed`, `TradeNotificationListener`, `SlippageTracker`, `execution/KiwoomBrokerAdapter`, `backtest/BacktestRunner`, `InverseSwitchWalkForwardRunner`
- 테스트: 생성자 인자 22곳(파서 스크립트 20 + 수동 2), 단언 14곳, 신규 `EventFeedTest` 2

### 5. 검증 상태

- `.\gradlew.bat test` 전체 통과(2026-09-30).
- **미검증:** 실제 주문 전송(`ord_qty`), 대시보드 이벤트 피드·텔레그램 알림 문구, event_store의 수량 JSON.

## 조각 15 — `Price` 1단계: 타입과 JSON (2026-09-30)

### 1. 결정과 근거

`StockCode`와 같은 단계적 도입(조각 3)을 따른다. 이번 조각은 타입과 JSON 기반만 두고, 이벤트·엔티티에는 아직 쓰지 않는다.

- `common/util/Price`(record, `BigDecimal value`): 양수만 허용한다. 0·음수·null은 만들 수 없다.
  - 가격을 모르는 경우는 값 객체가 아니라 호출부의 `null`로 표현한다(규칙: 단일 값의 없음은 null).
  - 평균단가처럼 원 아래 소수가 생길 수 있어 정수로 제한하지 않는다. 호가 단위 정렬은 `risk.KrxTickSize`의 일이다.
- **같음은 수치로 판단한다.** `BigDecimal.equals`는 자릿수(scale)까지 비교해 `259000`과 `259000.00`을 다르게 본다. `equals`는 `compareTo`, `hashCode`는 `stripTrailingZeros()` 기준으로 바꿨다. 값은 받은 그대로 보관한다.
- `toString()`은 `toPlainString()`이다. 지수 표기(`2.59E+5`)가 로그·주문 전문에 나가지 않게 한다.
- `config/JacksonConfig`: 숫자로 직렬화하고 받은 값의 자릿수를 그대로 쓴다. 이벤트 필드를 `BigDecimal`에서 `Price`로 바꿔도 `event_store` JSON(`"price":258000.00`)이 같다. 0은 읽을 때 생성자가 거부한다.

### 2. 변경 파일

- 신규: `common/util/Price`
- 수정: `app/config/JacksonConfig`
- 테스트: 신규 `common/.../PriceTest`(3: 경계값, 수치 기준 같음, 평문 문자열), `ValueObjectJsonTest` +2(BigDecimal 필드와 같은 JSON — 자릿수 포함 3값, 0 거부)

### 3. 검증 상태

- `.\gradlew.bat test` 전체 통과(2026-09-30). `PriceTest` 3, `ValueObjectJsonTest` 8.

### 4. 다음 단계 — 이벤트별 0·null 경로 조사가 먼저다

`Quantity`(조각 14)처럼 이벤트를 만드는 모든 곳이 0 이하·null을 어떻게 다루는지 먼저 확인해야 한다. 코드만 읽고 확인한 것:

| 이벤트 필드 | 0·null 가능성 | 비고 |
|---|---|---|
| `OrderRequest.limitPrice` | null 가능(`KrxTickSize.align`이 null·0 이하를 그대로 반환) | **조각 16에서 처리** — `RiskGate`가 거부 |
| `Signal.refPrice` | 생성처(C3, StrategyEngine, 대시보드 테스트 신호)별 확인 필요 | `RiskGate`가 `limitPrice`로 넘긴다 |
| `OrderNotice.fillPrice` | 빈 값이면 null(`RealMessageParser`). 실측(조각 17): 접수 통보는 빈 값, 체결은 양수 | **조각 18에서 처리** — 파서가 빈 값·0·형식 오류를 null로 |
| `Fill.fillPrice` | LIVE는 910이 비면 null·0이 나갈 수 있었다 | **조각 17에서 처리** — 지정가 근사 |
| `PositionRestored.avgPrice` | 매입가가 없으면 0을 지어냈다(`PositionRestorer.firstPrice`) | **조각 19에서 처리** — null(평단 미상) |
| `MarketTick.price` | 빈 값은 파서가 버림 | **조각 18에서 처리** — 0도 버림 |
| `Candle` OHLC | — | 종목코드와 함께 **보류**(조각 13 사용자 결정) |

## 조각 16 — `Price`: `OrderRequest.limitPrice` (2026-09-30)

### 1. 사전 조사 — 0·null 경로

- 모든 주문은 지정가다(`KiwoomBrokerAdapter`의 `trde_tp=0`). 가격 없는 주문은 낼 수 없다.
- 운영에서 `OrderRequest`를 만드는 곳은 `RiskGate` 하나다. 지정가는 `KrxTickSize.align(signal.refPrice())`이고, null·0 이하는 그대로 돌려준다.
- 매수는 `PositionSizer.sizeBuy`가 가격 null·0 이하면 0주를 돌려줘 이미 걸러진다.
- **매도는 가격 검사가 없었다.** C3 국면 OFF 강제 청산(`liquidateAll`)은 평균단가를 기준가로 쓰고, 잔고 복원(`PositionRestorer.firstPrice`)은 매입가를 못 찾으면 **0**을 넣는다. 이 경우 지금까지는 0원 지정가 주문이 브로커까지 나가 거부됐다(일 주문 슬롯 1개 소비, 주문 REJECTED).
- 백테스트 두 러너(`BacktestRunner`, `InverseSwitchWalkForwardRunner`)도 `candle.open()`으로 만든다. 기록용이고 체결가는 `referencePrice`로 따로 정한다. 레포 `data/` 단일 종목 CSV 9개와 `aiDoc/market-data` 사본 5개에 시가 0 이하 행은 없다(실측).

### 2. 결정

- `OrderRequest.limitPrice`를 `Price`로 바꿨다. JSON은 숫자 그대로다(`ValueObjectJsonTest`에 `"limitPrice":258000` 단언 추가).
- **`RiskGate`: 지정가를 정할 수 없으면 거부한다.** 호가단위 정렬 결과가 null·0 이하면 WARN과 REJECTED 판단 기록(`기준가 없음 — 지정가를 정할 수 없어 거부`)을 남기고 끝낸다.
  - 위치는 사이징 뒤, **일 주문 슬롯 앞**이다. 브로커가 거부할 주문에 슬롯을 쓰지 않는다.
  - 그대로 `new Price(0)`을 부르면 이벤트 리스너 안에서 예외가 나 C3 청산 루프의 다른 종목까지 멈출 수 있어서, 예외 대신 거부로 처리했다.
- 엔티티는 조각 1과 같이 **생성자만 값 객체**다. `OrderEntity(…, Price limitPrice, …)`, 컬럼과 `getLimitPrice()`는 `BigDecimal` 그대로다(조회 화면 파급 없음, 스키마 변경 없음).
- 나가는 곳에서 푼다: `KiwoomBrokerAdapter` `ord_uv`(`.value().toPlainString()`), SIM 체결가(`Fill.fillPrice`는 아직 `BigDecimal`), `SlippageTracker` 결정가.
- `SlippageTracker`의 null·0 이하 검사는 지웠다. `Price`가 양수를 보장한다.
- 백테스트는 `new Price(candle.open())`. 시가 0인 데이터가 들어오면 이제 조용히 0원 주문을 기록하지 않고 예외로 드러난다.

### 3. 함정 점검

- `limitPrice()`를 `Object`로 받는 호출(`equals`, `Map.get`, `assertEquals`, `%d`)을 grep으로 전수 확인했다. 로그 `{}`와 `%s`(EventFeed, 텔레그램 문구)는 `toString()`이 평문 숫자라 결과가 같다. 테스트 쪽 `limitPrice()` 단언은 조회 뷰(`OrderHistoryItemView`, BigDecimal)뿐이다.

### 4. 동작 변화

- 기준가 0·null인 매도 신호: 이전에는 0원 주문 → 브로커 거부(슬롯 소비). 이제 `RiskGate`에서 거부(슬롯 미소비, 판단 기록 REJECTED).

### 5. 발견했지만 바꾸지 않은 것 (사용자 판단 필요)

- **C3 국면 OFF 강제 청산의 지정가가 평균매입가다**(`C3LiveStrategy.liquidateAll` → `publishSell(symbol, position.avgPrice())`). 현재가가 매입가보다 낮으면 매도 지정가가 시장가보다 높아 체결되지 않을 수 있고, 미체결 주문은 `StaleOrderCanceller`가 취소한다. 즉 손실 구간의 강제 청산이 실제로는 안 될 수 있다(**추론**, 실측 없음). 판단일 매도(`decideOne`)는 최근 종가를 쓴다. 전략 동작 변경이라 이번 조각에서 건드리지 않았다.

### 6. 변경 파일

- 수정(main 8): `common/event/OrderRequest`, `risk/RiskGate`, `trading/OrderEntity`, `TradingService`, `execution/KiwoomBrokerAdapter`, `monitor/SlippageTracker`, `backtest/BacktestRunner`, `InverseSwitchWalkForwardRunner`
- 테스트: 생성자 인자 23곳(컴파일 오류 줄만 스크립트 변환), `RiskGateTest` 신규 2(0·null 매도 거부와 슬롯 미소비, 호가 보정 지정가), `ValueObjectJsonTest` 단언 1

### 7. 검증 상태

- `.\gradlew.bat test` 전체 통과(2026-09-30, 550건, 건너뜀 16). `RiskGateTest` 13.
- **미검증:** 실제 주문 전문의 `ord_uv`, event_store의 OrderRequest JSON, 기준가 0 거부 경로의 운영 발생 여부.

## 조각 17 — `Price`: `Fill.fillPrice` (2026-09-30)

### 1. 사전 조사 — 0·null 경로

- 발행처 3곳
  - LIVE `OrderNoticeHandler`: 증분 단가를 FID 910(누적 평균가)에서 역산한다. 910이 비었거나 0이면 null·0을 **그대로** 실었다.
  - SIM `TradingService`: 지정가 그대로(조각 16 이후 이미 `Price`).
  - 백테스트 `BacktestExecutionHandler`: 체결 기준가에 슬리피지를 반영한 값. 매수는 `affordableQuantity`가 0 이하를 거른다.
- 실측(`docs/measured/ws_probe_20260911_intraday.txt`, 00 통보 4건): 접수는 910·911이 빈 값이고(체결량 0 → Fill 없음), 체결은 `910="258000"`이다. 체결 통보에서 910이 빠진 사례는 관측되지 않았다.
- **null이 나가면 이미 깨졌다:** `PositionBook.onFill`·`DailyPnlTracker`가 `fillPrice().multiply(...)`를 부른다. 체결가 없는 Fill은 두 리스너에서 NPE였다(**추론**, 코드로 확인).

### 2. 결정

- `Fill.fillPrice`를 `Price`로 바꿨다. 항상 있다. JSON은 숫자 그대로다.
- `OrderNoticeHandler.fillPriceOf`: 역산 단가가 양수면 그대로, 아니면 **주문 지정가로 근사**하고 WARN을 남긴다. 지정가 주문의 체결가는 지정가와 같거나 유리하므로 평단·손익 오차는 그 차이만큼이다. 지정가도 없으면(과거 행) ERROR를 남기고 Fill을 내지 않는다. 주문 체결량은 이미 저장됐고, 포지션은 대사·재기동 잔고 복원이 맞춘다.
- 원장 산술(`PositionBook`, `DailyPnlTracker`, `SlippageTracker`, 백테스트 러너)은 `.value()`로 꺼낸다. `PositionBook.Position.avgPrice`와 `DailyPnlTracker.Lot`은 `BigDecimal` 그대로다(평단은 누적 계산 결과라 값 객체 대상이 아님).
- `SlippageTracker`의 체결가 null·0 이하 검사는 지웠다.
- 백테스트는 `new Price(execPrice)`. 체결 기준가가 0이면 이제 예외로 드러난다(시세 CSV에 시가 0 이하 행 없음 — 조각 16).

### 3. 함정 점검

- 테스트의 `assertEquals(new BigDecimal(..), fill.fillPrice())` 4곳은 컴파일되지만 항상 실패한다. `Price` 비교로 고쳤다. `compareTo` 1곳은 컴파일 오류로 드러나 같은 방식으로 고쳤다.
- 운영 코드의 `fillPrice()` 중 산술이 아닌 곳은 로그 `{}`·`%s`(EventFeed, 텔레그램, 슬리피지 로그)뿐이다. `toString()`이 평문 숫자라 문구가 같다.
- 변이 확인: 근사 분기를 없애면(`new Price(null)`) 새 테스트가 실패함을 실행으로 확인했다.

### 4. 동작 변화

- 체결가 없는 LIVE 체결 통보: 이전에는 null 체결가 Fill → 포지션·손익 리스너 NPE. 이제 지정가 근사 Fill(WARN), 지정가도 없으면 Fill 미발행(ERROR).

### 5. 변경 파일

- 수정(main 9): `common/event/Fill`, `trading/OrderNoticeHandler`, `TradingService`, `portfolio/PositionBook`, `risk/DailyPnlTracker`, `monitor/SlippageTracker`, `backtest/BacktestExecutionHandler`, `BacktestRunner`, `InverseSwitchWalkForwardRunner`
- 테스트: 생성자 인자 18곳(컴파일 오류 줄만 스크립트 변환), 단언 5곳, `OrderNoticeHandlerTest` 신규 1(체결가 null·0 → 지정가 근사, 주문 체결량 반영)

### 6. 검증 상태

- `.\gradlew.bat test` 전체 통과(2026-09-30, 551건, 건너뜀 16).
- **미검증:** 장중 실제 체결의 포지션·손익·슬리피지 반영, event_store의 Fill JSON.

## 조각 18 — `Price`: `MarketTick.price`·`OrderNotice.fillPrice` (2026-09-30)

### 1. 사전 조사

- 둘 다 만드는 곳은 `market/RealMessageParser`(키움 WS ACL) 하나다.
- `MarketTick.price`를 읽는 곳은 없다. 감사 JSON 저장(`EventAuditListener`)뿐이다.
- `OrderNotice.fillPrice`는 `OrderNoticeHandler`만 읽는다. 누적 평균가(FID 910)로 증분 단가를 역산한다. 이미 null·0 이하를 "평균가 없음"으로 다루고 있었다.
- 실측(`ws_probe_20260911_intraday.txt`): 접수 통보는 910이 빈 값, 체결 통보는 `"258000"`. 시세 FID 10은 부호 접두(`+70100`, `-258000`)가 붙는다.
- 910 형식 오류는 `KiwoomNumbers.toBigDecimal`이 `NumberFormatException`을 던져 **통보 전체**가 사라졌다(파서 호출부로 전파).

### 2. 결정

- 두 필드를 `Price`로 바꿨다. JSON은 숫자 그대로다.
- 시세: 가격이 0이면 틱을 버리고 DEBUG로 남긴다. 종목코드 형식 오류(조각 13)와 같은 판단이다(소비자는 감사 기록뿐, 초당 여러 건).
- 체결 통보: `fillPriceOf`가 빈 값·0·**형식 오류**를 null로 번역한다. 형식 오류는 WARN을 남기고 통보는 살린다. 종목코드(조각 11)와 같은 원칙이다 — 체결 통보를 잃으면 포지션·손익이 틀어지고, 체결가가 없으면 핸들러가 지정가로 근사한다(조각 17).
- `OrderNoticeHandler`는 `averagePriceOf`로 `BigDecimal`을 꺼내 기존 역산 산술을 그대로 쓴다. 0 이하 검사는 파서가 맡으므로 null 검사만 남겼다.

### 3. 함정 점검

- `RealMessageParserTest`의 `assertEquals(new BigDecimal(..), tick.price()/notice.fillPrice())` 5곳은 컴파일되지만 항상 실패한다. `Price` 비교로 고쳤다.
- `OrderNoticeHandlerTest.notice(...)` 도우미는 파서와 같은 번역(빈 값·0 → null)을 하도록 바꿨다. 조각 17의 "체결가 0" 테스트는 이제 파서 번역을 거친 null로 핸들러 근사를 검증한다.

### 4. 동작 변화

- 가격 0 시세: 이전에는 0원 `MarketTick` 발행(감사 기록). 이제 버림.
- 910 형식 오류 체결 통보: 이전에는 통보 유실. 이제 체결가 없이 전달되고 지정가 근사 Fill.

### 5. 변경 파일

- 수정(main 4): `common/event/MarketTick`, `OrderNotice`, `market/RealMessageParser`, `trading/OrderNoticeHandler`
- 테스트: `RealMessageParserTest` 단언 5 + 신규 2(가격 0 시세 버림, 체결가 0·형식 오류 → null로 살림), `OrderNoticeHandlerTest` 도우미·생성부 3

### 6. 검증 상태

- `.\gradlew.bat test` 전체 통과(2026-09-30, 553건, 건너뜀 16). `RealMessageParserTest` 17.
- **미검증:** 장중 WS 시세·체결 통보의 실제 수신과 event_store의 MarketTick JSON.

## 조각 19 — `Price`: `PositionRestored.avgPrice`, 평단 미상은 null (2026-09-30)

### 1. 결정 (사용자 확정 2026-09-30: 권장안)

- `PositionRestored.avgPrice`를 `Price`로 바꾸고 **null을 허용**한다. 잔고 응답에서 매입가를 못 찾으면 "평단 모름"이다.
  - 이전: `PositionRestorer.firstPrice`가 `BigDecimal.ZERO`를 지어냈다(규칙 §2.1 "파서·어댑터가 값을 지어내지 않는다" 위반). 이 0이 C3 국면 OFF 강제 청산의 기준가로 쓰이면 0원 지정가 주문이 나갔다.
  - 이제: null + WARN. 이 포지션의 평단 기반 주문은 `RiskGate`가 거부한다(조각 16).
- `PositionBook.Position.avgPrice`(`BigDecimal`)도 null을 허용한다. 모르는 평단에 추가 매수를 섞어도 여전히 모른다. 부분 매도는 수량만 줄인다(기존과 같음).
- 문구는 `Position.avgPriceText()` 한 곳에서 만든다("평단 미상"). 일일 리포트(`DailyReportScheduler`)와 텔레그램 상태(`TelegramCommandPoller`)가 쓴다 — 이전에는 `null`이 그대로 찍힐 뻔했다.
- 대시보드 FE: `PositionsCard`가 `Number(null)`로 **0**을 보여 줄 것이라 `—`로 표시하게 고쳤다(`types.ts`의 `avgPrice`에 `null` 추가). `tsc --noEmit` 통과.
  - **빌드 산출물(`app/src/main/resources/static`)은 다시 만들지 않았다.** Windows용 esbuild 바이너리라 작업 환경에서 `vite build`가 돌지 않는다. `frontend`에서 `npm run build` 후 커밋해야 화면에 반영된다.
- `DailyPnlTracker`는 `PositionRestored`를 받지 않는다(자체 장부는 체결로만 만든다). 영향 없음.

### 2. 변경 파일

- 수정: `common/event/PositionRestored`, `execution/PositionRestorer`, `portfolio/PositionBook`, `monitor/DailyReportScheduler`, `TelegramCommandPoller`, `frontend/src/types.ts`, `frontend/src/components/PositionsCard.tsx`
- 테스트: 생성부 3, 신규 2(`PositionRestorerTest` 매입가 0·누락 → null, `PositionBookTest` 평단 미상 포지션의 추가 매수·부분 매도)

### 3. 검증 상태

- `.\gradlew.bat test` 전체 통과(2026-09-30, 555건, 건너뜀 16). FE `tsc --noEmit` 통과.
- **미검증:** 대시보드 화면(빌드 전), 실제 잔고 복원 로그.

### 10. 변경 이력

- 2026-09-30: 조각 1
- 2026-09-30: 조각 2(BrokerPort)
- 2026-09-30: 조각 3(StockCode 1단계)
- 2026-09-30: 조각 4(StockCode 2단계: trading·BrokerPort)
- 2026-09-30: 조각 5(StockCode 3단계: OrderRequest 이벤트)
- 2026-09-30: 조각 6(Fill 이벤트)
- 2026-09-30: 조각 7(Signal 이벤트)
- 2026-09-30: 조각 8(PositionRestored 이벤트)
- 2026-09-30: 조각 9(장부 맵 키 전환)
- 2026-09-30: 조각 10(SignalDecision 이벤트)
- 2026-09-30: 조각 11(OrderNotice 파서 번역)
- 2026-09-30: 조각 12(Fill 주문번호)
- 2026-09-30: 조각 13(MarketTick 이벤트, Candle 보류)
- 2026-09-30: 조각 14(Quantity 이벤트 적용)
- 2026-09-30: 조각 15(Price 1단계: 타입과 JSON)
- 2026-09-30: 조각 16(Price: OrderRequest.limitPrice)
- 2026-09-30: 조각 17(Price: Fill.fillPrice)
- 2026-09-30: 조각 18(Price: MarketTick·OrderNotice)
- 2026-09-30: 조각 19(Price: PositionRestored, 평단 미상 null)
