# 종목코드와 종목명 함께 표시 (2026-10-02)

- 날짜: 2026-10-02
- 사용자 요구: "종목명 정보 추가 필요합니다. 종목 코드와 종목명은 항상 같이 표시되야합니다. 관련 모든 수정"
- 근거:
  - 기존 표기: 공시 블랙리스트 알림이 이미 "기업명(코드)" 형식을 썼다(`alert-digest.md`).
  - 키움 실측: ka10001 응답의 `stk_nm`(`docs/measured/tr_probe_20260918_ka10001.json`), kt00018 보유 원소의 `stk_nm`(`PositionRestorer` 주석, 2026-09-11).

## 1. 목적

- 텔레그램 알림, `/status`, 일일 리포트, 이벤트 피드, 대시보드, 로그가 종목을 코드(`005930`)로만 보여 줬다.
  - 무슨 종목인지 알려면 코드를 따로 찾아봐야 했다.
- 이제 모든 곳에서 같은 형식 `삼성전자(005930)`으로 보인다.

## 2. 표기 규칙

- **형식:** `종목명(코드)`이다. 사이에 공백이 없다.
  - 예: `삼성전자(005930)`, `KODEX 200(069500)`.
- **이름을 모를 때:** `종목명 미확인(005930)`이다. 코드는 항상 남는다.
- **코드가 비었을 때:** 빈 문자열이다.
- **종목코드 형식이 아닐 때:** 받은 값을 그대로 쓴다. 종목코드 형식은 영숫자 대문자 6자리다. 예: 키움 응답의 형식 오류 행.
- **같은 함수를 쓴다:**
  - 서버 문구: `common.util.StockNames.label`
  - 화면: `frontend/src/stock.ts`의 `stockLabel`, 표·목록은 `components/StockLabel.tsx`
  - 화면 표기를 복사하면 서버 알림과 같은 문자열이 된다.

## 3. 이름 출처와 우선순위

- **키움(`KIWOOM`, 높음):**
  - ka10001 `stk_nm` — 시세 어댑터(`KiwoomMarketDataAdapter.stockQuote`)가 응답을 받을 때마다 배운다. C3 판단, 대시보드 시세, 종목명 조회가 모두 이 경로를 지난다.
  - kt00018 보유 원소 `stk_nm` — 기동 시 잔고 복원(`PositionRestorer`)이 배운다. 보유 종목은 추가 조회 없이 바로 이름이 붙는다.
- **DART(`DART`, 낮음):**
  - 공시 블랙리스트 이벤트의 기업명(`corp_name`)이다.
  - 거래소 종목명과 다를 수 있다. 그래서 키움 이름이 있으면 덮지 않고, 키움 이름이 오면 키움 이름으로 바뀐다.
- **같은 출처의 다른 이름:** 바꾼다(종목명 변경 대응).

## 4. 구조

### `common.util.StockNames` — 메모리 사전
- 정적 저장소(`ConcurrentHashMap`)다. 기능:
  - `label` — 표기를 만든다.
  - `nameOf` — 이름을 돌려준다.
  - `learn` — 이름을 배운다(우선순위 적용).
  - `preload` — DB 값을 올린다.
  - `onMiss` — 모르는 종목을 만났을 때 조회 요청을 받을 청취자를 건다.
  - `onLearn` — 이름이 바뀌었을 때 저장 청취자를 건다.
- **표시 전용이다.** 리스크·전략은 이름으로 판단하지 않는다. 이름이 없어도 매매는 그대로 돈다.
- **정적으로 둔 이유:**
  - 로그를 남기는 거의 모든 모듈(risk·strategy·execution·portfolio·market·trading·monitor)이 쓴다.
  - 생성자 주입으로 바꾸면 표시 하나 때문에 매매 핵심 클래스(RiskGate 등)의 의존과 테스트 구성이 모두 늘어난다.
  - common은 순수 자바 모듈이라 스프링 빈을 둘 수 없다.
  - 상태는 "코드 → 이름" 사전 하나뿐이고 스레드 안전하다.

