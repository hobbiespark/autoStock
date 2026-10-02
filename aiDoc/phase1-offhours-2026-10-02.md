# Phase 1 당겨 하기 — 장마감 후 묶음 (2026-10-02 금)

- 지시: 2026-10-02 19:39 "현재 장마감 시간이고 금요일 입니다. 계획상 장마감이여도 할 수 있는걸 하자고요", 19:41 D-03·D-07 "권고 로 적용 함"
- 계획: `upgrade-2026-10/11-execution-plan.md` 4절 Phase 1(원래 10/19~11/13). 장중이 필요한 1.6(UNKNOWN 해소 TR 실측)과 사용자가 함께 해야 하는 1.10(복구 훈련)은 빼고 당겼다.
- 배포: 재기동 **한 번**(장외)으로 V13~V16과 코드가 함께 들어간다. 다음 장은 **10/6(화) 08:30**(10/5 개천절 대체 휴장).

## 1. 무엇이 바뀌었나

| 작업 | 한 줄 요약 | 문서 |
|---|---|---|
| 1.1 | C3 판단 주기를 21**거래일**로(백테스트 21봉과 같게), 마지막 판단일 DB 저장(V13) — 재기동해도 재판단 안 함 | `c3-trading-day-cycle.md` |
| 1.2 | RiskGate가 미체결 매수를 안다 — 같은 종목 중복 매수 거부, 동시 보유 한도에 포함. 주문 슬롯(날짜+일련번호) 원자 발급 | `open-order-aware-risk.md` |
| 1.3 | 체결·주문·킬스위치 알림을 전용 작업자 대기열로 — WS 수신·주문 경로를 막지 않음 | `async-notification.md` |
| 1.4 | 거시 지표(VIX·원/달러) 7일 넘게 못 받으면 매수 금지(보수 모드) + 하루 1회 경고, 지표 ID 단일화 | `macro-staleness.md` |
| 1.5 | 잔고 보유 타입(`BrokerHolding`), 잔고↔장부 5분 대사(알림만, **기본 꺼짐**) | `position-reconcile.md` |
| 1.7 | 헬스 3종(kiwoomWs·kiwoomAuth·killSwitch), 게이지·카운터, 일별 주문·취소 건수(V14) | `observability.md` |
| 1.8 | 감사 문구 정정·저장 실패 카운터·타입별 스키마 버전, `event_publication` 3컬럼(V15, D-07) | `audit-reliability.md` |
| 1.9 | 휴장일·SIM 잔고 조회 생략, 경로 변수 400, 주문 이력 KST 날짜, WS 종료 로그 등 + 오늘 결함 2건(공시 요약 종료 유실, 공모주 알림 중복 — V16) | `small-fixes-2026-10-02.md` |

## 2. 새 설정 키 (기본값 = 의도한 운영값)

| 키 | 기본값 | 뜻 |
|---|---|---|
| `macrointel.max-staleness-days` | `7` | 판정 지표를 이보다 오래 못 받으면 매수 금지 |
| `trading.position-reconcile.enabled` | `false` | 잔고↔장부 대사 — 장외 검증 후 켠다(사용자 결정) |
| `management.endpoint.health.show-details/show-components` | `always` | 헬스 세부 표시(루프백 전용) |

## 3. 마이그레이션 (적용 순서, 모두 추가만)

| 버전 | 내용 | 되돌리기 |
|---|---|---|
| V13 | `strategy_state`(C3 마지막 판단일) | 표 남겨도 무해 |
| V14 | `daily_performance.submitted_count·cancelled_count`(기존 날 값 채움) | 컬럼 남겨도 무해 |
| V15 | `event_publication.status·completion_attempts·last_resubmission_date`(널 허용) | 남겨도 무해 |
| V16 | `ipo_deals.last_alert_phase·last_alert_date` | 남겨도 무해 |

V12까지와 같이 **적용 뒤 파일을 고치지 않는다**(Flyway 체크섬). 기동 전 백업 권장: `scripts\backup_db.ps1`.

