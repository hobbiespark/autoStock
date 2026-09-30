# 키움 유량·인증·IP 오류 분류와 인증 실패 알림 (Phase 0.6)

- 날짜: 2026-10-01
- 계획: `upgrade-2026-10/11-execution-plan.md` 0.6, 감사 `09-audit-be.md` BE-P1-9·P1-10, 조사 `07-research-trading-market.md` §1-2·§1-8
- 사용자 확정: D-09 (a) 텔레그램 가동(알림 채널), 2026-10-01 Phase 0 착수

## 1. 목적

키움 REST 오류 가운데 현행 코드가 처리하던 것은 `[1700`(유량)과 `[8005`(토큰 무효) 두 개뿐이었다. 공식 스펙 오류코드 37종 중 운영에 직접 걸리는 나머지를 분류한다.

- **유량** `1701`(전체 총유량)·`1702`(그룹 유량): 여러 TR을 동시에 호출하면 날 수 있다. 일반 실패로 처리되고 있었다.
- **IP** `8010`(토큰 발급 IP ≠ 요청 IP): 가정용 회선에서 공인 IP가 바뀌면 난다. 허용 IP 등록은 필수이고 최대 10개다.
- **인증** `8001/8002`(App Key·Secret 검증 실패), `8030/8031`(실전·모의 구분 불일치), `8040/8050/8103`(단말기·허용 IP 인증 실패): 재시도해도 풀리지 않아 사람이 조치해야 한다. 그런데 로그에만 남아 아무도 몰랐다. 2026-09-18 `8001` 장애는 "3개월 실서버 미접속 자동 해지"가 유력 원인 후보다(추론).

작업 중 결함 두 개를 더 찾아 함께 고쳤다.
- **주문 타임아웃이 거부로 분류됐다(잠복 결함):**
  - 2026-09-22에 도입한 15초 타임아웃(`KiwoomRestClient.REQUEST_TIMEOUT`)이 `KiwoomApiException`을 던졌다. `KiwoomBrokerAdapter.placeOrder`는 이 예외를 "응답을 받은 명시 거부"로 보고 `BrokerRejectedException`으로 바꿨다.
  - 그 결과 **응답이 늦었을 뿐 실제로 접수된 주문이 REJECTED로 종결되고 대사도 하지 않는다.** 포지션 추적에서 빠지고, 다음 판단에서 중복 진입할 수 있다.
  - `aiDoc/http-timeouts.md`의 결정("주문 TR은 타임아웃이 나도… UNKNOWN으로 두고 대사로 확정")과 어긋나 있었다.
- **토큰 발급 실패 원인이 사라졌다:** 발급 실패 응답에는 토큰이 없다. 그런데 `TokenManager`가 토큰 유무를 먼저 봐서 `return_msg`(원인 코드)를 버리고 "토큰 발급 실패: 응답 없음"만 남겼다.

## 2. 결정과 근거

- **`KiwoomErrorCodes`(신규, kiwoom 패키지 내부):** 메시지의 `[숫자4자리:` 또는 `[숫자4자리]`만 코드로 본다. api-id 표기 `[ka10081]`, 다른 길이 숫자와 섞이지 않는다. 기존 `contains("[1700")`는 `[17001`도 잡았다.
  - `RATE_LIMIT` = 1700·1701·1702 → 기존 429 재시도와 같다(1.1초 간격, 최대 4회). 서버가 처리하지 않고 거절한 것이라 주문 TR도 중복이 없다.
  - `TOKEN_REJECTED` = 8005·8010 → 캐시 토큰을 버리고 새 토큰으로 1회 재시도한다(기존 8005 경로에 8010 추가). 인증 단계 거절이라 요청이 처리되지 않았다. IP가 바뀌었어도 새 IP가 허용 목록에 있으면 새 토큰으로 풀린다.
  - `AUTH_FAILURE` = 8001·8002·8010·8030·8031·8040·8050·8103 → 재시도 없이 알린다. 8010은 재발급 뒤에도 남으면(새 IP 미등록) 여기에 해당한다.
- **새 이벤트 `common.event.BrokerAuthFailure(code, message, host, at)`(추가만):**
  - `KiwoomRestClient.call`이 재시도로 풀리지 않은 인증 실패에서, `TokenManager`가 발급 실패 응답(HTTP 200 논리 오류·HTTP 오류 본문 모두)에서 발행한다.
  - 발급 실패는 `TokenManager` 한 곳에서 잡혀 REST·WS 어느 경로의 발급이든 알린다. 그 예외(`KiwoomTokenIssueException`)가 REST 호출을 거쳐 올라와도 클라이언트는 다시 알리지 않는다(중복 방지).
  - `host`로 모의(mockapi)·실전(api)을 구분한다. `message`는 `SecretMasking`을 거친다.