### `market.StockNameDirectory` — 사전 채우기와 저장
1. **기동:** DB 사전(V10 `stock_names`)을 올린다. 재기동 직후나 키움 장애 중에도 이름이 바로 보인다.
2. **설정 종목:** `strategy.c3.symbols`, `autostock.minute-archive.symbols` 중 이름이 없는 것을 조회 대기열에 넣는다.
3. **모르는 종목:** 표시하다 이름을 모르는 종목을 만나면 대기열에 넣는다.
4. **조회:** 2초마다 대기열을 최대 5건씩 ka10001로 조회한다.
   - 실패하면 30분 동안 다시 조회하지 않는다. 실패는 오류, 이름 없음, 100자 초과다.
5. **저장:** 다른 경로에서 배운 이름은 모아 두었다가 이 클래스의 2초 주기 작업이 DB에 남긴다.
   - 이름을 배운 호출 스레드에서 DB를 쓰지 않는다. 그 흐름이 매매 판단이나 공시 블랙리스트 등록 트랜잭션일 수 있기 때문이다.
   - 거기서 DB를 쓰면 흐름이 느려진다. 저장 실패가 바깥 트랜잭션을 롤백 전용으로 만들 수도 있다.
6. **갱신:** 평일 08:10 KST에 확인한 지 7일이 지난 이름을 다시 조회한다.

### DB — V10 `stock_names`
- 컬럼: `symbol`(PK), `name`, `source`(`KIWOOM`|`DART`), `updated_at`(마지막 확인 시각)
- 쓰기는 한 문장 upsert(`INSERT … ON CONFLICT (symbol) DO UPDATE`)다. 같은 종목을 두 스레드가 동시에 배워도 중복 키 오류가 나지 않는다.
- market 모듈 소유다. 매매 판단은 이 테이블을 읽지 않는다.

## 5. 적용 위치

### 알림(텔레그램)
- 체결·주문요청 — `TradeNotificationListener`
  - 예: `체결: 삼성전자(005930) BUY 10주 @ 70000`
- `/status` 보유 종목 — `TelegramCommandPoller`
  - 예: `- 삼성전자(005930) 19주 @ 259974`
- 일일 리포트의 보유 종목과 슬리피지 최대 종목 — `DailyReportScheduler`
- 공시 블랙리스트 즉시 알림과 요약 — `DisclosureBlacklistListener`
  - 사전의 이름을 먼저 쓰고, 없으면 공시 기업명을 쓴다.

### 이벤트 피드
- 시그널·주문요청·체결 요약 3종이다. 예: `[삼성전자(005930)] BUY 14주 @ 70000 주문요청`

### API
- 응답에 종목명 필드를 추가했다. 기존 필드는 그대로라 하위 호환이다.
- 이름을 아직 모르면 `null`이다. 화면이 `종목명 미확인`으로 표시한다.
- 추가된 필드:
  - `PositionView.symbolName`
  - `OrderHistoryItemView.symbolName`
  - `SignalDecisionView.symbolName`
  - 대시보드 슬리피지 `maxBpsSymbolName`(`SlippageTracker.SlippageSummary`)
- 값이 바뀌는 곳:
  - `SignalDecisionView.metrics` — 키가 `Symbol`로 끝나는 지표값(예: `regimeIndexSymbol`)은 응답에서 `KODEX 200(069500)`으로 바꿔 보낸다. 저장값(`metrics_json`)은 코드 그대로다.
  - `QuoteView.name` — 기본정보 조회가 실패하면 사전의 이름으로 채운다.

### 화면
- 보유 포지션, 슬리피지 최대 종목
- 주문 이력 표와 취소 확인 창
  - 취소 확인 창 예: `삼성전자(005930) BUY 10주`
- 판단 근거
- 수동 주문 카드의 시세 줄과 발행 확인 창
  - 시세 응답에 이름이 없으면 `종목명 미확인(코드)`이다. 예전에는 이 경우 "종목명 조회 중"이 계속 보였다.

### 로그
- 표기를 바꾼 곳:
  - risk: `RiskGate`, `DailyPnlTracker`, `DisclosureBlacklist`
  - strategy: `C3LiveStrategy`(실행 요약의 국면 지수 포함), `StrategyEngine`
  - execution: `KiwoomBrokerAdapter`, `PositionRestorer`
  - portfolio: `PositionBook`
  - market: 시세·일봉·분봉 적재·WS 구독 오류
  - trading: `TradingService`
  - monitor: `SignalDecisionListener`, `DecisionController`, `DashboardController`, `SlippageTracker`
- 로그의 `symbol=005930`은 `종목=삼성전자(005930)`으로 바뀌었다.
- 틱마다 불리는 `StrategyEngine`의 TRACE 로그는 TRACE가 켜져 있을 때만 표기를 만든다.

