# 외부 호출 타임아웃 (R1)

- 날짜: 2026-09-29
- 계획: `refactoring-plan.md` R1

## 1. 목적

Reactor Netty는 기본 응답 타임아웃이 없다. 그래서 키움 REST를 제외한 WebClient 호출은 모두 `.block()`에서 무한 대기할 수 있었다.
커밋 `8e86557`이 막은 2026-09-22 절전 복귀 사고와 같은 계열의 잔여 결함이다.

가장 위험한 곳은 `kiwoom/TokenManager`다.

- `issueLock`(ReentrantLock)을 잡은 채로 `/oauth2/token`을 `.block()`한다.
- 이 호출이 멈추면 락이 풀리지 않는다. 그러면 `accessToken()`을 부르는 모든 키움 REST 호출(주문 포함)과 WS 연결이 함께 멈춘다.

## 2. 결정과 근거

- **Boot 내장 속성을 쓴다(규칙 §1 "바퀴를 다시 만들지 않는다", §2.1 "타임아웃 명시").**
  - `application.yml`에 `spring.http.reactiveclient.connect-timeout: 5s`와 `read-timeout: 20s`를 추가했다.
  - Boot가 만드는 `WebClient.Builder`를 주입받는 모든 클라이언트에 자동 적용된다: TokenManager, KiwoomRestClient, ECOS, FRED, HolidaySync, TelegramNotifier, TelegramCommandPoller.
  - 새 설정 클래스는 만들지 않았다.
  - Reactor Netty에서 read-timeout은 `HttpClient.responseTimeout`으로 적용된다. 요청을 보낸 뒤 응답 헤더와 본문이 오기까지 기다리는 상한이다.
- **DART 두 클라이언트는 같은 설정으로 커넥터를 만든다(SSOT).**
  - `ipo/DartClient`와 `macrointel/MajorDisclosureDartClient`는 TLS 문제로 `clientConnector`를 직접 교체한다. 그래서 자동구성 커넥터의 타임아웃이 빠져 있었다.
  - 생성자에서 `ClientHttpConnectorSettings`(Boot 빈, 위 yml 값)를 받아 `ClientHttpConnectorBuilder.reactor().withHttpClientCustomizer(secure...).build(settings)`로 만든다.
  - 적용 순서는 Boot 3.5.16 `ReactorHttpClientBuilder.build`의 바이트코드로 확인했다: 팩토리 → 기본값 → 설정(타임아웃) → customizer. 따라서 TLS customizer가 타임아웃을 지우지 않는다.
  - 두 클래스의 의도적 중복(MajorDisclosureDartClient Javadoc의 결정)은 유지했다(§0 유지보수).
- **키움 REST의 per-call 15초는 유지한다.**
  - `KiwoomRestClient.REQUEST_TIMEOUT`이 공통 20초보다 짧아 먼저 걸린다.
  - 주문 TR은 타임아웃이 나도 재시도하지 않는다. 결과를 모르는 주문은 UNKNOWN으로 두고 대사로 확정한다(ARCH §6). 이 결정은 바꾸지 않았다.
- **WebSocket 연결 상한을 명시한다.**
  - `KiwoomWebSocketClient.CONNECT_PROPERTIES`에 `org.apache.tomcat.websocket.IO_TIMEOUT_MS = 5000`을 두었다.
  - 값은 tomcat-embed-websocket 10.1.55의 기본값과 같다. 동작은 바뀌지 않고, 암묵적 기본값을 명시만 한 것이다.
  - 이 상한이 없으면 연결 future가 끝나지 않는다. 그러면 `connecting` 플래그가 true로 남아 재연결이 영구히 멈춘다.
- **재시도는 추가하지 않는다(§2.6 "재시도를 겹쳐 걸지 않는다").**
  - ECOS, FRED, DART, 공휴일은 매시 5분 따라잡기 크론이 다음 회차에 다시 수집한다.
  - Telegram 폴러는 5초 주기로 다시 돈다.

## 3. 버린 대안

