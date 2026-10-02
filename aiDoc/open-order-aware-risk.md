# RiskGate가 미체결 매수를 안다 + 주문 일련번호 원자화 (Phase 1.2)

- 날짜: 2026-10-02(금) 장마감 후
- 계획: `upgrade-2026-10/11-execution-plan.md` 1.2(BE-P1-2·P1-3), 1.9의 BE-P2-17(같이 처리)
- 사용자 지시: 2026-10-02 19:39 "계획상 장마감이여도 할 수 있는걸 하자" — Phase 1 중 장외에 할 수 있는 작업을 당겨서 진행

## 1. 목적

- **같은 종목 중복 매수 차단.** `RiskGate`의 "보유 중이면 추가 매수 금지"와 "동시 보유 한도"는 `PositionBook`(체결된 것만 앎)만 봤다. 매수 주문이 나가 체결을 기다리는 동안 같은 종목 매수 시그널이 또 오면 그대로 통과했다.
- **주문 일련번호 중복 차단.** `DailyLimitTracker.tryAcquireOrderSlot()`이 카운터를 올린 뒤, `RiskGate`가 `todayOrderCount()`를 **다시 읽어** `ClientOrderId` 일련번호로 썼다. 두 스레드가 동시에 통과하면 같은 번호를 받을 수 있다(나중 것은 DB UNIQUE로 버려짐 → 주문 유실).
- **자정 롤오버 원자화(BE-P2-17).** 롤오버가 "날짜 CAS → 개수 0으로 set" 두 단계라, 자정 직후 다른 스레드의 증가가 지워지거나 어제 번호가 오늘 번호와 겹칠 수 있었다.

## 2. 결정과 근거

- **포트 `risk.OpenOrderQuery`** — `Set<StockCode> symbolsWithOpenBuy()` 한 메서드. 구현은 `trading.OpenOrderQueryAdapter`(주문 테이블 조회). 의존 방향 trading → risk로 `EquitySource`(execution이 구현)와 같은 모양이다. `ArchitectureRulesTest`에 "risk는 trading을 모른다" 규칙을 더해 방향을 고정했다.
- **"결과가 확정되지 않은 매수" = 6개 상태:** `SUBMITTING, SUBMITTED, ACCEPTED, PARTIALLY_FILLED, CANCEL_REQUESTED, UNKNOWN`.
  - 계획의 5개에 `CANCEL_REQUESTED`를 더했다. 취소 요청 중인 주문도 취소 확인 전에 체결될 수 있다(전이표 `CANCEL_REQUESTED → FILLED·PARTIALLY_FILLED`).
  - `CREATED·VALIDATED`는 넣지 않았다(계획과 같음). 전송 전 단계라 브로커에 없고, 저장 직후 앱이 죽어 남은 행이 그 종목 매수를 영영 막지 않게 하기 위해서다.
  - 날짜는 보지 않는다. 어제의 `UNKNOWN`도 아직 포지션이 될 수 있다. 막힌 종목은 로그(`미체결 매수 주문 있음 — 추가 매수 차단`)와 판단 근거(`SignalDecision`)에 남는다.
- **매수 분기 순서:** 보수 모드 → 공시 블랙리스트 → 보유 중 → **미체결 매수 종목** → 동시 보유 한도(**보유 + 미보유 종목의 미체결 매수**) → 사이징. 계획대로 기존 단계 순서는 유지했다.
  - 동시 보유 한도의 판단 근거 지표에 `pendingBuyCount`를 더했다(`openPositionCount`는 그대로).
  - **계획과 다른 점:** 계획의 `int openBuyCount()`는 만들지 않았다. 이미 보유한 종목의 미체결 매수(부분 체결 등)를 두 번 세게 되므로, 종목 집합에서 "장부에 없는 것"만 센다.
