# 리팩토링 계획 — 코딩 규칙 대비 격차 정리

- 날짜: 2026-09-29
- 기준 문서
  - `C:\claude\CLAUDE.md`
  - `C:\claude\coding-rules.md` (이하 "규칙", 절 번호는 `§`로 표기)
- 상태: **진행 중**
  - S1: 완료 (`log-secret-masking.md`)
  - S2: 완료, 사용자 확정 (a) 루프백 바인딩 (`loopback-binding.md`). 재기동 실측은 남음
  - R1: 완료 (`http-timeouts.md`). 기동 후 실측은 남음
  - R3: 완료 (`ipo-command-transaction.md`). 동시 갱신 문제는 R2로 넘김
  - R2: 완료, 커밋 `fb02d61` (`order-concurrency.md`). 사용자 확정. 실제 DB 검증은 남음
  - R4(신규, F3): (a) 보류 후 재처리, 커밋 `3f6dc77`, 사용자 확정 (`order-concurrency.md` 8절). (b) 체결내역 조회 TR은 미착수
  - A4: 빈·정적 유틸 완료, 커밋 `71e2afd`. 엔티티 시각(③)도 완료(2026-09-30 사용자 결정) — 직접 시계 호출 0, 규칙 테스트로 고정 (`clock-injection.md` 10절)
  - A3: 완료, 커밋 `2badb2f` (`architecture-rules.md`). market→kiwoom 규칙은 A2 뒤에
  - A2: 완료, 커밋 `71b7927` (`market-data-port.md`). market→kiwoom 규칙 추가
  - B2: 완료, 커밋 `6075dab` (`request-validation.md`). 킬스위치 fail-open 결함 실측 확인·수정
  - B1: 완료, 커밋 `578dba8` (`error-handling.md`)
  - A1: 조각 1(trading 내부 BrokerOrderId·Quantity) 완료, 커밋 `f6915b7`. 조각 2(BrokerPort BrokerOrderId) 완료, 커밋 `a08d430`. 조각 3(StockCode 1단계: 타입+JSON) 완료, 커밋 `f923f68`. 조각 4(StockCode 2단계: trading·BrokerPort) 완료, 커밋 `7d848db`. 조각 5(OrderRequest 이벤트) 완료, 커밋 `866dedc`. 조각 6(Fill 이벤트) 완료, 커밋 `1eba51a`. 조각 7(Signal 이벤트) 완료, 커밋 `b1c6a1d`. 조각 8(PositionRestored 이벤트) 완료, 커밋 `e6e9a65`. 조각 9(장부 맵 키 StockCode 전환) 완료, 커밋 `311f8ff`. 조각 10(SignalDecision 이벤트) 완료, 커밋 `6159065`. 조각 11(OrderNotice 파서 번역) 완료, 커밋 `263f490`. 조각 12(Fill 주문번호 BrokerOrderId) 완료, 커밋 `5bbe0b1`. 조각 13(MarketTick 이벤트) 완료, 커밋 `a22293a`. 조각 14(Quantity 이벤트 적용) 완료, 커밋 `f4b0573`. 조각 15(Price 1단계: 타입+JSON) 완료, 커밋 `6d6299f`. 조각 16(OrderRequest.limitPrice) 완료, 커밋 `441dc13`. 조각 17(Fill.fillPrice) 완료, 커밋 `97c7cee`. 조각 18(MarketTick·OrderNotice 가격) 완료, 커밋 `882c365`. 조각 19(PositionRestored 평단 미상 null) 완료, 커밋 `6dbd55e`. 조각 20(C3 강제 청산 기준가 = 최우선 매수호가, 사용자 결정) 완료, 커밋 `fbbea40`. 조각 21(Signal.refPrice) 완료, 커밋 `18eeada` — 이벤트 가격의 Price 도입 끝(Candle 보류). 조각 22(C3 판단일 기준가 = 최유리 호가, 사용자 결정) 완료. Candle은 사용자 결정으로 보류. 사용자 확정: StockCode 단계적 도입 (`value-objects.md`). ARCH 3절 1순위 값 객체는 Candle을 빼고 모두 적용
  - A5: 완료 (`time.md`). `hibernate.jdbc.time_zone`의 DATE 영향은 내장 PostgreSQL로 실측(영향 없음)
  - P3 Testcontainers: 완료, 사용자 확정(운영 정확도 우선 + Docker 없을 때 내장 PG 16.15) (`db-integration-test.md`). H2 제거
  - B3: 완료, 사용자 확정 A안 (`large-classes.md`) — WS 클라이언트에서 ReconnectBackoff·DisconnectionTracker 추출. RiskGate·백테스트는 유지
  - B4: 사용자 결정 2026-09-30 — 나중에
  - 나머지: 미착수

