# 잔고 타입 정리 → 잔고·장부 주기 대사 (Phase 1.5)

- 날짜: 2026-10-02(금) 장마감 후
- 계획: `upgrade-2026-10/11-execution-plan.md` 1.5(BE-P2-1 → P1-4), 새 설정 키는 규약-6 `trading.position-reconcile.enabled`

## 1. 목적

- **포트 밖으로 새는 키움 필드(BE-P2-1).** `BrokerBalance.holdings`가 `List<Map<String,Object>>`(키움 원시 Map)라, 포트 바깥의 `PositionRestorer`가 `rmnd_qty`·`pur_pric` 같은 키움 필드 이름을 알아야 했다(ARCHITECTURE.md 4절 위반, "typed record TODO").
- **장부 어긋남을 모름(BE-P1-4).** `PositionBook`(체결·기동 복원으로 쌓은 장부)과 브로커 잔고가 어긋나도(체결 통보 유실, HTS 수동 매매, 복원 실패) 알 길이 없었다. 장부가 틀리면 "보유 중 추가 매수 금지"와 매도 수량이 틀어진다.

## 2. 결정과 근거

- **`execution.BrokerHolding(StockCode symbol, String name, long quantity, Price avgPrice)`** — 계획의 3필드에 종목명을 더했다(잔고의 `stk_nm`으로 종목명 사전을 채우는 기존 동작 유지).
  - 해석은 `KiwoomBrokerAdapter.toHolding`으로 옮겼다. **필드 규칙은 그대로**: 후보 키(`stk_cd`/`stock_cd`, `rmnd_qty`/…, `pur_pric`/…), `A` 접두 제거, 0·없음 평단은 `null`(평단 미상), 종목코드 형식 오류·수량 0 원소는 경고 후 뺀다.
  - `PositionRestorer`는 `BrokerHolding`만 본다(키움 필드를 모른다). 실측 끝난 "원소 키 DEBUG 로그"는 뺐다.
- **`trading.PositionReconciler`(신규)** — 5분마다(기동 1분 뒤 시작) 잔고(kt00018) 종목별 수량과 장부 수량을 비교한다.
  - **알림·메트릭만, 자동 교정 없음**(계획 — 결정 전까지). 어느 쪽이 맞는지는 사람이 HTS로 확인한다.
  - **같은 불일치(종목·브로커 수량·장부 수량)를 연속 2회(약 10분) 볼 때만** 알린다. 체결 직후 잔고 반영과 WS 체결 통보 사이 시차로 생기는 일시 불일치를 거른다. 같은 불일치는 한 번만 알리고, 알린 종목이 다시 맞으면 INFO `잔고·장부 다시 일치`. 값이 바뀌면 다시 연속 2회 뒤에 알린다.
  - 알림은 새 이벤트 `PositionMismatch` → monitor(`TradeNotificationListener`) 텔레그램 WARN. trading은 monitor를 모른다(monitor → trading 의존이 이미 있어 반대 방향은 순환).
  - 게이지 `reconcile.position.mismatch` = 마지막 점검의 불일치 종목 수(연속 확인 전 포함).
  - 조건: `trading.position-reconcile.enabled=true` + LIVE + 장중 세션(ACTIVE). **기본 false** — 장외 검증 후 켠다(규약-5).
  - **계획과 다른 점:** 계획은 `ReconciliationService.scheduledReconcile`에 끼워 넣는 안이었다. 주문 대사와 책임이 달라 별도 컴포넌트로 두고, 같은 주기(5분)·같은 조건(LIVE·ACTIVE)을 따랐다.
- **호출량:** kt00018 5분 1회(TR당 1회/초 한도 안). `BrokerEquitySource`(60초 캐시)와는 별개 호출이다.
- 의존 방향: trading → portfolio(장부 조회, portfolio는 리프)·execution(잔고). 순환 없음(`ModularityTests`).

## 3. 버린 대안과 보류

- **불일치 시 장부를 브로커 값으로 교정:** 체결 통보가 늦게 오는 중이면 교정이 이중 반영을 만든다. 1.6(체결내역 TR)로 근거가 생긴 뒤 결정한다.
- **미체결 주문이 있는 종목은 비교 제외:** 연속 2회 규칙(5분 뒤 미체결은 타임아웃 취소)으로 충분하다고 봤다. 오경보가 잦으면 추가한다.
- **평단 비교:** 수수료·반올림으로 달라질 수 있어 수량만 본다.

## 4. 변경 파일

- 운영
  - `execution/BrokerHolding`(신규), `execution/BrokerBalance`(holdings 타입), `execution/KiwoomBrokerAdapter`(`toHolding`), `execution/PositionRestorer`(타입 사용)
  - `trading/PositionReconciler`·`PositionReconcileProperties`(신규), `trading/package-info`(의존 방향)
  - `common/event/PositionMismatch`(신규), `monitor/TradeNotificationListener`(WARN 알림)
  - `application.yml` — `trading.position-reconcile.enabled: false`
- 테스트
  - `KiwoomBrokerAdapterTest` +2: 보유 원소 → `BrokerHolding`(A 접두, 형식 오류·수량 0 제외, 평단 0 → 미상), 보유 배열 없음 → 빈 목록
  - `PositionRestorerTest` 4 → 3건: 해석 테스트는 어댑터로 옮김, 복원·종목명·평단 미상·SIM
  - `PositionReconcilerTest`(신규 7건): 일치, 연속 2회 한 번만 알림, 장부에만·브로커에만, 1회 불일치 무시, 재일치 후 재발 시 다시 알림, 꺼짐·SIM·장외 무조회, 잔고 조회 실패 무예외
  - `TradeNotificationListenerTest` +1: 불일치 WARN 문구

## 5. 함정과 주의

- 켜기 전(기본 false)에는 아무 일도 하지 않는다. **켜는 법:** `scripts\start_autostock.bat` 인자에 `--trading.position-reconcile.enabled=true`를 더하거나 yml 기본값을 바꾼다(장외).
- 켠 뒤 첫 장중에 `잔고·장부 불일치` WARN이 오면, 먼저 대시보드 보유와 HTS 잔고를 비교한다. 수동 매매 흔적이면 앱 재기동(기동 복원이 장부를 브로커 기준으로 다시 채움)이 가장 간단하다 — 단 장중 재기동 규약(킬스위치 ON 상태에서만)을 따른다.
- 잔고 원소 해석 실패 경고는 이제 잔고를 읽을 때마다(자산 조회 60초 캐시, 이 대사 5분) 남을 수 있다. 실측 형식에서는 나오지 않는다.

## 6. 롤백

- 대사만 끄려면 `trading.position-reconcile.enabled=false`(기본값). 전체는 커밋을 되돌린다. 스키마 변경 없음.

## 7. 검증 상태

- 컨테이너: 대상 테스트 통과(전체 결과는 `aiDoc/phase1-offhours-2026-10-02.md`).
- 미검증: 켠 뒤 장중 5분 주기 로그(정상이면 무로그), `GET /actuator/metrics/reconcile.position.mismatch` 값 0. 기동 복원 로그(`포지션 복원`)가 예전과 같은지는 10/6 재기동에서 본다.