## 4. 운영 변경 이벤트 (PROGRESS 트랙 C, 규약-4)

- **10/6 09:05부터** C3 판단 주기가 21거래일로 바뀐다. 표가 비어 있으니 10/6에 전 종목을 한 번 판단하고, 다음 판단은 **11/5**(국면 ON 기준). 그 사이 `주기 전 5`가 정상.
- 게이트 ② 연속 무인 운영 카운트는 **유지**(D-03). C3 성과 시계열은 **10/6 전후로 나눠** 본다.
- 같은 종목 미체결 매수가 있으면 매수를 거부한다(지금까지는 통과).
- 거시 지표를 7일 넘게 못 받으면 매수를 막는다.

## 5. 재기동 확인 (장외, 한 번)

1. (선택) `scripts\backup_db.ps1`
2. `scripts\stop_autostock.bat` → `scripts\start_autostock.bat`
3. 기동 로그:
   - `Migrating schema "public" to version "13 - strategy state"` … `"16 - ipo alert marker"`, 오류 없음
   - `시장 세션 (기동) → STANDBY`, `거시 지표 발행: FRED_VIX=…`·`ECOS_USDKRW=…`
4. `http://127.0.0.1:8080/actuator/health` — `status: UP`, `kiwoomWs` UP(session STANDBY), `kiwoomAuth` UP 또는 UNKNOWN, `killSwitch` UP(engaged false)
5. 앱을 다시 끌 때 종료 로그에 `event executor terminated`가 없는지(공시 요약 대기열이 있으면 텔레그램으로 온다)

## 6. 10/6(화) 장중 확인

- 08:30 `WS 로그인 성공` → 헬스 `kiwoomWs` UP(connected·loggedIn true, `lastMessageAgeSeconds` 10 안팎)
- 09:05 `C3: 마지막 판단일 복원 0종목 {}` → `C3 판단 2026-10-06 — …` → `SELECT * FROM strategy_state;` 5행(국면 ON일 때)
- 주문이 나가면: 텔레그램 주문·체결 알림 순서, WS 재연결·PING 지연 로그 없음. 거부되면 사유(`미체결 매수 주문 있음` 등)
- 15:45 분봉 정기 적재, 15:50 일일 리포트 → `SELECT trade_date, order_count, submitted_count, cancelled_count FROM daily_performance ORDER BY trade_date DESC LIMIT 3;`
- 10/7 09:05 `주기 전 5`, `다음 판단 2026-11-05부터`

## 7. 검증 상태

- 컨테이너(HA PG17 시험 DB): **app 737건 통과·16 skip, common 63건 통과**, 실패 0. 오늘 Phase 1 묶음에서 테스트 약 69건 추가(`@Test` 기준 715 → 784). `ModularityTests`·`ArchitectureRulesTest`(새 규칙 "risk는 trading을 모른다" 포함) 통과.
- 실제 jar 실행 점검(새 DB `autostock_smoke`, SIM, 외부 연동·텔레그램 끔, 가짜 키):
  - V1~V16 적용 후 12.5초에 기동, 재실행 때 `Successfully validated 16 migrations`
  - `/actuator/health` UP — `killSwitch` UP, `kiwoomAuth` UNKNOWN(토큰 미발급), `kiwoomWs` UP(꺼짐)
  - 게이지·카운터 노출(`risk.killswitch.engaged`, `orders.unknown.count`, `orders.cancel_requested.count`, `market.ws.last_message_age.seconds`, `reconcile.position.mismatch`)
  - 경로 변수 형식 오류 400(`VALIDATION_FAILED` — 이 점검에서 찾아 고침, `small-fixes-2026-10-02.md`), `/api/orders?days=1` 200
  - `/actuator/shutdown` 정상 종료 — 오류는 가짜 키로 인한 토큰 발급 실패(8001)뿐
- 미검증: 5·6절 전부(실제 키·텔레그램·실DB로 재기동, 장중).
- 남은 Phase 1: 1.6(장중 실측 — UNKNOWN 해소 TR), 1.10(복구 훈련 — 사용자와 함께, 장외).