## 6. 제외

- **백테스트·실험 도구(`backtest` 패키지):** 오프라인 CSV 분석이다. 운영 표시가 아니다.
- **공모주:** 상장 전이라 종목코드가 없다. 기업명만 표시한다.
- **종목코드 형식이 아닌 원시 값:** 이름을 붙일 수 없다. 예: 키움 형식 오류 행, 시세 원시 메시지 DEBUG 로그.
- **DB 저장값:** 판단 기록 `metrics_json`, `event_store`, `orders` 등은 코드 그대로 둔다. 이름은 표시할 때 붙인다. 그래서 종목명이 바뀌어도 기록은 흔들리지 않는다.
- **공시 수집 DEBUG 로그(`DisclosureBlacklistSyncScheduler`):** 이미 "기업명(코드)" 형식이다.

## 7. 버린 대안

- **생성자 주입 서비스로 모든 클래스에 넣기:** 4절 "정적으로 둔 이유"와 같다.
- **키움 전 종목 목록을 매일 받아 두기:** 실측하지 않은 TR이고 수천 종목을 받아야 한다. 필요한 종목만 1건씩 조회하면 충분하다(ka10001, 실측 완료).
- **DART 기업명만 쓰기:** ETF(069500)는 DART 기업이 아니다. 기업명과 거래소 종목명이 다를 수도 있다.
- **이벤트(Fill·OrderRequest 등)에 이름을 실어 나르기:** 공용 이벤트 계약(common)이 바뀐다. 저장된 `event_store`와도 맞지 않게 된다.

## 8. 함정과 주의

- **처음 보는 종목:** 첫 표시 때는 `종목명 미확인(코드)`이다. 수 초 뒤 조회가 끝나야 이름이 붙는다.
  - 텔레그램처럼 한 번 보내고 끝나는 문구는 미확인으로 남을 수 있다.
  - 그래서 기동 때 설정 종목과 보유 종목(잔고 복원)을 먼저 채운다.
- **ka10001 유량:** 매매의 현재가 조회와 TR 유량(초당 1건)을 나눠 쓴다.
  - 조회는 2초마다 최대 5건이다.
  - C3 주문 기준가는 주로 ka10004(호가)를 써서 겹침이 적다.
- **공시 기업명으로 배운 종목:** 7일마다 평일 08:10에 키움 이름으로 다시 조회된다. 종목 수가 늘면 그 시각 ka10001 사용량도 는다.
  - 예: 1천 종목이면 하루 약 150건, 2~3분이다.
  - 장 시작(09:00) 전에 끝난다.
- **조회가 계속 실패하는 종목:** 예를 들어 상장폐지 종목이다. 매 평일 08:10에 1회씩 다시 조회한다. 이름은 마지막 값을 유지한다.
- **테스트:** 정적 사전이 테스트 사이에 남으면 결과가 순서에 따라 달라진다.
  - app 모듈: JUnit 전역 확장 `support.StockNamesResetExtension`이 매 테스트 전후로 비운다(`junit-platform.properties`의 자동 감지).
  - common 모듈: `StockNamesTest`가 직접 비운다.
  - 새 테스트 모듈에서 `StockNames`를 쓰면 같은 설정이 필요하다.
- **로그 검색어:** `symbol=`로 찾던 검색은 `종목=` 또는 코드만으로 찾는다.

## 9. 롤백

- 코드를 되돌린다.
- **V10 테이블:** 남겨 두어도 무해하다(읽는 코드가 없어진다).
  - V10 파일이 없어져도 앱은 뜬다. DB에 적용된 V10이 가장 높은 버전이면 Flyway가 "미래 버전"으로 보고 넘어간다.
    - 기본 설정 `ignoreMigrationPatterns=*:future` 덕분이다.
    - 2026-10-02 실측: 내장 PostgreSQL 16.15에 V1~V10을 적용한 뒤 V1~V9만으로 migrate를 돌렸고 통과했다.
  - 단, V11 이후가 들어온 뒤에 V10만 빼면 "missing"으로 기동이 실패한다. 그때는 V10 파일을 남겨 둔다.
    - 2026-10-02 같은 날 V11(공모주 공모 종류·지표 출처, `ipo-demand-forecast.md`)이 들어왔다. 이제 이 변경만 되돌릴 때는 V10 파일을 남겨 둔다.
  - 테이블까지 지우려면 백업 후 수동으로 지운다: `DROP TABLE stock_names;`, `DELETE FROM flyway_schema_history WHERE version = '10';`