## 0. 원칙

규칙 §0에 따라 autoStock 작업은 **유지보수**다. 판단 우선순위는 다음과 같다.

1. 사용자의 현재 요청
2. 리포의 기존 결정
   - `PLAN.md` ADR-1~14
   - `docs/ARCHITECTURE.md`(설계 규칙 20)
   - `docs/RUNBOOK.md`
   - 결정을 담은 주석과 테스트
3. 규칙: 기존 관례가 없는 곳에만 적용한다.

기존 관례가 규칙과 달라도 임의로 바꾸지 않는다. 결함으로 보이는 것은 먼저 알리고 사용자가 정한다.
원칙끼리 부딪히면 정확성·보안 → 단순함 → 변경 용이성 → 성능 순으로 고른다.

근거 표기(§14)는 다음 두 가지로 나눈다.

- **확인**: 코드를 직접 읽어 확인한 것
- **추론**: 실측하지 않은 판단

## 1. 현황 요약

### 구성

- Gradle 멀티모듈 2개
  - `common`: 이벤트 계약 record, 유틸
  - `app`: Spring Boot 3.5.16, Java 21
- Spring Modulith 1.4.12를 쓰고, 패키지는 기능별로 나뉘어 있다. 패키지 안은 계층 구분이 없는 평면 구조다.
  - 모듈: market, strategy, risk, trading, execution, portfolio, ipo, macrointel, monitor, audit, backtest, kiwoom
- 외부 연동
  - PostgreSQL 16, Flyway V1~V7
  - 키움 REST·WebSocket, DART, ECOS, FRED, Telegram
  - WebClient를 `.block()`으로 동기 호출한다.
- 테스트
  - 약 89개 클래스. JUnit 5, Mockito, 단위 테스트 중심이다.
  - `ModularityTests`가 Modulith `verify()`로 모듈 경계를 검사한다.
  - 스모크 테스트 `*SmokeIT` 3개는 키가 없으면 건너뛴다.

### 이미 규칙과 맞는 부분 (건드리지 않는다)

- 생성자 주입, `final` 필드. 필드 주입과 Lombok은 없다. 엔티티에 세터가 없다(§4).
- record를 광범위하게 쓴다: 이벤트, `*Properties`, `monitor/view/*`(§4).
- 엔티티 기본 생성자는 `protected`다.
- `OrderEntity`는 상태기계를 스스로 강제하는 풍부한 모델이다(`transitionTo`, `applyFill`)(§2.2).
- `ddl-auto: validate`, `open-in-view: false`, Flyway 마이그레이션을 쓴다(§7, §8).
- 시각 컬럼은 `TIMESTAMPTZ`이고 앱에서는 `Instant`로 다룬다. `Clock` 빈(`config/ClockConfig`)이 있다(§9).
- 응답은 View DTO로 주고 엔티티를 노출하지 않는다(§3, ARCH §10).
- 주문 API는 무조건 재시도하지 않는다. 타임아웃은 UNKNOWN으로 두고 대사로 확정한다. ClientOrderId에 UNIQUE를 걸고, 브로커 전송 전에 DB에 먼저 저장한다(§2.5).
- 설정은 `@ConfigurationProperties` 위주다. 비밀값은 환경변수로만 주입하고, 로그는 Logback 마스킹 컨버터를 거친다(§5.3, §2.7).
- 의존성 버전은 전부 고정돼 있다(§11).

## 2. 조치 항목

각 항목의 형식은 다음과 같다: 규칙 절 / 근거 / 조치 / 재사용할 기존 코드 / 위험 / 롤백 / 검증.

