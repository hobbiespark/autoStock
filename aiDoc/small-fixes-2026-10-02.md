# 소규모 정리 + 오늘 발견한 알림 결함 2건 (Phase 1.9, 2026-10-02)

- 날짜: 2026-10-02(금) 장마감 후
- 계획: `upgrade-2026-10/11-execution-plan.md` 1.9 표 7항목 + 오늘 운영 로그에서 찾은 결함 2건(공시 요약 종료 유실, 공모주 청약 알림 중복)

## 1. 목적과 결정 — 1.9 표

| 근거 | 변경 | 비고 |
|---|---|---|
| BE-P2-8 | `DailyReportScheduler`: SIM이거나 휴장일이면 잔고(kt00018)를 조회하지 않고 `계좌 평가액: SIM — 잔고 조회 생략`·`휴장일 — 잔고 조회 생략` 한 줄 | 휴장일 모의 서버 점검 실패 로그 소음 제거. 리포트·성과 스냅샷 자체는 예전처럼 남긴다(휴장일 리포트를 아예 건너뛸지는 보류 — 5절) |
| BE-P2-9 | `GET /api/dashboard/quote/{symbol}`은 `StockCode.PATTERN`, `POST /api/dashboard/orders/{clientOrderId}/cancel`은 `ClientOrderId.PATTERN`으로 경로 변수 형식 검증 → 400 | Spring MVC 내장 메서드 검증(`@Pattern`). `ClientOrderId`에 형식 상수 `PATTERN`을 공개(파싱 정규식과 같은 원천). 형식이 틀리면 브로커 호출·취소 이벤트까지 가지 않는다 |
| (BE-P2-9 따라) | `ApiExceptionHandler.handleExceptionInternal`: 상위 클래스가 본문을 만든 **뒤에** `code`를 채운다 | 본문 없이 들어오는 예외(경로 변수 검증 등)는 `code`가 빠졌다. 이제 모든 오류 응답에 `code`가 있다 |
| (BE-P2-9 따라) | 경로 변수 검증 실패(`HandlerMethodValidationException`)는 본문 검증과 같게 `code: VALIDATION_FAILED`, `detail: 입력값을 확인하세요.`, `errors[{field, message}]` | 실제 jar 실행 점검에서 영어 detail `Validation failure`와 `code: MALFORMED_REQUEST`로 나와 고쳤다. 화면 `describeError`가 `입력값을 확인하세요. — symbol: …`로 보여 준다. 거부된 값은 싣지 않는다. 반환값 검증 실패(서버 잘못, 500)는 상위 클래스 기본 처리 |
| BE-P2-11 | `GET /api/orders?days=N`: "지금부터 N×24시간"이 아니라 **오늘(KST) 포함 N개 날짜, KST 자정부터** | 화면 "최근 1일" = 오늘. 테스트 기대값을 날짜 경계로 바꿨다 |
| BE-P2-13 | `risk.stop-loss-pct`·`take-profit-pct`: Javadoc·yml 주석에 "미사용 — ADR-7 E4 사용 예정, 바꿔도 동작 불변" | 삭제하지 않음(계획) |
| BE-P2-16 | `TokenManager`의 `ZoneId.of("Asia/Seoul")` → `MarketConstants.KST` | 1.7 작업과 같은 파일에서 처리 |
| BE-P2-17 | `DailyLimitTracker` 롤오버 원자화 | 1.2에서 처리(`aiDoc/open-order-aware-risk.md`) |
| BE-P2-20 | `KiwoomWebSocketClient`에 `afterConnectionClosed`(코드·사유, 정상 종료 1000은 INFO·그 밖 WARN)·`handleTransportError`(WARN) 로그 | **동작 불변** — 상태 플래그를 건드리지 않는다(늦게 온 옛 세션 종료 콜백이 새 세션 로그인 상태를 지우지 않게) |

## 2. 오늘 발견한 결함 2건

### A. 공시 블랙리스트 요약이 종료 때 유실 (18:56 재기동 때 13건)

- **증상(로그 확인):** `DisclosureBlacklistListener.flushOnShutdown`(`@PreDestroy`)에서 텔레그램 발송이 `RejectedExecutionException: event executor terminated`로 실패했다.
- **원인:** 종료 순서. Spring은 빈 소멸(`@PreDestroy`) **전에** 수명주기 빈을 멈추는데, Reactor Netty 자원(`ReactorResourceFactory`, SmartLifecycle 단계 **0** — spring-web 6.2.19 확인)이 그때 닫힌다. 그 뒤의 `@PreDestroy`는 WebClient를 쓸 수 없다.
- **수정:** 요약 비우기를 `SmartLifecycle.stop()`(단계 **2048**)으로 옮겼다. 종료는 높은 단계부터라 스케줄러(기본 단계)·웹 서버가 먼저 멈추고, 요약을 보낸 뒤 Reactor 자원(0)이 닫힌다.
- **같은 원인 예방:** 1.3의 `NotificationDispatcher`도 `@PreDestroy` 대신 단계 **1024**에서 대기열을 비운다(요약 2048 → 발송기 1024 → Reactor 0).

### B. 공모주 청약 알림 중복 (회사 단위)

- **증상:** 같은 회사 청약 D-1·당일 알림이 여러 번 나갔다.
  - 한 회사가 신고서를 여러 번 내면(정정·발행조건확정 — 예: 진코스텍 rcept_no 2건) 딜이 rcept_no마다 따로 있어 **딜 수만큼** 나갔다.
  - 마지막 수집일이 메모리에만 있어 **재기동할 때마다** 그날 알림을 다시 보냈다(감사 BE-P2-6 — 오늘처럼 하루 9번 재기동하면 9번).