- **조회 실패 = 매수 거부(fail-closed):** 모르는 채로 사지 않는다. 사유 `미체결 주문 조회 실패 — 매수 거부`. 매도는 이 경로를 거치지 않으므로 청산은 막히지 않는다.
- **수동 주문은 적용 안 함:** 수동 지정 매수는 원래 물타기 금지·동시 보유 한도를 적용하지 않는다(운영자 의도, `sizeManual`). 미체결 매수 검사도 같은 성격이라 같은 규칙을 따른다. 금액 상한(D-06)은 그대로다.
- **슬롯 = 날짜 + 일련번호:** `tryAcquireOrderSlot()`이 `Optional<OrderSlot(day, serial)>`을 돌려준다. `RiskGate`는 이 값으로 `ClientOrderId`를 만든다.
  - **계획과 다른 점:** 계획은 `OptionalInt`였다. 날짜를 함께 돌려줘야 자정 경계에서 "어제 번호 + 오늘 날짜"가 섞이지 않는다.
  - 상태는 불변 값 `DayCount(day, count)` 하나를 `AtomicReference`에 담는다. 날짜 확인·한도 확인·증가가 compareAndSet 한 번이다.
  - 한도를 넘은 시도는 개수를 올리지 않는다. 예전에는 한도 초과 시도도 개수에 더해져 대시보드·일일 리포트의 "오늘 주문 수"가 실제보다 컸다.
  - 시계가 뒤로 가도 날짜를 되돌리지 않는다. 같은 날 번호가 1부터 다시 나오지 않게 하기 위해서다.
  - 재기동 복원(`OrderSequenceRestored`)은 기존과 같다(오늘 날짜만, 최댓값으로 끌어올림).

## 3. 버린 대안과 보류

- **`PositionBook`에 "주문 중" 상태 추가:** portfolio는 리프 모듈(아키텍처 규칙)이고 체결 장부다. 주문 상태는 trading 소유라 포트로 묻는 쪽이 경계에 맞다.
- **메모리 집합으로 미체결 추적(이벤트 구독):** 재기동하면 비어 버린다. DB 조회는 매수 시그널마다 1회(하루 몇 건)라 비용이 작다.
- **`RiskGate.onSignal`에 `synchronized`:** 같은 종목 신호가 동시에 와도 막히지만 모든 신호를 직렬화한다. 슬롯 원자화로 번호 중복은 이미 막히고, 같은 종목 동시 신호는 지금 경로(C3 09:05 한 스레드, 수동은 별도 규칙)에서 생기지 않는다(추론). 필요해지면 종목별 잠금을 검토한다.

## 4. 변경 파일

- 운영
  - `risk/OpenOrderQuery`(신규) — 포트
  - `trading/OpenOrderQueryAdapter`(신규) — 포트 구현(6개 상태 × 매수)
  - `trading/OrderRepository` — `findBySideAndStatusIn` 파생 쿼리
  - `risk/RiskGate` — 생성자에 포트 추가, 매수 분기 두 검사, 슬롯 값으로 `ClientOrderId`
  - `risk/DailyLimitTracker` — `Optional<OrderSlot>`, `DayCount` 원자 상태, 한도 초과 미증가, 시계 역행 무시
- 테스트
  - `DailyLimitTrackerTest` 3 → 7건: 한도 경계(초과 미증가), 슬롯 날짜, 8스레드 1,600슬롯 번호 중복 0, 재기동 복원, 시계 역행
  - `RiskGateTest` 17 → 24건: 같은 종목 미체결 거부(슬롯 미사용), 다른 종목 통과, 한도에 미체결 포함, 보유 종목 이중 계산 안 함, 조회 실패 시 매수 거부·매도 통과, 수동 매수 미적용, 주문 ID 날짜·번호
  - `OpenOrderQueryAdapterDbTest`(신규, 실제 PostgreSQL): 9개 상태·방향 조합에서 6개 상태 매수만
  - `ArchitectureRulesTest` +1: risk는 trading을 모른다
  - `RiskGateMacroTest` — 생성자 인자만

## 5. 함정과 주의

- **운영 영향:** 지금까지 막히지 않던 매수가 막힐 수 있다(의도). 장중에 `미체결 매수 주문 있음` 또는 `동시 보유 한도 … (보유 n, 미체결 매수 m)` 로그가 나오면 이 변경 때문이다.
- 오래 남은 `UNKNOWN`·`CANCEL_REQUESTED` 매수가 있으면 그 종목은 대사로 확정될 때까지 매수되지 않는다. 확인 SQL: `SELECT client_order_id, symbol, status, updated_at FROM orders WHERE side='BUY' AND status IN ('SUBMITTING','SUBMITTED','ACCEPTED','PARTIALLY_FILLED','CANCEL_REQUESTED','UNKNOWN');`
- `ClientOrderId` 일련번호 상한은 999다. `risk.daily-max-orders`를 999보다 크게 두면 안 된다(기존과 같음).

## 6. 롤백

- 커밋을 되돌린다. 설정·스키마 변경은 없다.

## 7. 검증 상태

- 컨테이너: 대상 테스트 통과(전체 결과는 묶음 배포 문서 `aiDoc/phase1-offhours-2026-10-02.md`).
- 미검증(10/6 장중): C3 09:05 매수가 평소대로 나가는지, 판단 근거 화면의 거부 사유 표시.