### P0 — 정확성·보안 결함 (알리고 사용자 확인 후 수정. 권고: 수정)

#### S1. ECOS API 키가 로그로 샐 수 있음

- **규칙**: §5.3 비밀 값, §6 "로그에 비밀을 남기지 않는다", "외부 응답 본문을 통째로 찍지 않는다"
- **근거(확인)**
  - `app/.../macrointel/EcosClient.java:93`: 키가 URL **경로 세그먼트**(`/api/StatisticSearch/{key}/...`)로 들어간다.
  - `EcosClient.java:74`: 원시 예외를 `log.error`로 남긴다. WebClient 예외 메시지에는 요청 URI가 들어간다(추론: 4xx·5xx `WebClientResponseException`의 메시지 형식).
  - `common/.../util/SecretMasking.java:54-67`: 마스킹 규칙은 `key=value`, JSON 키, `bot<token>`, `Bearer` 형태만 잡는다. 경로에 든 키는 못 잡는다.
  - `EcosClient.java:122`, `:146`과 `FredClient.java:110`: 응답 본문(`response`) 전체를 로그에 남긴다.
- **조치**
  1. `SecretMasking`에 ECOS 경로 패턴을 추가한다(`/StatisticSearch/<key>/` → `/StatisticSearch/***/`). 마스킹 규칙을 한 곳에 모으기 위해서다(SSOT).
  2. `EcosClient`와 `FredClient`의 catch 로그는 `SecretMasking.sanitizeForLogging`을 거친 메시지만 남긴다.
  3. 응답 본문 로그는 결과 코드(`RESULT.CODE` 등)와 최상위 키 목록으로 줄인다.
- **재사용**: `SecretMasking`, `config/logging/SecretMaskingConverter`, `ThrowableMaskingConverter`
- **위험**: 낮음. 장애 분석에 쓸 정보가 줄어든다. 결과 코드는 남기므로 원인 추적은 가능하다.
- **롤백**: 커밋을 되돌린다.
- **검증**: `SecretMaskingTest`에 경로형 키 케이스를 추가한다. 기존 `EcosClientTest`와 `FredClientTest`를 돌린다.

#### S2. 인증 없이 열린 위험 엔드포인트

- **규칙**: §5.1 Secure by Default, §5.7 "운영 관리 경로는 최소만 열고 막는다"
- **근거(확인)**
  - `application.yml:42-47`: actuator에 `shutdown`이 노출돼 있고 `access: unrestricted`다.
  - `application.yml:150-154`: Swagger UI의 try-it-out이 켜져 있다.
  - `monitor/DashboardController`: `POST /test-signal`(주문 신호), `POST /killswitch`, `POST /orders/{id}/cancel`, `/api/trading/start|stop`에 인증이 없다.
  - Spring Security 의존성이 없다.
- **기존 결정**: yml 주석이 "로컬 전용 사용"을 전제한다. `stop_autostock.bat`은 `POST /actuator/shutdown`으로 종료한다.
- **선택지**
  - (a) **권고**: `server.address: 127.0.0.1`로 루프백에만 바인딩하고, 기동 시 바인딩 주소를 검사한다(fail-fast). 변경이 가장 작고 bat 스크립트도 그대로 동작한다.
  - (b) Spring Security를 도입한다(Basic 또는 토큰). 원격 접속이 필요할 때 쓴다. FE와 bat에도 인증을 넣어야 한다.
- **사용자 결정 필요**
  - 현재 원격(다른 PC, 모바일)에서 대시보드를 쓰는지
  - Telegram 명령(`TelegramCommandPoller`) 외에 원격 제어 경로가 필요한지
- **롤백**: 설정값을 되돌린다.
- **검증**: 다른 호스트에서 `curl http://<LAN IP>:8080/actuator/health`가 실패하는지, `stop_autostock.bat`이 정상 종료되는지 확인한다.

#### R1. 외부 호출 타임아웃 공백