- **수정(V16):** `ipo_deals`에 `last_alert_phase`·`last_alert_date`.
  - 알림은 회사(corp_code) 단위로 한 번. 그날 알림 단계가 있는 딜 중 **가장 최근 신고서(rcept_no 최대)** 내용으로 보낸다(확정 공모가 등).
  - 보내면 그 회사의 **모든 딜**에 (단계, 날짜)를 남기고, 같은 회사·단계·날짜면 다시 보내지 않는다. 다음 날 START는 따로 보낸다.
  - 저장은 기존 재계산 저장 루프가 한다(낙관적 잠금 충돌로 한 딜 저장이 빠져도 같은 회사의 다른 딜 표시로 막힌다).

## 3. 버린 대안과 보류

- **휴장일에 일일 리포트·성과 스냅샷 자체를 건너뛰기:** 성과 표(FE-2)에 휴장일 0건 행이 생기는 문제는 남아 있다. 계획(BE-P2-8)은 잔고 조회만이라 이번엔 잔고만 뺐다. 필요하면 별도 작업으로 묻는다.
- **종료 비우기를 `ContextClosedEvent`로:** 가장 이르지만 스케줄러가 아직 돌아 비운 뒤 알림이 또 들어온다. 수명주기 단계가 순서를 정확히 표현한다.
- **공모주 알림 표시를 메모리에:** 재기동 중복(BE-P2-6)을 못 막는다.

## 4. 변경 파일

- 운영
  - `monitor/DailyReportScheduler`(생성자에 `MarketCalendarService`·`TradingProperties`), `monitor/DashboardController`(경로 변수 `@Pattern`), `monitor/ApiExceptionHandler`(code 보강, 경로 변수 검증 → VALIDATION_FAILED), `monitor/OrderHistoryController`(KST 날짜 경계)
  - `common/util/ClientOrderId`(`PATTERN` 공개), `risk/RiskProperties`(설명), `application.yml`(주석)
  - `kiwoom/TokenManager`(KST 상수), `market/KiwoomWebSocketClient`(종료·전송 오류 로그)
  - `monitor/DisclosureBlacklistListener`(SmartLifecycle 2048), `monitor/NotificationDispatcher`(SmartLifecycle 1024)
  - `ipo/IpoSyncScheduler`(회사 단위 알림·표시), `ipo/IpoDealEntity`(표시 2필드), `db/migration/V16__ipo_alert_marker.sql`(신규)
- 테스트
  - `DailyReportSchedulerTest` +2(SIM·휴장일 잔고 생략, 기존 테스트는 LIVE로 조립)
  - `RequestValidationTest` +2(종목코드·주문 ID 형식 400 — `code: VALIDATION_FAILED`와 `errors[0].field` 확인, 형식 맞으면 취소 요청 발행)
  - `OrderHistoryControllerTest` 기대값 3건 변경 + 1건(UTC로 어제인 KST 오전 — 오늘 자정 경계)
  - `DisclosureBlacklistListenerTest`(종료 = `stop()`, 단계 순서 2048 > 1024 > 0)
  - `ShutdownOrderTest` 신규 — 실제 Spring 컨텍스트를 닫아, 남은 공시 요약과 알림 대기열이 단계 0 수명주기 빈(Reactor 자원 대역)이 멈추기 **전에** 나가는지 본다
  - `IpoSyncSchedulerTest` +2(같은 회사 신고서 2건 → 최근 신고서로 1회·모든 딜 표시, 재기동해도 같은 날 다시 안 보내고 다음 날 START는 보냄)
  - `SchemaAndTimeZoneDbTest` — 최신 버전 16

## 5. 함정과 주의

- `GET /api/orders?days=7`의 결과 범위가 조금 달라진다(지금 시각 기준 → 날짜 기준). 화면 숫자가 바뀔 수 있다(의도).
- `errors[].message`는 Bean Validation 기본 문구라 언어가 요청·서버 로캘을 따른다(컨테이너 curl 점검에서는 영어 `must match "[0-9A-Z]{6}"`). 본문 검증 오류와 같은 방식이다.
- 경로 변수 검증은 `@Validated` 없이 Spring MVC 내장 메서드 검증으로 동작한다. 컨트롤러에 `@Validated`를 붙이면 AOP 검증이 끼어 500(ConstraintViolationException)이 될 수 있으니 붙이지 않는다.
- V16은 적용 뒤 고치지 않는다(Flyway 체크섬). 기존 딜의 표시는 비어 있어, 배포 당일 이미 보낸 알림이 한 번 더 나갈 수 있다(10/2 저녁 배포라 해당 없음 — 10/5 휴장, 다음 청약 알림은 배포 뒤).

## 6. 롤백

- 커밋을 되돌린다. V16 두 컬럼은 남겨도 무해하다(널 허용).

## 7. 검증 상태

- 컨테이너 전체 테스트 통과(`aiDoc/phase1-offhours-2026-10-02.md`).
- 종료 순서: 실제 Spring 컨텍스트 테스트(`ShutdownOrderTest`)로 확인했다. 실제 Reactor Netty·텔레그램 발송은 재기동 때 본다.
- 경로 변수 400: 실제 jar(새 DB)에서 `quote/ABC`·`quote/59 30`·`orders/abc/cancel` 모두 400, `code: VALIDATION_FAILED`, `errors[0].field`가 `symbol`·`clientOrderId`.
- 미검증(재기동 때): 종료 로그에 `event executor terminated`가 없고 대기 중이던 공시 요약이 텔레그램으로 오는지(대기열이 비어 있으면 아무것도 안 보낸다). 다음 청약 D-1 아침에 회사당 1건만 오는지, 그날 재기동해도 다시 오지 않는지.
