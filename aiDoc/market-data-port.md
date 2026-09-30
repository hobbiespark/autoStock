# 시세 조회 포트 추출 (A2)

- 날짜: 2026-09-30
- 계획: `refactoring-plan.md` A2
- 사용자 확정: 진행

## 1. 목적

키움 REST 시세 응답(`Map<String,Object>`)과 키움 필드명이 market 밖까지 새고 있었다.

- `MarketQueryService`가 TR 응답 `Map`을 그대로 반환했다.
- `monitor/DashboardController`가 `cur_prc`, `sel_fpr_bid` 같은 키움 필드명을 직접 파싱했다.
- `KiwoomDailyChartService`와 `MinuteBarArchiver`도 각자 키움 필드명을 파싱했다.

관련 규칙과 결정:

- 규칙 §2.2 ACL: 외부 시스템 모델은 어댑터에서 번역한다.
- 규칙 §2.3: 포트는 안쪽이 소유한다.
- 규칙 §3: 반환 타입을 `Object`나 `Map`으로 두지 않는다.
- ARCH 4절과 설계 규칙 5·7: Kiwoom은 Adapter로만, Domain은 외부 DTO를 모른다.

## 2. 결정과 근거

- **`market/MarketDataPort`(신규):** `stockQuote`, `bestQuote`, `dailyCandles`, `minuteBars`. 반환 타입은 우리 모델이다.
  - 중첩 record `StockQuote`, `BestQuote`, `MinuteBar`와 기존 `common.event.Candle`
  - `execution/BrokerPort`와 같은 구조이고, 중첩 record는 `EcosClient.Observation` 등 이 리포의 관례를 따랐다.
- **`market/KiwoomMarketDataAdapter`(신규):** `MarketQueryService`를 대체한다.
  - TR 호출(요청 계약 그대로), `@Cacheable`(캐시 이름·TTL 그대로), 흩어져 있던 필드 파싱을 한 곳에 모았다(SSOT).
- **어댑터 위치는 market 모듈 안이다.** kiwoom 모듈에 두면 kiwoom → market(포트)과 market → kiwoom(WS 클라이언트)이 서로 참조해 순환이 된다. Modulith가 막는다. `execution/KiwoomBrokerAdapter`도 같은 이유로 execution 안에 있다.
- **파싱 동작은 그대로다.**
  - 대시보드 시세: 부호를 벗긴 `abs()`, 0 이하·빈 값은 null
  - 일봉: `toBigDecimal`만 쓰고 `abs()`는 쓰지 않는다(기존과 같다)
  - 분봉: `abs()`와 `toLongOrZero`
  - 일봉 응답을 뒤집는 것(최신순 → 오름차순)은 키움 응답 특성이라 어댑터로 옮겼다.
- **`KiwoomDailyChartService`:** 공개 API(`fetchDaily`)와 이름은 유지하고 포트에 위임한다. 이름을 바꾸면 `C3LiveStrategy`와 그 테스트 스텁(11곳), 스모크 테스트까지 바뀐다. 이름 정리는 별도로 하는 것이 낫다.
- **`MinuteBarArchiver`:** 포트의 `MinuteBar.time`을 `yyyyMMddHHmmss`로 다시 포맷해 기존 CSV 첫 컬럼과 비교하고 기록한다. 적재 파일 형식은 바뀌지 않았다.
- **`DashboardController`:** 포트 값을 `QuoteView`로 옮기기만 한다. 조회 실패 시 빈 값으로 응답하는 기존 동작은 `StockQuote.EMPTY`, `BestQuote.EMPTY`로 유지했다.
- **ArchUnit 규칙 추가:** market 안에서 kiwoom을 참조할 수 있는 클래스는 `KiwoomMarketDataAdapter`와 `KiwoomWebSocketClient`뿐이다(`ArchitectureRulesTest`, A3 남은 일 해소).

## 3. 버린 대안

- **어댑터를 kiwoom 모듈에 두기:** 모듈 순환이 생긴다(위 2절).
- **`KiwoomWebSocketClient`를 `market.adapter` 하위 패키지로 옮기기:** package-private 협력 클래스(`RealMessageParser`)와 테스트 패키지까지 바뀐다. 이름 기반 허용 목록으로 규칙을 충분히 표현할 수 있다.
- **`KiwoomDailyChartService` 개명:** 위 2절.

## 4. 변경 파일

- 신규: `market/MarketDataPort.java`, `market/KiwoomMarketDataAdapter.java`
- 삭제: `market/MarketQueryService.java`
- 수정
  - `market/KiwoomDailyChartService.java`: 포트 위임
  - `market/MinuteBarArchiver.java`: 포트 사용, Javadoc 갱신
  - `monitor/DashboardController.java`: `firstPrice`와 `firstText` 제거, 포트 사용
  - `config/CacheConfig.java`: 주석의 참조처 갱신
- 테스트
  - 신규 `market/KiwoomMarketDataAdapterTest`(6): TR 요청 계약, 부호·빈 값 처리, 일봉 정렬, 분봉 해석 불가 행 건너뛰기
  - 신규 `market/MinuteBarArchiverTest`(1): 오늘 행만, 시간순, 재실행 멱등, CSV 형식
  - 신규 `monitor/DashboardControllerQuoteTest`(2): 매핑, 기본정보 실패 시 폴백
  - `ArchitectureRulesTest`(+1): market 규칙
  - `smoke/KiwoomSmokeIT`: 생성 코드 갱신

## 5. 함정과 주의

- 새 시세 조회가 필요하면 `MarketDataPort`에 메서드를 추가하고 어댑터에서 번역한다. 사용처에 키움 필드명을 두지 않는다. ArchUnit이 market 안쪽은 막지만, monitor 등 다른 모듈이 `Map`을 다시 받게 되는 설계는 리뷰에서 잡아야 한다.
- 캐시는 이제 원시 `Map`이 아니라 번역된 record를 담는다. 캐시 이름과 TTL은 같다. 일봉 캐시 키는 `symbol-LocalDate`(ISO 형식)로 바뀌었다. 재기동하면 비워지는 로컬 캐시라 호환 문제는 없다.
- 주문·잔고 조회를 이 어댑터에 추가하지 않는다(캐시된 낡은 잔고 위험). 기존 경고를 어댑터 Javadoc으로 옮겼다.

## 6. 롤백

- 커밋을 되돌린다. 설정과 스키마 변경은 없다.

## 7. 검증 상태

- `.\gradlew.bat test` 전체 통과(2026-09-30). 결과:
  - `ArchitectureRulesTest` 8
  - `KiwoomMarketDataAdapterTest` 6
  - `MinuteBarArchiverTest` 1
  - `DashboardControllerQuoteTest` 2
  - `C3LiveStrategyTest` 11
  - `ModularityTests` 통과
- market 규칙 Red 확인(실측): market에 kiwoom을 참조하는 임시 클래스를 넣어 실행했더니 실패했다. 임시 파일은 삭제했다.
- **미검증**
  - 실서버 시세 응답에서 대시보드 시세·일봉·분봉이 이전과 같은지. `KiwoomSmokeIT`는 키가 있는 환경에서만 돈다.
  - 재기동 후 15:45 분봉 적재 결과 CSV를 이전 행과 비교해 확인할 것.
- 새 테스트는 구현과 함께 작성했다. 옛 코드에는 같은 API가 없어 수정 전 실패(Red)는 확인할 수 없었다.

## 8. 남은 일

- `KiwoomDailyChartService` 이름 정리(선택)

## 9. 변경 이력

- 2026-09-30: 최초 작성