- **규칙**: §2.6 "모든 원격 호출에는 연결·응답 타임아웃", §2.1 "타임아웃은 명시"
- **근거(확인)**
  - 응답 타임아웃이 있는 곳은 `kiwoom/KiwoomRestClient`(`.timeout(15s)`)뿐이다.
  - 다음 호출은 `.block()`에 타임아웃이 없다. Reactor Netty는 기본 응답 타임아웃이 없어서 무한 대기가 가능하다.
    - `kiwoom/TokenManager`
    - `ipo/DartClient`
    - `macrointel/MajorDisclosureDartClient`
    - `macrointel/EcosClient`
    - `macrointel/FredClient`(`:85`에 TODO: "타임아웃/재시도 정책은 실제 응답 지연 확인 후 결정")
    - `market/HolidaySyncService`
    - `monitor/TelegramNotifier`, `monitor/TelegramCommandPoller`
  - `market/KiwoomWebSocketClient`의 connect 타임아웃이 명시돼 있지 않다.
  - 커밋 `8e86557`(절전 복귀·유량 초과 내성)은 키움 REST에만 타임아웃을 적용했다. 이것과 같은 계열의 잔여 결함이다.
- **조치**
  1. `config/`에 `WebClientCustomizer`를 하나 둔다(SSOT). Reactor Netty `ChannelOption.CONNECT_TIMEOUT_MILLIS`와 `responseTimeout`을 여기서 설정한다. 값은 `@ConfigurationProperties` record(예: `http.connect-timeout`, `http.response-timeout`)로 받는다. 모든 `WebClient.Builder` 주입처에 자동 적용된다.
  2. DART 두 클라이언트는 JDK 암호 스위트 전용 커넥터를 따로 만든다. 여기에도 같은 값을 명시한다. 커스터마이저가 커넥터를 덮지 않는지 확인해야 한다.
  3. WebSocket connect에 타임아웃을 건다(`connect(...).get(timeout)` 또는 컨테이너 설정).
  4. 재시도는 조회 API(ECOS, FRED, DART, 공휴일)에만 기존 `resilience4j-retry`로 건다(ARCH §6 "조회 API에만 Retry"). 스케줄 쪽에 이미 따라잡기 크론(매시 5분)이 있으므로 재시도를 겹쳐 걸지 않는다는 규칙(§2.6)에 맞게 **재시도 없이 타임아웃만** 거는 것을 기본으로 한다.
  5. `TokenManager` 발급이 타임아웃으로 실패하면 기존 발급 실패 경로를 탄다. 동작 변경이 없는지 확인한다.
- **위험**: 중간. 응답이 느린 외부 API(DART)가 새로 실패로 떨어질 수 있다. `docs/measured/`의 실측 지연을 보고 여유 있게 잡는다.
- **롤백**: 설정값을 크게 늘리거나 커스터마이저 빈을 제거한다.
- **검증**
  - `callApi` 오버라이드 관례(`HolidaySyncService`, `EcosClient`)는 HTTP 층을 우회하므로 타임아웃 검증에는 맞지 않는다.
  - 대신 로컬 `MockWebServer`나 지연 응답 서버로 커스터마이저 단위 테스트를 한다. 필요하면 테스트 의존성을 하나 추가한다(§11 최소 의존성과 저울질).

#### R2. 주문 동시 갱신 (추론, 조사 필요)

- **규칙**: §7 "동시성은 명시적으로 제어", §5.4 TOCTOU
- **근거**
  - (확인) `trading/OrderEntity`에 `@Version`이 없다.
  - (추론) 다음 세 경로가 같은 주문을 읽고-바꾸고-저장할 수 있다. WS 체결과 대사가 겹치면 나중 저장이 앞선 체결 반영을 덮을 가능성이 있다.
    - `trading/OrderNoticeHandler`(WS 체결 통지)
    - `trading/ReconciliationService`(fixedDelay 5분)
    - `trading/StaleOrderCanceller`(fixedDelay 60초)
- **조치**
  1. 먼저 조사한다. 세 경로의 트랜잭션, 잠금, 스레드 모델을 확인한다. `IpoDealEntity`(배치 `saveAll`과 수동 입력의 경합, R3에서 발견)도 함께 본다. `ReentrantLock` 등 기존 직렬화 장치가 있는지도 본다(ADR-5).
  2. 경합이 실재하면 Flyway `V8__order_version.sql`로 `version BIGINT NOT NULL DEFAULT 0`을 추가하고 `@Version`을 단다. 충돌 시 재조회하고 상태기계 규칙대로 다시 적용한다.
