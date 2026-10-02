# 관측성 — 헬스·메트릭·주문·취소 건수 기록 (Phase 1.7)

- 날짜: 2026-10-02(금) 장마감 후
- 계획: `upgrade-2026-10/11-execution-plan.md` 1.7

## 1. 목적

장애를 로그를 뒤져야만 알 수 있었다. WS가 끊겼는지, 토큰 발급이 막혔는지, 킬스위치가 켜졌는지, 결과 불명 주문이 쌓였는지를 한 번에 보는 창이 없었다. 또 금융위 고빈도 매매 규율 검토 대비로 하루 주문·취소 건수(OTR)를 남겨 둘 필요가 있었다(`upgrade-2026-10/07` §4-1).

## 2. 결정과 근거

### 헬스(`GET http://127.0.0.1:8080/actuator/health`)

세부가 보이도록 `management.endpoint.health.show-details/show-components: always`를 켰다(루프백 바인딩이라 외부에 나가지 않는다).

| 이름 | 위치 | 판정 |
|---|---|---|
| `kiwoomWs` | market | 장중 세션(ACTIVE)인데 미연결이거나 LOGIN 전이면 **DOWN**. 장외(STANDBY)·WS 끔은 UP. 세부: session, connected, loggedIn, 마지막 메시지(PING 포함) 뒤 경과 초 |
| `kiwoomAuth` | kiwoom | 마지막 토큰 발급 성공 UP(발급·만료 시각), 실패 **DOWN**(시도 시각, 사유 코드 — `8030` 같은 인증 코드, `HTTP 503`, `return_code n`), 발급 전 UNKNOWN. 토큰 값은 싣지 않는다 |
| `killSwitch` | risk | **늘 UP** + 세부 engaged·reason. 킬스위치는 장애가 아니라 안전장치가 일하는 상태라 전체 헬스를 흔들지 않는다(계획과 같음) |

- `TokenManager`가 마지막 발급 결과(`IssueStatus`)를 보관하고, `KillSwitch`가 작동 사유를 보관한다(읽기 전용 조회 추가).
- `KiwoomWebSocketClient`에 읽기 전용 조회(`isEnabled`·`isConnected`·`isLoggedIn`·`lastMessageAt`)를 더했다. 상태를 바꾸지 않는다.

### 메트릭(`GET /actuator/metrics/<이름>`)

| 이름 | 종류 | 위치 | 뜻 |
|---|---|---|---|
| `risk.killswitch.engaged` | 게이지 | risk `RiskMetrics` | 1 작동 / 0 해제 |
| `orders.unknown.count` | 게이지 | trading `TradingMetrics` | UNKNOWN 주문 수(읽을 때 DB에서 셈) |
| `orders.cancel_requested.count` | 게이지 | trading `TradingMetrics` | CANCEL_REQUESTED 주문 수 |
| `market.ws.last_message_age.seconds` | 게이지 | market `MarketMetrics` | 마지막 WS 메시지 뒤 경과 초(받은 적 없으면 NaN). 서버 PING이 약 10초 간격이라 장중에 수십 초를 넘으면 이상 |
| `kiwoom.ratelimit.retry{code}` | 카운터 | kiwoom `KiwoomRestClient` | 유량 재시도 — code = 429·1700·1701·1702 |
| `kiwoom.auth.failure{code}` | 카운터 | kiwoom `KiwoomMetrics` | `BrokerAuthFailure` 이벤트 수(토큰 발급·REST 두 경로를 한 곳에서 셈) |
| `reconcile.failure{kind}` | 카운터 | trading | 대사 실패 — kind = orders(`ReconciliationService`)·positions(`PositionReconciler`, 1.5) |
| `reconcile.position.mismatch` | 게이지 | trading | 1.5 참고 |
| `audit.write.failure` | 카운터 | audit | 1.8 참고 |

- 게이지는 모듈마다 `MeterBinder` 빈으로 둔다 — 값의 주인이 있는 모듈이 노출한다.
- Prometheus·Grafana는 D-13(보류)이라 수집기는 없다. 지금은 필요할 때 엔드포인트로 본다.

### 주문·취소 건수(OTR 관측)

- **V14:** `daily_performance`에 `submitted_count`·`cancelled_count`(INT, 기본 0).
- 15:50 스냅샷(`DailyPerformanceService.saveSnapshot`)이 그날(KST) 접수된 주문(`orders.submitted_at`)에서 센다.
  - `submitted_count`: CREATED·VALIDATED(전송 전)를 뺀 모든 상태 — 브로커로 보냈거나 보내려던 주문.
  - `cancelled_count`: 현재 상태가 CANCELLED·CANCEL_REQUESTED.