- **호출마다 `.timeout(Duration)`**(KiwoomRestClient 방식): 8곳에 같은 값을 흩뿌리게 된다(SSOT 위반). Boot 속성으로 한 곳에서 해결된다.
- **직접 만든 `WebClientCustomizer`와 전용 `@ConfigurationProperties`**: Boot 3.5가 같은 기능을 속성으로 제공한다.
- **`ReactorNettyHttpClientMapper`로 DART TLS를 전역 적용**: 키움과 Telegram의 TLS까지 바뀐다.
- **`resilience4j-retry`로 조회 재시도**: 스케줄 따라잡기와 겹친다.

## 4. 변경 파일

- `app/src/main/resources/application.yml`: `spring.http.reactiveclient.connect-timeout`, `read-timeout`
- `app/src/main/java/com/autostock/ipo/DartClient.java`: 생성자에 `ClientHttpConnectorSettings` 추가, `jdkCipherConnector(settings)`
- `app/src/main/java/com/autostock/macrointel/MajorDisclosureDartClient.java`: 위와 같다.
- `app/src/main/java/com/autostock/market/KiwoomWebSocketClient.java`: `CONNECT_PROPERTIES`, `client.setUserProperties(...)`
- `app/src/main/java/com/autostock/macrointel/FredClient.java`: 해소된 TODO 주석 갱신
- `app/src/test/java/com/autostock/config/HttpClientTimeoutTest.java`(신규): 테스트 3건
  - yml 값 고정
  - 자동구성 WebClient가 응답 없는 서버에서 읽기 타임아웃으로 실패
  - customizer를 붙인 커넥터에도 설정의 타임아웃이 적용
- 생성자 변경 반영: `ipo/DartClientTest`, `macrointel/MajorDisclosureDartClientTest`, `smoke/DartIpoSmokeIT`, `smoke/DartDisclosureSmokeIT`

## 5. 함정과 주의

- 20초라는 값은 **추론**이다. `docs/measured/`에는 응답 본문만 있고 지연 실측은 없다. DART와 공공데이터포털이 느린 날에 타임아웃 로그가 보이면 늘린다(yml 한 줄).
- 새 WebClient 클라이언트를 만들 때 `WebClient.builder()`(정적)를 쓰면 이 설정이 빠진다. **Boot의 `WebClient.Builder`를 주입받아야 한다.** 커넥터를 교체하면 DART처럼 `ClientHttpConnectorSettings`로 빌드한다.
- TokenManager 발급이 타임아웃되면 `KiwoomApiException`이 아니라 WebClient 예외가 호출자에게 간다. 기존 호출자는 `RuntimeException` 단위로 처리한다(**코드 확인은 부분적**).
- TLS 핸드셰이크 상한은 Reactor Netty 기본값(10초)이다. connect-timeout(TCP)과 read-timeout 어디에도 포함되지 않는다.

## 6. 롤백

- yml의 `spring.http.reactiveclient` 블록을 지우면 타임아웃 적용 전으로 돌아간다. DART 생성자 변경은 남아도 무해하다(설정이 기본값이 된다).
- 전체 롤백은 커밋을 되돌린다.

## 7. 검증 상태

- `.\gradlew.bat test` 전체 통과(2026-09-29). `ModularityTests` 통과.
- 새 테스트를 실행해 yml 값 테스트가 수정 전 실패하는 것(Red)을 확인했다.
- 응답 없는 로컬 서버에 대해 300ms 설정으로 5초 안에 실패하는 것을 확인했다.
- 코드로 확인한 것:
  - `ClientHttpConnectorAutoConfiguration`이 `AutoConfiguration.imports`에 등록돼 있다.
  - 조건(Reactor Netty 감지)을 충족한다.
- **미검증**
  - 실제 앱 기동 시 DART 클라이언트에 `ClientHttpConnectorSettings` 빈이 주입되는지. 앱은 재기동하지 않았다.
  - 실서버 대상 DART, ECOS, FRED 정상 호출과 TLS 협상 유지. 스모크 `DartIpoSmokeIT`와 `DartDisclosureSmokeIT`는 키가 있는 환경에서만 돈다.
- 다음 재기동 후 확인할 것
  1. 기동 로그에 빈 생성 오류가 없는지
  2. 08:20 IPO 동기화와 08:30 매크로 동기화 로그에서 정상 수집되는지
  3. WS 연결과 토큰 발급이 정상인지

## 8. 남은 일

- 위 7절의 기동 후 실측.
- 타임아웃 발생 빈도가 생기면 값을 조정한다.

## 9. 변경 이력

- 2026-09-29: 최초 작성