- **위험**: 중간. 충돌 예외 처리 경로가 새로 생긴다.
- **롤백**: 애너테이션을 제거한다. 컬럼은 남겨도 무해하다. 마이그레이션은 앞으로만 간다(§8).
- **검증**: 두 스레드가 같은 주문을 갱신하는 단위 테스트를 쓴다. 이 테스트는 DB가 필요하므로 P3의 Testcontainers 결정과 연결된다.

#### R3. IPO 수동 입력에 트랜잭션 경계가 없음

- **규칙**: §7 "트랜잭션은 애플리케이션 서비스에서 연다", §3 "컨트롤러는 변환·호출만"
- **근거(확인)**
  - `monitor/IpoController.java:50-59`(`record`), `:66-77`(`metrics`)가 컨트롤러 안에서 `findById → 변경 → save`를 한다.
  - `metrics`는 `syncScheduler.evaluateFilter`와 `refreshStatus`도 호출한다. 업무 흐름을 조율하는 일이 컨트롤러에 있다.
- **조치**
  1. ipo 모듈에 애플리케이션 서비스(예: `IpoDealCommandService`)를 추출한다. `recordMyDeal`과 `updateMetrics`를 `@Transactional`로 둔다. 컨트롤러는 호출과 응답 변환만 한다.
  2. 없는 ID는 `Optional` 반환으로 표현하고, 컨트롤러가 `404`로 바꾼다. 현행 동작을 유지한다.
- **위험**: 낮음.
- **롤백**: 커밋을 되돌린다.
- **검증**: 기존 `IpoController` 테스트를 서비스 테스트로 옮기거나 보강한다.

### P1 — 리포가 이미 결정했지만 미완인 것 (기존 결정과 규칙 방향이 같으므로 바로 진행 가능)

#### A1. Value Object 1순위 도입

- **규칙·결정**: ARCH §3("중요 도메인 값의 primitive 사용 금지", 1순위 `StockCode`, `Quantity`, `Price`, `BrokerOrderId`), 규칙 §2.2 값 객체, §4
- **근거(확인)**: `common/util/ClientOrderId`만 record VO다. 종목코드, 수량, 가격은 `String`, `long`, `BigDecimal` 원시 타입이다.
- **조치**
  - `ClientOrderId`와 같은 패턴(record + 생성 시 검증 + 정적 팩토리)으로 만든다.
  - 도입은 안쪽부터 한다: trading → risk → strategy.
  - common 이벤트는 ARCH 지침대로 **스키마 v2로 단계 도입**한다. 새 필드를 추가하고, 사용처를 옮기고, 옛 필드를 제거한다(Expand-Contract, §2.8). `event_store`에 쌓인 과거 이벤트의 역직렬화 호환도 확인한다.
- **위험**: 중간. 변경 범위가 넓다. 모듈 하나씩, 커밋 하나씩 나눈다.
- **검증**: VO 경계값 테스트(0, 음수, 자릿수)와 전체 테스트를 돌리고, 과거 이벤트 리플레이(backtest)가 동작하는지 확인한다.

#### A2. 시세 조회 포트 추출

- **규칙·결정**: ARCH §4, 설계 규칙 5("Kiwoom은 Adapter로만"), 규칙 §2.3 포트는 안쪽이 소유
- **근거(확인)**: `market/MarketQueryService`와 `market/MinuteBarArchiver`가 `kiwoom.KiwoomRestClient`를 직접 쓴다. `MarketQueryService`는 `Map<String, Object>`를 반환한다.
- **조치**
  - `execution/BrokerPort` ↔ `KiwoomBrokerAdapter`의 선례를 따른다. market이 소유하는 포트(예: `MarketDataPort`)를 만들고, kiwoom 쪽 어댑터가 구현한다.
  - 반환 타입은 record로 바꾼다(§3 "반환 타입을 Object로 두지 않는다").
- **검증**: `ModularityTests`와 Fake 포트로 market 단위 테스트를 한다.

#### A3. 의존 규칙을 테스트로 강제