- **`monitor.BrokerAuthAlertListener`(신규):** 텔레그램 **CRITICAL** 알림을 보낸다. 같은 코드는 10분에 한 번만 보낸다(인증이 막히면 모든 호출이 같은 코드로 실패한다). 본문에는 코드별 원인 안내와 확인 순서(① App Key 관리의 서비스 상태·해지 여부 ② 허용 IP 목록 ③ 조치 후 재기동)를 싣는다.
- **`KiwoomTimeoutException`(신규, `KiwoomApiException` 상속):** 타임아웃은 이 예외로 던진다. `KiwoomBrokerAdapter.placeOrder`는 이 예외를 거부로 바꾸지 않고 그대로 던진다 → `TradingService`가 UNKNOWN + 대사로 확정한다. 상속을 유지해 대시보드 조회 실패는 여전히 502다.
- **`TokenManager.issue`:** `return_code`를 토큰 유무보다 먼저 보고, 실패 원문을 예외 메시지에 남긴다. HTTP 오류(`WebClientResponseException`)는 코드만 확인해 알리고 예외는 예전 그대로 던진다(기존 분류 불변).

## 3. 버린 대안과 보류

- **인증 실패 시 킬스위치 자동 작동:** 인증이 막히면 주문 자체가 나가지 않으므로 추가 차단 효과가 없다. 오히려 복구 후 사람이 따로 해제해야 하는 부담만 생긴다. 알림으로 충분하다.
- **WS LOGIN 실패 코드 알림:** WS 로그인은 REST로 발급한 토큰을 쓰므로 인증 계열 실패는 발급 단계(`TokenManager`)에서 먼저 잡힌다. 로그인 거절 자체(토큰 무효)는 기존대로 토큰 폐기 후 재연결한다.
- **취소 주문(`cancelOrder`) 타임아웃 분류:** 거부로 바뀌어도 `TradingService.requestCancel`이 예외 종류와 무관하게 UNKNOWN + 대사로 처리해 결과가 같다. 변경을 최소화하려 손대지 않았다.
- **429 외 5xx 재시도:** 주문 중복 위험으로 계속 하지 않는다(기존 결정).

## 4. 변경 파일

- 신규 운영 5개: `kiwoom/KiwoomErrorCodes`, `kiwoom/KiwoomTimeoutException`, `kiwoom/KiwoomTokenIssueException`, `common/event/BrokerAuthFailure`, `monitor/BrokerAuthAlertListener`
- 수정 운영 3개:
  - `kiwoom/KiwoomRestClient` — 분류기 사용, 인증 실패 발행, 타임아웃 예외 타입, 생성자에 `ApplicationEventPublisher`·`Clock`
  - `kiwoom/TokenManager` — 발급 실패 알림, 원인 보존, 생성자에 `ApplicationEventPublisher`
  - `execution/KiwoomBrokerAdapter` — 주문 타임아웃은 거부로 바꾸지 않음
- 테스트:
  - `KiwoomRestClientTest` 3 → 10건: 1701·1702 재시도, 8010 재발급 후 성공(알림 없음), 8010 지속 시 1회 알림, 8040 재시도 없이 알림, 비인증 오류 무알림, 발급 실패 1회 알림과 원인 코드 보존, 코드 추출 규칙
  - `KiwoomBrokerAdapterTest` +2건: 타임아웃은 결과 불명, 응답 받은 오류는 거부
  - 신규 `BrokerAuthAlertListenerTest` 4건: 긴급 알림과 안내, 10분 억제, 코드별 분리, 기본 문구
  - 생성자 변경 반영: `TokenManagerTest`, `smoke/KiwoomSmokeIT`

## 5. 함정과 주의

- 텔레그램이 꺼져 있으면(`monitor.telegram.enabled=false`, 현재 기본) 알림은 `LogOnlyNotifier`로 **로그에만** 남는다. 0.7에서 텔레그램을 켠다(사용자 작업: 봇 토큰·chat id).
- 8010이 나면 먼저 **현재 공인 IP가 허용 IP 목록에 있는지** 본다. 재발급 1회로 풀리면 알림이 없다.
- `8001`이 반복되면 서비스 해지를 의심한다. 키움은 3개월 실서버 미접속 시 매월 첫 영업일에 자동 해지하고, 모의 서버만 쓰면 해지될 수 있다(D-02: 월 1회 실서버 토큰 점검).
- 타임아웃 주문은 이제 UNKNOWN → 대사로 간다. 대사는 미체결 목록(ka10075)만 보므로, 이미 전량 체결돼 목록에 없으면 "UNKNOWN 유지·수동 확인" 경고가 남는다(체결내역 TR은 1.6).

## 6. 롤백

- 커밋을 되돌린다. 설정·스키마 변경은 없다.

## 7. 검증 상태

- 컨테이너 테스트: 위 4절 통과. ArchUnit·Modulith 경계 테스트 통과(kiwoom·monitor → common.event).
- 미검증(운영): 실제 1701·1702·8010 응답은 아직 관측되지 않았다. 메시지 형식은 공식 스펙(대괄호 코드)과 8005·1700 실측 형식이 같다는 전제다. 텔레그램 가동(0.7) 뒤 인증 실패 알림 문구를 실측한다(예: 장외에 잘못된 키로 기동해 8001 유도).