- **API:** 필드를 추가만 했다. 그래서 화면만 먼저 되돌려도 동작한다.

## 10. 변경 파일

- **신규(운영):**
  - common: `common/util/StockNames`
  - market: `market/StockNameDirectory`, `market/StockNameEntity`, `market/StockNameRepository`
  - 마이그레이션: `db/migration/V10__stock_names.sql`
  - 화면: `frontend/src/stock.ts`, `frontend/src/components/StockLabel.tsx`
- **수정(운영):**
  - 시세 어댑터: `KiwoomMarketDataAdapter`(ka10001 이름 학습)
  - 잔고 복원: `PositionRestorer`(kt00018 이름 학습)
  - 알림·이벤트: `TradeNotificationListener`, `TelegramCommandPoller`, `DailyReportScheduler`, `DisclosureBlacklistListener`, `EventFeed`
  - API·View: `DashboardFacade`, `DashboardController`, `OrderHistoryController`, `DecisionController`, `SlippageTracker`, `PositionView`, `OrderHistoryItemView`, `SignalDecisionView`
  - 로그 표기: 5절 "로그" 목록
  - 화면: `types.ts`, `PositionsCard`, `SlippageCard`, `TestSignalCard`, `OrdersPage`, `DecisionsPage`, `index.css`, 빌드 산출물 `app/src/main/resources/static`
- **문서:** `README.md`(목록), `RUNBOOK.md` 5절 C3 요약 형식, `run-summary-logs.md`(지수 표기 주석), 분봉 마이그레이션 번호 V10→V11(`data-platform-postgres.md`, `external-data-files.md`, `upgrade-2026-10/12-decisions.md`)
- **테스트:**
  - 신규:
    - `StockNamesTest` 9건
    - `StockNameDirectoryTest` 10건 — 기동 적재, 모르는 종목 조회, 실패 후 30분 대기, 이름 없는 응답, 5건 상한, 지연 저장, 저장 실패, 7일 재조회와 DART→키움 교체, 08:10 갱신, 종료
    - `StockNameRepositoryDbTest` 2건 — upsert 넣기·바꾸기, 재조회 대상
    - 사전 초기화 확장과 등록 파일
  - 단언 추가: `TradeNotificationListenerTest`, `TelegramCommandPollerPollTest`, `DailyReportSchedulerTest`(+1건, 슬리피지 최대 종목), `EventFeedTest`(+1건, 미확인 표기), `DashboardFacadeTest`, `OrderHistoryControllerTest`, `DecisionControllerTest`, `DashboardControllerQuoteTest`(+1건, 사전 대체), `PositionRestorerTest`, `SlippageTrackerTest`, `KiwoomMarketDataAdapterTest`, `DisclosureBlacklistListenerTest`(+1건, 키움 이름 우선), `C3LiveStrategyTest`(실행 요약 국면 지수)
  - 기대값 변경: `SchemaAndTimeZoneDbTest`(최신 마이그레이션 10)

## 11. 검증 상태

- **컨테이너:**
  - 전체 테스트 통과: app 626건, common 63건. 건너뛴 16건은 실데이터·스모크 테스트다.
  - DB 테스트는 내장 PostgreSQL로 V10 적용, upsert, 조회를 확인했다.
  - 화면: `tsc`와 `vite build` 통과.
- **미검증(운영) — 장 마감 후 재기동하고 확인한다:**
  1. 기동 로그 `종목명 사전 0건 불러옴 — 설정 종목 5개 중 조회 대기 5건`(첫 기동)
     - 몇 초 뒤 `종목명 조회 실패` WARN이 없어야 한다.
     - 다음 기동부터는 `N건 불러옴`으로 바뀐다.
  2. 텔레그램 `/status`: `- 삼성전자(005930) 19주 @ …`
  3. 대시보드의 보유 포지션, 주문 이력, 판단 근거가 `삼성전자(005930)` 형식이다.
  4. DB: `select * from stock_names;`에 `KIWOOM` 행이 있다.
  5. ka10001이 ETF(069500)에도 `stk_nm`을 주는지 본다. 기존 실측(9/18)은 005930 기준이다.