- **규칙·결정**: ARCH §11("필요 시 ArchUnit 규칙 추가"), 설계 규칙 19, 규칙 §3, §18.2
- **조치**: `ModularityTests` 옆에 ArchUnit 테스트를 추가한다.
  - `strategy` → `kiwoom` 금지, `risk` → `kiwoom` 금지, `strategy` → `execution` 금지
  - 순수 계산 클래스(`strategy/*Math`, `risk/KrxTickSize`, `risk/PositionSizer`)는 `org.springframework..`, `java.time.Clock`, WebClient를 참조하지 않는다(ARCH §5 Functional Core).
- **주의**: ArchUnit은 Modulith가 전이 의존으로 가져온다. 테스트에서 직접 쓰면 명시 의존으로 선언할지 정하고, 실제로 해석된 버전을 확인한다(§11).

#### A4. 현재 시각 일원화

- **규칙·결정**: 규칙 §9 "현재 시각은 주입받는 `Clock`에서", ARCH §5 "시계 접근 금지"
- **근거(확인)**
  - main 코드에서 `now()` 직접 호출이 약 41곳이다(`Instant.now()` 약 28, `LocalDate.now(KST)` 약 13). `Clock` 주입 호출은 약 20곳이다.
  - 주요 위치: `strategy/C3LiveStrategy`(5), `market/HolidaySyncService`(4), `market/RealMessageParser`(4), `macrointel/MacroSyncScheduler`(3), `risk/RiskGate`(3, Clock과 혼용), `risk/DailyLimitTracker`(2), `risk/KillSwitch`(2), `kiwoom/TokenManager`(2)
  - 엔티티 생성 시각: `OrderEntity`, `IpoDealEntity`, `EventRecord` 등
- **조치**
  - 기존 `Clock` 빈을 생성자로 주입한다. 엔티티는 팩토리 메서드가 시각을 인자로 받는다.
  - 업무 날짜는 `LocalDate.now(clock.withZone(MarketConstants.KST))`처럼 한 형태로 통일한다.
  - `RiskGate`처럼 한 클래스에서 두 방식을 섞어 쓰는 곳부터 고친다.
- **검증**: 고정 `Clock`으로 자정·장 시작 경계 테스트를 추가한다(§12 경계값).

#### A5. 시간대 설정 명시

- **규칙**: §2.1 "시간대는 명시", §9 "이중 변환 금지, 어느 층에서 변환하는지 한 곳에"
- **근거(확인)**: JVM `user.timezone`, `hibernate.jdbc.time_zone`, Jackson 시간대 설정이 없다. `TIMESTAMPTZ`와 `Instant` 조합이라 현재 동작은 안전하다(추론). 다만 기본값에 기대고 있다.
- **조치**
  - yml에 `spring.jpa.properties.hibernate.jdbc.time_zone: UTC`와 `spring.jackson.time-zone: UTC`를 명시한다.
  - 변환 층을 표로 정리해 이 문서나 `aiDoc/time.md`에 둔다: 저장 UTC, 표시와 날짜 경계는 KST, `@Scheduled`는 `Asia/Seoul`.
- **검증**: 기존 테스트를 돌리고, 대시보드 시각 표시가 전후 동일한지 실측한다.

### P2 — 규칙 방향의 설계 개선 (작게 나눠 진행)

#### B1. 전역 예외 처리와 에러 응답 형식

- **규칙**: §6 "에러 응답 형식을 하나로(RFC 9457)", "전역 예외 처리는 한 곳", "에러 코드는 enum 한 곳", §10.2.2
- **근거(확인)**: `@RestControllerAdvice`가 없다. `IpoController.java:83`이 FQCN으로 `ResponseStatusException`을 직접 던진다. 에러 코드 enum이 없다.
- **조치**
  - `monitor`에 `@RestControllerAdvice`를 하나 둔다. Spring 내장 `ProblemDetail`을 쓰고, `spring.mvc.problemdetails.enabled: true`를 켠다.
  - 에러 코드는 enum 한 곳에 모으고, 선언과 사용이 어긋나지 않는지 테스트로 검사한다.
  - 4xx(입력)와 5xx(하위 시스템) 예외 타입을 나눈다. 기존 `KiwoomApiException`은 `502`로 매핑한다.
