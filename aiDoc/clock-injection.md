# 현재 시각 일원화 (A4)

- 날짜: 2026-09-30
- 계획: `refactoring-plan.md` A4
- 사용자 확정: A4를 다음 작업으로 진행

## 1. 목적

`Instant.now()`와 `LocalDate.now(KST)`를 직접 호출하는 곳이 49곳(`System.currentTimeMillis()` 1곳 포함)이었다.

- 규칙 §9: 현재 시각은 주입받는 `Clock`에서 얻는다. 날짜 경계는 업무 시간대로 계산한다.
- ARCH §5: 매매 판단 코드는 시계에 직접 접근하지 않는다.

직접 호출하면 자정, 장 시작, 만료 같은 시각 경계를 테스트에서 고정할 수 없다. 이미 `config/ClockConfig`(`Clock.systemUTC()` 빈)가 있었고 14개 클래스가 이 빈을 쓰고 있었다.

## 2. 결정과 근거

- **① Clock이 이미 있는 클래스(4개):** `RiskGate`, `IpoSyncScheduler`, `DisclosureBlacklist`, `DisclosureBlacklistSyncScheduler`는 기존 `clock` 필드를 쓰도록 바꿨다.
- **② Clock이 없는 빈(11개):** 생성자 마지막 인자로 `Clock`을 받는다. `IpoSyncScheduler`, `StaleOrderCanceller`의 기존 관례를 따랐다.
  - 대상: `TradingService`, `ReconciliationService`, `MacroSyncScheduler`, `C3LiveStrategy`, `EcosClient`, `TokenManager`, `KillSwitch`, `DailyLimitTracker`, `DashboardController`, `HolidaySyncService`, `MinuteBarArchiver`
  - `ReconciliationService`의 `System.currentTimeMillis()`는 `clock.millis()`로 바꿨다.
  - `DailyLimitTracker`는 필드 초기화에서 날짜를 계산하던 것을 생성자 안으로 옮겼다. 필드 초기화는 생성자 본문보다 먼저 실행되므로 `clock`을 쓸 수 없다.
  - `TokenManager.CachedToken.expiresSoon()`을 `expiresSoon(Instant now)`로 바꿨다. record는 빈이 아니므로 시각을 인자로 받는다.
- **정적 유틸:** `RealMessageParser.parse(data)`를 `parse(data, clock)`으로 바꿨다. 호출부인 `KiwoomWebSocketClient`가 이미 가진 `clock`을 넘긴다.
- **동작 변경 없음:** 운영의 `Clock` 빈은 `systemUTC()`이고, KST 변환 방식(`clock.withZone(MarketConstants.KST)`)은 기존과 같다. 테스트의 기존 생성 지점 약 45곳에는 `Clock.systemUTC()`를 넘겨 기존 동작을 유지했다.
  - 예외: `RiskGateTest`와 `RiskGateMacroTest`의 `DailyLimitTracker`에는 `RiskGate`와 같은 테스트 시계(`ANY_CLOCK`, `clock`)를 넘겼다. 한 테스트 안에서 시각 원천을 하나로 맞추기 위해서다.

## 3. 버린 대안과 보류

- **③ JPA 엔티티 시각(보류, 사용자 결정 대기)**
  - 대상: `OrderEntity`, `IpoDealEntity`, `EventRecord`, `SignalDecisionEntity`, `DailyPerformanceEntity`, `DisclosureBlacklistEntity`
  - 엔티티는 `Clock`을 주입받을 수 없다. 생성자와 변경 메서드(`transitionTo`, `markSubmitted`, `applyFill`, `apply*`)가 시각을 인자로 받아야 한다.
  - 실측 파급: 운영 약 25곳, 테스트 약 110곳.
  - 얻는 것: `updatedAt` 기반 판정(`StaleOrderCanceller` 타임아웃)을 리플렉션 없이 테스트할 수 있다. 지금은 `StaleOrderCancellerTest`가 리플렉션으로 `updatedAt`을 강제한다.
  - 규모가 커서 이번 커밋에서는 제외했다.
- **Hibernate `@CreationTimestamp`/`@UpdateTimestamp`:** JVM 시계를 써서 테스트 고정이라는 목적에 맞지 않는다.

## 4. 변경 파일

- 운영 17개
  - ①: `risk/RiskGate`, `ipo/IpoSyncScheduler`, `risk/DisclosureBlacklist`, `macrointel/DisclosureBlacklistSyncScheduler`
  - ②: `trading/TradingService`, `trading/ReconciliationService`, `macrointel/MacroSyncScheduler`, `strategy/C3LiveStrategy`, `macrointel/EcosClient`, `kiwoom/TokenManager`, `risk/KillSwitch`, `risk/DailyLimitTracker`, `monitor/DashboardController`, `market/HolidaySyncService`, `market/MinuteBarArchiver`
  - 정적 유틸: `market/RealMessageParser`, `market/KiwoomWebSocketClient`(호출부 1줄)
- 테스트 18개(생성자와 `parse` 호출에 `Clock` 추가)
- 신규 테스트
  - `risk/DailyLimitTrackerTest` 3건: 한도, KST 자정 롤오버, UTC 자정은 경계가 아님
  - `RealMessageParserTest` +2건: UTC·KST 날짜가 다른 시각의 체결시간 변환, 체결시간 누락 시 주입 시각

## 5. 함정과 주의

- **줄바꿈:** 이 저장소는 CRLF로 커밋된 파일과 LF 파일이 섞여 있다. Git Bash `sed -i`는 CRLF를 LF로 바꿔 파일 전체를 diff로 만든다. 이번 작업에서도 `KiwoomWebSocketClient`, `RiskGateTest`, `RiskGateMacroTest`가 그렇게 바뀌어 HEAD 바이트와 비교해 복원했다. Git Bash `grep`은 줄 끝 CR을 인식하지 못하므로 판정에 쓰지 않는다.
- 새 빈에서 현재 시각이 필요하면 `Clock`을 주입받는다. 엔티티는 ③ 결정 전까지 예외다.
- 테스트용 가변 시계를 만들 때 `withZone`이 zone을 무시하면 KST 날짜 계산이 틀린다. `DailyLimitTrackerTest.MutableClock`은 zone을 반영한다(`OrderNoticeHandlerTest`의 것은 instant만 써서 무관하다).

## 6. 롤백

- 커밋을 되돌린다. 설정과 스키마 변경은 없다.

## 7. 검증 상태

- `.\gradlew.bat test` 전체 통과(2026-09-30). 결과:
  - `DailyLimitTrackerTest` 3
  - `RealMessageParserTest` 11
  - `TokenManagerTest` 2
  - `C3LiveStrategyTest` 11
  - `RiskGateTest` 11
  - `TradingServiceTest` 9
  - `ModularityTests` 통과
- 운영 코드에 주입 `Clock`을 거치지 않는 호출은 엔티티 6개 파일만 남았다(Grep으로 확인).
- 새 테스트는 수정 뒤에 작성했다. 수정 전에는 시각을 주입할 수 없어 작성 자체가 불가능했다.

## 8. 남은 일

- ③ 엔티티 시각 주입 여부 결정

## 9. 변경 이력

- 2026-09-30: 최초 작성
