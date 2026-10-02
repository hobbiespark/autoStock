# C3 판단 주기를 거래일 기준으로 + 마지막 판단일 영속화 (Phase 1.1)

- 날짜: 2026-10-02(금) 장마감 후
- 계획: `upgrade-2026-10/11-execution-plan.md` 1.1(BE-P1-1)
- 사용자 결정: **D-03 권고안 (a)** — 2026-10-02 19:41 "권고 로 적용 함". 수정 적용, 게이트 ② 연속 운영 카운트 **유지**, C3 성과 시계열은 **변경일 전후로 나눠** 집계

## 1. 목적

- **주기 단위 불일치.** 백테스트는 21**봉**(거래일)마다 판단하는데(`TimeSeriesMomentumStrategy.REBALANCE_INTERVAL`), 라이브는 `last.plusDays(21)` **역일**로 셌다. 휴장일이 낀 달엔 라이브가 더 일찍 판단했다.
- **재기동마다 전 종목 재판단.** 마지막 판단일이 메모리 맵이라 재기동하면 전 종목이 "첫 판단"이 됐다. 아침 자동 기동(07시대)이면 그날 09:05에 다시 판단하므로, 라이브 C3는 사실상 **매일** 판단했다(추론 — 9/30·10/1 모두 07시대 기동, 판단 결과는 `signal_decisions`에 있음).

## 2. 결정과 근거

- `market.MarketCalendarService`에 공개 API 두 개를 더했다(기존 `isTradingDay` 재사용, 캐시 그대로).
  - `tradingDaysBetween(fromExclusive, toInclusive)` — 구간의 거래일 수
  - `plusTradingDays(from, n)` — n번째 거래일(요약 로그의 "다음 판단일" 계산용 — 계획에 없던 추가)
- **판단일 조건:** `tradingDaysBetween(마지막 판단일, 오늘) >= 21`. 백테스트의 `dayIndex % 21 == 0`과 같은 봉 간격이다. 예: 10/1 판단 → 10/5·10/9 휴장을 빼면 21거래일째는 **11/3**(역일 기준은 10/22).
- **영속화:** `strategy_state(strategy_id, symbol, last_decision_date, created_at, updated_at, version, PK(strategy_id, symbol))` — V13, strategy 모듈 소유.
  - 판단에 성공한 종목만 저장한다(데이터 부족·조회 실패는 저장하지 않아 다음 스케줄에 재판단 — 기존 규칙).
  - 재기동 뒤 **첫 실행**(09:05)에서 한 번 읽는다(지연 로드). 메모리에 더 늦은 날짜가 있으면 그쪽을 남긴다.
  - JPA 엔티티 + 리포지토리(코딩 규칙 §7 "모든 테이블은 엔티티"). 키를 직접 넣는 엔티티라 `@Version Long`(래퍼)으로 새 행·기존 행을 구분한다 — `RiskStateEntity`와 같은 이유(Hibernate 6.6).
  - 저장은 `REQUIRES_NEW`(`RiskStateStore`와 같은 모양).
- **실패해도 매매를 막지 않는다.** 복원 실패 → 경고, 이번 실행은 메모리 기준(기록 없는 종목은 다시 판단 = 예전 동작), 다음 실행에서 다시 읽는다. 저장 실패 → 경고, 판단·신호는 그대로. "판단을 건너뛰기보다 한 번 더 하는 쪽이 안전하다"는 기존 방향을 유지했다.
- **빈 표로 배포(시드 없음).** `signal_decisions`에서 마지막 판단일을 채워 넣을 수도 있었지만 넣지 않았다. D-03의 "변경일 기준 분리"에 맞춰, 배포 후 첫 09:05(**10/6**)에 전 종목을 한 번 판단하고 그날부터 21거래일 주기가 시작된다. 예전 판단일은 재기동 탓에 사실상 매일이라 이어 갈 "주기"가 없다.
- 판단식(`*Math`)은 손대지 않았다(규약-1).

## 3. 버린 대안과 보류

- **`signal_decisions`에서 시드:** 위 이유로 버렸다. 필요하면 `INSERT … SELECT max(trade_date) … WHERE strategy_id='C3-MOMENTUM' AND conclusion IN ('BUY','SELL','HOLD','SKIP') AND metrics_json LIKE '%momentumLookbackN%'`로 수동 시드할 수 있다.
- **기동 이벤트(ApplicationReadyEvent)에서 로드:** 테스트가 `execute()`만 불러도 되도록 첫 실행 지연 로드를 택했다. 기동 순서와 무관하다.
- **판단일을 `signal_decisions`에서 매번 계산:** 모듈 경계(그 표는 monitor 소유)를 넘고, "판단했지만 결론 기록 실패" 같은 경우를 구분하기 어렵다.

## 4. 변경 파일

- 운영
  - `market/MarketCalendarService` — `tradingDaysBetween`, `plusTradingDays`
  - `strategy/C3LiveStrategy` — 거래일 주기, 생성자에 `StrategyStateStore`, 지연 로드·저장, 다음 판단일 계산, 클래스 설명
  - `strategy/StrategyStateEntity`·`StrategyStateRepository`·`StrategyStateStore`(신규)
  - `db/migration/V13__strategy_state.sql`(신규)
  - `application.yml` — `decision-interval-days` 주석(거래일)
- 테스트
  - `MarketCalendarServiceTest` 6 → 8건: 추석·대체공휴일 낀 거래일 수, 21거래일 뒤 = 11/3
  - `C3LiveStrategyTest` 17 → 22건: 21거래일 경계(10/22·11/2 주기 전, 11/3 판단), 재기동 후 재판단 안 함, 데이터 부족 미저장, 복원 실패 시 재판단·다음 실행 재시도, 저장 실패에도 신호 발행. 기존 요약 테스트의 다음 판단일 10/22 → 11/3
  - `StrategyStateStoreDbTest`(신규, 실제 PostgreSQL): 저장·갱신(version 1)·전략별 조회
  - `SchemaAndTimeZoneDbTest` — 최신 버전 기대값 13(이후 묶음에서 15)

## 5. 함정과 주의

- **운영 영향(의도):** 판단 빈도가 백테스트와 같아진다(약 월 1회). 10/6 09:05에 전 종목 판단 뒤, 다음 판단은 21거래일 뒤(10/6 기준 **11/5**)다. 그 사이 09:05 요약은 `주기 전 5`가 정상이다.
- 국면 OFF 강제 청산은 주기와 무관하게 매일 본다(기존과 같음).
- 판단 시점의 수동 개입(수동 매도 등)은 다음 판단일까지 C3가 되돌리지 않는다. 예전에는 다음 날 아침 재판단으로 다시 살 수 있었다.
- PROGRESS 트랙 C에 **운영 변경 이벤트**로 기록했다(규약-4). 게이트 ② 카운트는 유지(D-03), C3 성과는 10/6 전후로 나눠 본다.

## 6. 롤백

- 커밋을 되돌린다. V13 표는 남겨도 무해하다(되돌린 코드는 읽지 않는다).

## 7. 검증 상태

- 컨테이너: 대상 테스트 통과(전체 결과는 `aiDoc/phase1-offhours-2026-10-02.md`).
- 미검증(10/6 장중): 09:05 로그 `C3: 마지막 판단일 복원 0종목 {}` → 판단 → `strategy_state` 5행(`SELECT * FROM strategy_state;`). 10/7 09:05에 `주기 전 5`, 다음 판단 `2026-11-05부터`.