- **위험**: API 에러 본문이 바뀐다. `frontend/`(React, TanStack Query)의 에러 표시 코드에 영향이 있는지 **확인이 필요하다**.

#### B2. 요청 DTO와 검증

- **규칙**: §2.1 "형식 검증은 DTO 한 곳", §5.2 대량 할당 방지, §10.2.8
- **근거(확인)**
  - `DashboardController.java:93`: `toggleKillSwitch(@RequestBody Map<String, Object> body)`
  - `TestSignalRequest`, `IpoRecordRequest`, `IpoMetricsRequest`에 `@Valid`가 없다. Bean Validation 스타터는 이미 클래스패스에 있다.
- **조치**
  - 킬스위치 요청을 record(`KillSwitchRequest(boolean active, String reason)` 등)로 바꾼다.
  - 모든 `@RequestBody`에 `@Valid`와 필드 제약(수량 양수, 종목코드 패턴)을 단다. A1 VO가 도입되면 형식 검증은 VO 생성과 겹칠 수 있다. 형식은 DTO, 업무 규칙은 도메인으로 나눈다(규칙 §0 표).

#### B3. 책임 분리 후보 (조사 후 결정)

- **규칙**: §2.1 SRP. 단, KISS·YAGNI·"잘못된 추상화는 중복보다 비싸다"와 저울질한다.
- **후보**
  - `market/KiwoomWebSocketClient`(345줄): 연결, 지수 백오프, 로그인, 구독, watchdog, stale 판정이 한 클래스에 있다. 연결 수명주기와 구독 관리를 나누는 안을 검토한다.
  - `risk/RiskGate`(349줄): ARCH §2가 말하는 "Policy 조합" 형태(정책 인터페이스와 게이트의 조합)로 나눌지 검토한다.
- 긴 Javadoc 비중이 커서 실제 로직은 줄 수보다 작다. 분리했을 때 테스트가 쉬워지는 경우에만 한다. 아니면 하지 않는다.

#### B4. 감사·판정 기록 실패의 알림

- **규칙**: §2.7 "실패는 알림이 가는 경로로", §6 "실패를 삼키지 않는다"
- **근거(확인)**: `audit/EventAuditListener.java:85-86`과 `monitor/SignalDecisionListener.java:50-54`는 실패 시 `log.error("...실패(스킵)...")`만 남긴다.
- **판단 필요**: 감사 기록 실패가 매매를 막지 않게 한 것은 의도된 기능 저하로 보인다(§2.6 허용). 다만 누락이 조용히 쌓인다.
- **안**: 매매 흐름은 막지 않고, 기존 `monitor/Notifier`(Telegram)로 알린다. 알림 폭주를 막기 위해 일정 시간 동안 한 번만 보낸다. **사용자 확인이 필요하다.**

### P3 — 규칙과 기존 결정·관례가 다른 것 (사용자 결정 필요. 기본값은 현행 유지)

| 항목 | 규칙 | 현행·기존 결정 | 권고 |
|---|---|---|---|
| 구조화 JSON 로그 | §2.7, §6 | 평문 롤링 파일(`logs/autostock.log`)과 마스킹 컨버터 | **유지**. 단일 로컬 프로세스라 수집 플랫폼이 없다. 필요해지면 Boot 내장 structured logging으로 전환한다 |
| Testcontainers 통합 테스트(**2026-09-30 완료** — `db-integration-test.md`) | §12 | 단위 테스트뿐이다. 저장소, SQL 매핑, Flyway는 검증하지 않는다. H2는 의존성만 있고 쓰지 않는다 | **도입 검토**. PG 컨테이너로 Flyway와 `ddl-auto: validate`, 저장소를 검증한다(R2 검증에도 필요). 쓰지 않는 H2는 제거한다. 로컬과 CI에 Docker가 필요하다 |
| 모듈 내부 계층 패키지(domain/application/adapter) | §3 | 평면 구조. ARCH §2는 "커지는 모듈부터 적용" | **필요할 때만**. 후보 1순위는 trading. 지금은 YAGNI |
| 리포에 커밋하는 md는 README만 | §13 | `PLAN.md`, `PROGRESS.md`, `docs/`를 커밋한다 | **유지**(기존 관례) |
| 버전 카탈로그 | §11 | BOM과 직접 고정 4개(springdoc, resilience4j×2, junit-bom) | **유지**. 규모가 작다 |
| Saga, 트랜잭셔널 아웃박스 | §2.5 | ARCH §12에서 Saga를 채택하지 않았다. Modulith 이벤트 발행 로그(`event_publication`)를 쓴다 | **유지**(물리 모놀리스) |
| 서킷 브레이커 | §2.6 | 없다. 레이트 리미터와 429·1700 재시도만 있다 | **유지**. R1 타임아웃이 먼저다. 반복 장애 실측이 쌓이면 검토한다 |
| 감사 컬럼(생성·수정 주체) | §7 | 테이블마다 다르다(추론, 전수 확인 전) | **보류**. 단일 사용자 시스템이라 "주체" 값이 의미가 적다 |