- 이미 있는 날의 행도 V14가 같은 규칙으로 채운다(주문 테이블은 운영 시작부터 있다).
- 목표치 없이 기록만 한다. 화면 노출은 하지 않았다(필요하면 성과 API에 더한다).

## 3. 버린 대안과 보류

- **킬스위치 작동을 DOWN으로:** 전체 헬스가 DOWN이 되어 다른 이상(WS 단절 등)과 구분이 안 된다. 계획대로 UP + 세부.
- **이벤트 저장소에서 취소 건수 계산:** 취소 요청 뒤 체결로 끝난 주문까지 셀 수 있지만 이벤트 형식 해석이 필요하다. 지금은 현재 상태 기준으로 세고 한계를 적었다(5절).
- **게이지 값 캐시:** 읽는 쪽이 사람뿐이라 매번 DB에서 센다.

## 4. 변경 파일

- 운영
  - kiwoom: `TokenManager`(발급 결과 보관, KST 상수는 1.9 BE-P2-16), `KiwoomRestClient`(유량 재시도 카운터), `KiwoomAuthHealthIndicator`·`KiwoomMetrics`(신규)
  - market: `KiwoomWebSocketClient`(읽기 조회, 마지막 메시지 시각, 종료·전송 오류 로그는 1.9 BE-P2-20), `KiwoomWsHealthIndicator`·`MarketMetrics`(신규)
  - risk: `KillSwitch`(작동 사유 보관·조회), `KillSwitchHealthIndicator`·`RiskMetrics`(신규)
  - trading: `OrderRepository`(`countByStatus`, 구간·상태별 수), `ReconciliationService`(실패 카운터, 생성자에 `MeterRegistry`), `TradingMetrics`(신규)
  - monitor: `DailyPerformanceEntity`(두 컬럼), `DailyPerformanceService`(건수 기록, 생성자에 `OrderRepository`)
  - `db/migration/V14__daily_performance_otr.sql`(신규), `application.yml`(헬스 세부)
- 테스트
  - `KiwoomWsHealthIndicatorTest`(신규 4건): WS 끔 UP, 장외 UP, 장중 미연결·LOGIN 전 DOWN → LOGIN 후 UP → 끊김 DOWN, 마지막 메시지 경과 게이지
  - `TokenManagerTest` +4: 발급 전 UNKNOWN, 성공 UP(토큰 값 없음), 인증 코드 실패 DOWN(8030), 그 밖 실패 `return_code n`
  - `KiwoomRestClientTest` +1·보강: 유량 재시도 카운터(1701·1702), 인증 실패 카운터
  - `KillSwitchHealthIndicatorTest`(신규): 늘 UP, 사유, 게이지 0/1
  - `TradingMetricsTest`(신규): 상태별 게이지가 읽을 때마다 다시 셈
  - `OrderRepositoryDbTest` +1(실제 PostgreSQL): KST 하루 경계·상태별 수
  - `DailyPerformanceServiceTest` +1: KST 하루 구간으로 건수 기록
  - `PositionReconcilerTest`·`ReconciliationServiceTest` — 실패 카운터·생성자 인자

## 5. 함정과 주의

- **취소 건수 한계:** 취소 요청 뒤 체결로 끝난 주문(CANCEL_REQUESTED → FILLED)은 현재 상태가 FILLED라 `cancelled_count`에 들지 않는다.
- `kiwoomWs`는 08:30 장 대응 시작 직후 연결·LOGIN까지 몇 초 DOWN일 수 있다(정상).
- 지금 헬스를 보는 곳은 없다(스크립트·외부 감시 모두 미사용 — 확인). 외부 감시가 헬스를 보게 되면 `kiwoomWs` DOWN이 곧 경보가 된다.

## 6. 롤백

- 커밋을 되돌린다. V14 두 컬럼은 남겨도 무해하다(기본 0).

## 7. 검증 상태

- 컨테이너 전체 테스트 통과(`aiDoc/phase1-offhours-2026-10-02.md`).
- 미검증(재기동 후): 장외 `GET /actuator/health` → `kiwoomWs` UP(STANDBY)·`kiwoomAuth` UP 또는 UNKNOWN·`killSwitch` UP. 10/6 장중 `kiwoomWs` UP(connected·loggedIn true, 경과 초 10 안팎). 10/6 15:50 뒤 `SELECT trade_date, order_count, submitted_count, cancelled_count FROM daily_performance ORDER BY trade_date DESC LIMIT 5;`