## 3. 실행 순서

작은 커밋 하나에 목적 하나만 담는다. 커밋 메시지는 한국어 한 줄로 쓰고, AI 작성 표기는 넣지 않는다. 커밋은 사용자가 요청할 때만 한다(§14).

1. **P0**: S1 → R1 → S2(방식 결정 후) → R3 → R2(조사 결과에 따라)
2. **P1**: A5 → A4 → A3 → A2 → A1(모듈 단위로 여러 커밋)
3. **P2**: B2 → B1(FE 영향 확인 후) → B4(결정 후) → B3(조사 후)
4. **P3**: 결정된 항목만 별도로 진행한다.

단계마다 다음을 한다(§18.1).

1. 관련 `aiDoc` 문서와 코드를 읽는다.
2. 지킬 동작을 테스트로 먼저 적는다.
3. 안쪽(도메인)부터 구현한다.
4. `.\gradlew.bat test`를 돌린다.
5. §18.3 점검표를 확인한다.
6. `aiDoc/<주제>.md`를 쓴다. 규칙과 다르게 가는 곳은 `aiDoc/adr/NNNN-<제목>.md`로 남긴다.

## 4. 검증 방법

- 공통: `.\gradlew.bat test`(CI `.github/workflows/ci.yml`과 같은 명령). `ModularityTests`가 통과해야 한다.
- 스모크 테스트 `*SmokeIT`는 키가 있는 환경에서만 돈다. 못 돌렸으면 결론에 "미검증"이라고 적는다.
- paper 프로필로 모의 운영 중이다. 운영 동작이 바뀌는 R1, R2, A1, A4는 장 마감(15:50 리포트) 뒤에 반영하고, 다음 거래일의 로그와 텔레그램 리포트로 확인한다.
- 롤백: 항목별 "롤백" 줄을 따른다. 설정만 바꾼 변경은 값을 되돌리면 된다.

## 5. 사용자 결정이 필요한 것

1. ~~**S2**~~: 사용자 확정(2026-09-29). 원격 접속을 쓰지 않으므로 루프백 바인딩으로 한다.
2. **R2**: 조사 결과 경합이 있으면 `@Version` 도입에 동의하는가.
3. **B1**: 에러 본문을 RFC 9457로 바꾸는 데 동의하는가(FE 영향 포함).
4. **B4**: 감사 기록 실패를 Telegram으로 알릴 것인가.
5. **P3 표**: 각 항목을 유지할지 도입할지. 특히 Testcontainers.

## 6. 참고 — 조사 중 발견한 기타 사항

- `app/build/classes/`에 재편 전 패키지(`marketdata`, `newsintel`)의 산출물이 남아 있다. 소스 문제는 아니며 `.\gradlew.bat clean`으로 정리된다.
- `analysis` 패키지는 `package-info`만 있다(ADR-6 예정 모듈). 유지한다.
- `C:\claude\CLAUDE.md`의 규칙 import 경로가 `@D:/...`로 잘못돼 있었다. 2026-09-29에 사용자 확정으로 `@C:/claude/coding-rules.md`로 고쳤다.

## 변경 이력

- 2026-09-29: 최초 작성(코드 조사 기반, 코드 변경 없음)
