# 10. FE 코드 감사 (2026-10-01)

> 문서 묶음: [00-README](00-README.md) · [01 현황](01-current-state.md) · 조사 [02 BE](02-research-be.md) · [03 FE](03-research-fe.md) · [04 디자인](04-research-design.md) · [05 기획](05-research-planning.md) · [06 인프라](06-research-infra.md) · [07 주식거래①시장·제도](07-research-trading-market.md) · [08 주식거래②전략·LLM](08-research-trading-strategy.md) · 감사 [09 BE](09-audit-be.md) · [10 FE](10-audit-fe.md) · **[11 실행 계획](11-execution-plan.md)** · [12 결정 목록](12-decisions.md)


- 대상: `/home/claude/autostock/frontend` — Vite 5.4.21 + React 18.3.1 + TypeScript 5.9.3 + TanStack Query 5.102.8 + recharts 2.15.4 (package-lock 해석 버전). 빌드 산출물은 `app/src/main/resources/static`에 커밋.
- 기준 문서: PLAN.md ADR-10(FE React 전환·재전환 금지·전역 상태 라이브러리 미도입·라우터는 화면 3개 시점 react-router), docs/ARCHITECTURE.md 10절(CQRS Lite, Backend가 Source of Truth), aiDoc/claude-rules/coding-rules.md §16(웹 접근성 KWCAG 2.2/WCAG 2.2 AA)·§17(웹 호환성).
- 근거 표기: **확인(파일:줄)** = 코드에서 직접 확인, **추론** = 코드·문서에서 합리적으로 유도.
- 시각화 없음(표·목록만).

---

## 1. 구조 지도

### 1.1 페이지·컴포넌트 트리

| 계층 | 파일 | 역할 | 근거 |
|---|---|---|---|
| 엔트리 | `src/main.tsx` | `QueryClient` 기본 옵션으로 생성 → `QueryClientProvider` → `<App/>`, `StrictMode` | 확인(main.tsx:7-15) |
| 루트 | `src/App.tsx` | `useState<Tab>` 탭 상태, 대시보드 단일 쿼리, 탭별 조건부 렌더 | 확인(App.tsx:22-62) |
| 공통 | `Header.tsx` | 제목·운영상태 뱃지·마지막 갱신 시각·부제 | 확인(Header.tsx:10-21) |
| 공통 | `TabNav.tsx` | 5개 탭(대시보드/주문 이력/성과/판단 근거/공모주) `<nav>` + `<button>` | 확인(TabNav.tsx:4-32) |
| 대시보드 카드 | `OperationCard` | 시작/정지 mutation → `['dashboard']` invalidate | 확인(OperationCard.tsx:15-24) |
| 대시보드 카드 | `KillSwitchCard` | 비상 정지/해제 mutation | 확인(KillSwitchCard.tsx:9-13) |
| 대시보드 카드 | `PerformanceCard` | 오늘 주문 수·실현손익·보수모드·공시 배제 건수 | 확인(PerformanceCard.tsx:7-34) |
| 대시보드 카드 | `SlippageCard` | 체결 건수·평균/최대 bps, 5bps 초과 경고색 | 확인(SlippageCard.tsx:8-12) |
| 대시보드 카드 | `SystemCard` | SIM/LIVE 뱃지, C3·WS on/off 점 | 확인(SystemCard.tsx:11-28) |
| 대시보드 카드 | `TestSignalCard` | 종목/방향/기준가/수량 폼 + 5초 시세 폴링 + 시그널 발행 mutation | 확인(TestSignalCard.tsx:9-41) |
| 대시보드 카드 | `PositionsCard` | 보유 포지션 표 | 확인(PositionsCard.tsx:7-40) |
| 대시보드 카드 | `EventFeedCard` | 타입 필터(span) · 일시정지 · 신규 2초 하이라이트 | 확인(EventFeedCard.tsx:15-103) |
| 페이지 | `pages/OrdersPage.tsx` | 기간 select(7/30/90) · 새로고침 · 취소(window.confirm) | 확인(OrdersPage.tsx:23-138) |
| 페이지 | `pages/PerformancePage.tsx` | 기간 select(30/90) · recharts ComposedChart ×2 | 확인(PerformancePage.tsx:41-148) |
| 페이지 | `pages/DecisionsPage.tsx` | 날짜 input · 지평 서브탭(데이터 있는 지평만 활성) · DecisionRow | 확인(DecisionsPage.tsx:31-140) |
| 페이지 | `pages/IpoPage.tsx` | 상태 서브탭 · IpoRow(div onClick 펼침) · MetricsSection/RecordSection 폼 | 확인(IpoPage.tsx:38-250) |
| 뱃지 | `OrderStatusBadge`, `DecisionConclusionBadge`, `IpoDdayBadge`, `IpoRecommendationBadge` | 상태→색상 클래스 + 한글 라벨 | 확인(각 파일) |
| 타입/API | `src/types.ts`, `src/api.ts` | BE View DTO 대응 타입, `fetch` 래퍼 | 확인(types.ts, api.ts) |

총 소스 2,264줄(index.css 620줄 포함), 컴포넌트 14개·페이지 4개.

### 1.2 데이터 흐름 (쿼리 키 · 폴링 · 무효화)

| 쿼리 키 | 엔드포인트 | 폴링/재조회 조건 | 근거 |
|---|---|---|---|
| `['dashboard']` | `GET /api/dashboard` | 대시보드 탭 활성 시 2초(`refetchInterval: tab==='dashboard' ? 2000 : false`), 다른 탭이면 정지. 창 비활성 시 TanStack 기본(`refetchIntervalInBackground=false`)으로 정지(추론) | 확인(App.tsx:25-29) |
| `['quote', symbol]` | `GET /api/dashboard/quote/{symbol}` | 6자리 숫자 종목일 때만 `enabled`, 5초 폴링, 포커스 재조회 끔. 카드 언마운트(탭 전환) 시 정지 | 확인(TestSignalCard.tsx:17-24) |
| `['orders', days]` | `GET /api/orders?days=` | 폴링 없음, 탭 진입/기간 변경/수동 새로고침 | 확인(OrdersPage.tsx:27-31) |
| `['performance-daily', days]` | `GET /api/performance/daily?days=` | 동상 | 확인(PerformancePage.tsx:44-48) |
| `['decisions', date]` | `GET /api/decisions?date=` | 동상(날짜 변경 시) | 확인(DecisionsPage.tsx:35-39) |
| `['ipo', statusTab]` | `GET /api/ipo?status=` | 동상(상태 탭 변경 시) | 확인(IpoPage.tsx:42-46) |

| 명령(mutation) | 엔드포인트 | 무효화 시점 | 근거 |
|---|---|---|---|
| 시작/정지 | `POST /api/trading/start\|stop` | `onSettled` → `['dashboard']` 즉시 | 확인(OperationCard.tsx:16-19) |
| 킬스위치 | `POST /api/dashboard/killswitch` | `onSettled` → `['dashboard']` | 확인(KillSwitchCard.tsx:11-13) |
| 테스트 시그널 | `POST /api/dashboard/test-signal` | `onSettled` → 500ms 뒤 `['dashboard']`,`['orders']` (타이머 언마운트 시 미해제) | 확인(TestSignalCard.tsx:32-41) |
| 주문 취소 | `POST /api/dashboard/orders/{id}/cancel` | `onSettled` → 700ms 뒤 `['orders']` | 확인(OrdersPage.tsx:33-39) |
| 공모주 지표/기록 | `POST /api/ipo/{id}/metrics\|record` | `onSuccess` → `['ipo']` | 확인(IpoPage.tsx:158-165, 215-226) |

- 낙관적 업데이트 없음 — 모든 명령 후 서버 재조회(ARCHITECTURE 10절 "Backend가 Source of Truth" 준수). 확인(OperationCard.tsx:9-13 주석, KillSwitchCard.tsx:8).
- `QueryClient`가 기본 옵션이라 조회 실패 시 3회 지수 백오프 재시도 후에야 `isError` → 연결 실패 배너는 약 7초 뒤 표시(추론, TanStack 기본 retry=3). mutation은 기본 retry=0이라 주문 이중 발행 위험은 없음(추론).
- 테스트 시그널이 RiskGate에서 거부되면 `SignalDecision(REJECTED)`만 발행되고 EventFeed에는 실리지 않음 → 대시보드에서는 아무 반응이 없고 "판단 근거" 탭(TEST 지평)에서만 보임. 확인(RiskGate.java:372-381, EventFeed.java:46-66 — SIGNAL/ORDER/FILL 3종만). FE는 `['decisions']`를 무효화하지 않음(확인 TestSignalCard.tsx:36-39).

### 1.3 상태 관리 · 라우팅 · 스타일링

| 항목 | 현황 | 근거 |
|---|---|---|
| 서버 상태 | TanStack Query만 사용, 전역 상태 라이브러리 없음(ADR-10·ADR-6 준수) | 확인(package.json:11-16) |
| 클라이언트 상태 | 컴포넌트 `useState`(탭, 필터, 폼, 펼침). Context 없음. | 확인(App.tsx:22, EventFeedCard.tsx:16-20 등) |
| 라우팅 | 라우터 라이브러리 없음. `useState<Tab>` 조건부 렌더. URL/뒤로가기/새로고침 시 탭 유실. ADR-10은 "화면 3개 시점 react-router"를 명시했으나 5개 화면에서도 미도입(주석에서 "번들 비대화" 이유로 보류 명시) | 확인(TabNav.tsx:1-3, App.tsx:20-21) |
| 스타일링 | 단일 전역 `index.css`(620줄), CSS 변수 10개 토큰 + 다수 리터럴 hex, 인라인 style 산재(`style={{ marginTop: 12 }}`, `maxWidth: 1100`) | 확인(index.css:2-13, OperationCard.tsx:30, OrdersPage.tsx:44) |
| 디자인 토큰 | `--bg --card --line --text --dim --accent --danger --ok --warn-bg --warn-fg`. 그러나 `#1e4a35/#7fe0b0`(ok 뱃지) 조합이 7곳, `#4a1e1e/#ff8a8a` 5곳, `#2a3550` 8곳에 리터럴 반복 — 의미 토큰(`--ok-bg/--ok-fg` 등) 부재 | 확인(index.css:152-163, 247-270, 405-420, 513-532, 557-596) |
| 차트 색 | TSX에 리터럴 중복(`COLORS` 객체) — CSS 변수와 수동 동기화 | 확인(PerformancePage.tsx:22-31) |
| 다크모드 | 다크 단일 테마 고정. `prefers-color-scheme`·라이트 테마 없음 | 확인(index.css 전체에 `@media (prefers-color-scheme)` 없음) |
| 반응형 | 브레이크포인트 1개(720px): body padding 24→12, `.grid` 2열→1열 | 확인(index.css:27-31, 70-74) |
| 모션 | `feed-highlight` 2초, `.ipo-dday-active` 무한 alternate 애니메이션. `prefers-reduced-motion` 미대응 | 확인(index.css:123-135, 584-588) |
| 폰트 | `'Segoe UI', system-ui, sans-serif`, 본문 px 단위(11~20px), 11px 텍스트 4곳 | 확인(index.css:23, 149, 400, 508, 552) |

---

## 2. API 계약 대조표 (FE `types.ts` ↔ BE View DTO)

Jackson 설정: `spring.jackson.time-zone: UTC`, Boot 기본으로 `Instant`는 ISO-8601 문자열, `BigDecimal`은 JSON 숫자로 직렬화(추론 — 커스텀 모듈은 값 객체만 등록, JacksonConfig.java:13-45). FE는 `string | number`로 양쪽을 허용해 놓았다(확인 types.ts:17, 30 등).

### 2.1 필드별 대조

| FE 타입 | BE DTO | 결과 | 비고/근거 |
|---|---|---|---|
| `DashboardView{positions,recentEvents,trading,system,slippage}` | `DashboardView` 5필드 | 일치 | 확인(types.ts:48-54 ↔ DashboardView.java:18-24) |
| `Position{symbol,quantity:number,avgPrice:string\|number\|null}` | `PositionView(String,long,BigDecimal)` | 일치 | avgPrice null 처리 FE에서 `—` 표시. 확인(types.ts:14-18, PositionsCard.tsx:33) |
| `DashboardEvent{type,summary,at}` | `EventFeed.FeedItem(String,String,Instant)` | 일치 | `EventType`이 `'SIGNAL'\|'ORDER'\|'FILL'\|string`로 열려 있음 — BE도 3종만 발행. 확인(types.ts:12, EventFeed.java:48-62) |
| `TradingInfo` 6필드 | `TradingStatusView` 6필드 | 일치 | status enum 6값 동일. 확인(types.ts:4-33 ↔ TradingStatusView.java:17-24, TradingSystemStatus.java) |
| `SystemInfo{executionMode,c3Enabled,wsEnabled}` | `SystemStatusView` | 일치 | 확인(types.ts:35-39 ↔ SystemStatusView.java:17) |
| `SlippageInfo{fills,avgBps,maxBps,maxBpsSymbol:string}` | `SlippageSummary(long,double,double,String)` | 일치 | BE는 체결 없을 때 `maxBpsSymbol=""`(null 아님). 확인(SlippageTracker.java:65,131,144) |
| `QuoteView` 8필드 | `DashboardController.QuoteView` 8필드 | 일치 | 실패 시 BE가 빈 View(null 필드) 반환 → FE `-` 표시. 확인(DashboardController.java:130-149) |
| `OrderHistoryItem` 9필드, `side: Side`, `status: OrderStatus` | `OrderHistoryItemView(String… long, long, BigDecimal, String, String, Instant)` | 일치 | side/status는 BE String이나 enum name 그대로. 확인(types.ts:86-96 ↔ OrderHistoryItemView.java:15-25) |
| `DailyPerformance` 8필드 | `DailyPerformanceView` 8필드 | 일치 | `tradeDate` LocalDate→"YYYY-MM-DD". 확인(types.ts:103-112 ↔ DailyPerformanceView.java:14-23) |
| `DecisionItem` 7필드 | `SignalDecisionView` 7필드 | 일치 | `metrics: Record<string,string>` ↔ `Map<String,String>`. 확인(types.ts:122-130 ↔ SignalDecisionView.java:15-23) |
| `IpoDeal` 23필드 | `IpoDealView` 23필드 | 일치 | `id:number` ↔ `Long`(2^53 이내 안전). `IpoStatus` 4값·`IpoRecommendation` 3값 BE enum과 동일. 확인(types.ts:133-164 ↔ IpoDealView.java:8-32, IpoStatus.java, IpoRecommendation.java) |
| `IpoRecordInput` 6필드 | `IpoRecordRequest` 6필드 | 일치 | 확인(types.ts:167-174 ↔ IpoRecordRequest.java:8-15) |
| `IpoMetricsInput{institutionalCompetitionRate:number, lockupCommitRate:number}` | `IpoMetricsRequest(BigDecimal, BigDecimal, **LocalDate listingDate**)` | **불일치** | BE는 2026-09-23에 `listingDate` 수동 입력을 추가했으나 FE 타입·폼에 없음. 화면은 상장일을 "미확정(수동 입력 필요)"로 표시하면서 입력 수단이 없음. 또한 FE는 두 값을 필수(둘 다 채워야 활성)로 다루나 BE는 셋 다 선택(부분 갱신). 확인(types.ts:177-180 ↔ IpoMetricsRequest.java:16-20, IpoPage.tsx:135, 191) |
| `sendTestSignal(symbol, side, price:string, quantity:string)` | `TestSignalRequest(@Pattern symbol, side, price `[1-9]\d{0,8}(\.\d{1,4})?`, quantity `\|[1-9]\d{0,8}`)` | 일치(형식) | FE는 형식 검증 없음(정규식 미적용) — "1,000" 같은 입력은 400. 확인(api.ts:99-100 ↔ DashboardController.java:26-27,160-165) |
| `setKillSwitch({engage})` | `KillSwitchRequest(@NotNull Boolean engage)` | 일치 | 확인(api.ts:96-97 ↔ DashboardController.java:171) |
| 시작/정지 응답 `{status}` / 킬스위치 `{engaged}` / 시그널 `{accepted}` | `StatusResponse`, `KillSwitchView`, `TestSignalResponse` | FE가 본문 무시(재조회 원칙) | 확인(api.ts:72-83, 94-97) |
| (미사용) | `GET /api/dashboard/positions`, `/events`, `/killswitch` | FE 미호출 | 확인(DashboardController.java:57-72) |

### 2.2 에러 처리 (RFC 9457 ProblemDetail)

| 항목 | 현황 | 근거 |
|---|---|---|
| BE | `ApiExceptionHandler`가 `application/problem+json`으로 `title/status/detail/code`와 확장 필드(`errors[{field,message}]`, `currentStatus`, `allowed`) 제공 | 확인(ApiExceptionHandler.java:14-70, TradingSystemController.java:39-40, IpoController.java:63-64) |
| FE | **본문을 읽지 않음**. `if (!res.ok) throw new Error('…: HTTP ' + res.status)` — 상태 코드만 메시지화. 필드 오류·현재 상태·허용값이 모두 버려짐 | 확인(api.ts:17-19, 26-28, 78-81) |
| 명령 실패 표시 | `OperationCard`, `KillSwitchCard`, `TestSignalCard`는 `mutation.isError`를 렌더하지 않음 → 400/409/502가 **무음 실패**. `OrdersPage`(취소)·`IpoPage`(지표/기록)만 표시 | 확인(OperationCard.tsx:26-51, KillSwitchCard.tsx:15-33, TestSignalCard.tsx:46-97 vs OrdersPage.tsx:66-73, IpoPage.tsx:197-201) |
| 조회 실패 표시 | 각 페이지 `error-banner`(`role="alert"` 없음) | 확인(App.tsx:40-44, OrdersPage.tsx:61-65 등) |
| §16.2 정합 | BE는 "필드 단위 오류"를 주지만 FE가 표시하지 않아 규칙 취지(사용자가 고칠 수 있게) 미달 | 추론 |

### 2.3 로딩/에러/빈 상태

| 화면 | 로딩 | 에러 | 빈 상태 | 근거 |
|---|---|---|---|---|
| 대시보드 | 카드별 `'확인 중...'`/`'-'` | 상단 배너 | 포지션 "보유 없음", 피드 "이벤트 없음", 슬리피지 "체결 없음" | 확인(PositionsCard.tsx:20-27, EventFeedCard.tsx:85-88, SlippageCard.tsx:17-20) |
| 주문 이력 | 표 안 "조회 중..." | 배너 + 취소 실패 배너 | "선택한 기간 동안 주문 없음" | 확인(OrdersPage.tsx:91-100) |
| 성과 | "조회 중..." | 배너 | "모의 운영 시작 후 축적됩니다." | 확인(PerformancePage.tsx:92-95) |
| 판단 근거 | "조회 중..." | 배너 | 날짜별 안내 문구 | 확인(DecisionsPage.tsx:98-101) |
| 공모주 | "조회 중..." | 배너 | 배치 시각 안내 | 확인(IpoPage.tsx:88-91) |
| 테스트 시그널 | 시세 "시세 조회 중..." | 시세 실패 문구만, **발행 실패 표시 없음**, 성공 표시도 없음 | — | 확인(TestSignalCard.tsx:74-86) |

---

## 3. UX 감사

### 3.1 위험 동작의 확인 단계 · 되돌리기 · 오입력 방지

| 동작 | 위험도 | 확인 단계 | 되돌리기 | 오입력 방지 | 근거 |
|---|---|---|---|---|---|
| 테스트 시그널 발행(실주문 경로) | **최고**(LIVE면 실거래) | 없음 — 버튼 1클릭 | 없음(체결되면 반대매매만 가능) | 종목 기본값 `'005930'` 선입력, 기준가 자동=현재가, 수량 공란=자동 사이징(예산 = equity×10%×confidence). 즉 **페이지 열고 버튼 한 번이면 삼성전자를 예산 상한까지 매수** — PROGRESS "11:23 193주(5,000만 원) 즉시 체결 사고"의 직접 원인. 수량·가격 형식 검증 없음(BE 정규식만) | 확인(TestSignalCard.tsx:10-13, 28-30, 87-92; RiskGate.java:144-150, 223-226; PROGRESS.md:181) |
| 매매 시작 | 높음(LIVE) | 없음 | 정지 버튼 | 상태 기반 disabled만 | 확인(OperationCard.tsx:23-43) |
| 매매 정지 | 낮음 | 없음 | 시작 | — | 동상 |
| 킬스위치 비상 정지 | 낮음(안전 방향) | 없음(적절 — 비상 동작은 1클릭이어야 함) | 해제 | — | 확인(KillSwitchCard.tsx:25-27) |
| 킬스위치 **해제** | 높음(자동 주문 재개) | 없음 | 재작동 | — | 확인(KillSwitchCard.tsx:28-30) |
| 주문 취소 | 중간 | `window.confirm` | 없음(취소는 비가역) | 취소 가능 상태에서만 버튼 노출 | 확인(OrdersPage.tsx:11-15, 117-129) |
| 공모주 기록/지표 저장 | 낮음 | 없음 | 재입력 | 빈 값이면 null(부분 갱신) | 확인(IpoPage.tsx:215-224) |

추가 관찰:
- `TestSignalCard`는 실행 모드(SIM/LIVE)를 모른다(prop 없음) → LIVE에서도 동일 UI. LIVE 경고는 `SystemCard`에만 있음. 확인(App.tsx:51-52, SystemCard.tsx:27).
- 헤더 부제가 "SIM 모드 검증용"으로 하드코딩 — LIVE에서도 그대로 표시되어 오판 유도. 확인(Header.tsx:19).
- `OperationCard`의 안내 문단은 개발자 주석(ARCHITECTURE.md 10절 인용)이 사용자 문구로 노출됨. 확인(OperationCard.tsx:45-50).
- 발행 성공/거부 피드백 부재: 응답 `{accepted:true}`는 무시되고, RiskGate 거부는 피드에 실리지 않음(2절 참조).

### 3.2 숫자·시각·통화 표기 일관성

| 항목 | 현황 | 근거 |
|---|---|---|
| 통화 단위 | `PerformanceCard`·`IpoPage`는 `원` 접미, `OrdersPage` 가격·`PositionsCard` 평단·시세·차트 툴팁은 단위 없음 | 확인(PerformanceCard.tsx:17, IpoPage.tsx:20, OrdersPage.tsx:110, PositionsCard.tsx:33, TestSignalCard.tsx:43-44, PerformancePage.tsx:107) |
| 로캘 | `toLocaleString()` 인자 없음 → 브라우저 로캘 의존(`ko-KR` 명시 없음) | 확인(위 동일 줄) |
| 시각/시간대 | `new Date(x).toLocaleString()/toLocaleTimeString()` — 브라우저 시간대. 반면 `DecisionsPage`·`IpoDdayBadge`는 `Intl`로 KST 명시 — 방식 혼재. 운영 PC가 KST라 현재는 동작하나 §17.6 "클라이언트가 로캘로 형식화" 취지상 공용 포매터 필요 | 확인(EventFeedCard.tsx:96, OrdersPage.tsx:104, DecisionsPage.tsx:23,123, IpoDdayBadge.tsx:3-6) |
| 날짜 | 공모주 날짜는 원문 "YYYY-MM-DD" 그대로, 주문 시각은 로캘 문자열 | 확인(IpoPage.tsx:132-135, OrdersPage.tsx:104) |
| bps 소수 자릿수 | 카드 `toFixed(1)`, 차트 툴팁 `toFixed(2)` | 확인(SlippageCard.tsx:27,31 vs PerformancePage.tsx:131) |
| 부호/색 | 손익은 색 + 부호 숫자(색만 의존 아님) | 확인(PerformanceCard.tsx:16-18) |
| 수량 | 천 단위 구분 없음(`{o.quantity} / {o.filledQuantity}`) | 확인(OrdersPage.tsx:108) |

### 3.3 신규 이벤트 하이라이트

- 키 `${at}|${type}|${summary}`로 신규 판별, 2초 `feed-highlight` 애니메이션, 타이머 언마운트 시 정리. 확인(EventFeedCard.tsx:11-59).
- 일시정지 중에는 `knownKeys` 갱신을 건너뛰어 재개 시 누적 신규가 한꺼번에 하이라이트(의도된 동작으로 보임, 추론).
- 같은 시각·같은 요약 이벤트 2건이면 React key 중복 경고 가능(추론). BE `FeedItem`에 id가 없음(확인 EventFeed.java:40).
- 시각 장애 사용자에게는 색 애니메이션이 유일한 신호 — `aria-live` 없음(4절).

### 3.4 모바일 사용성

| 항목 | 현황 | 근거 |
|---|---|---|
| 접근 경로 | BE가 `server.address: 127.0.0.1` 루프백 전용 — 폰에서 직접 접속 불가(인증 도입 전 의도적) | 확인(application.yml server 절 주석) |
| 탭 내비 | `.tab-nav`에 `flex-wrap`/`overflow-x` 없음 → 5개 탭이 360px 폭에서 넘침(추론: 14px 글자·14px 패딩 × 5) | 확인(index.css:334-340) |
| 표 | 주문 이력만 `overflowX: auto`; 9열이라 좁은 화면에서 가로 스크롤 필수. 포지션·지표 표는 폭 초과 시 대응 없음 | 확인(OrdersPage.tsx:75, PositionsCard.tsx:11) |
| 입력 | 숫자 입력이 `type="text"`·`inputMode` 없음 → 모바일에서 문자 키보드 | 확인(TestSignalCard.tsx:50-72, IpoPage.tsx:180-189, 232-239) |
| 터치 타깃 | 버튼 `min-height:40px` 양호. `.tab`(span) 약 26px, `.horizon-sub-nav-btn` 약 26px — WCAG 2.2 2.5.8(24px) 경계 | 확인(index.css:188-197, 310-319, 440-448) |
| 고정 폭 | 인라인 `maxWidth: 1100`, 수량 입력 `maxWidth: 140` | 확인(OrdersPage.tsx:44, TestSignalCard.tsx:71) |
| 차트 | `ResponsiveContainer width=100%` 양호, 높이 고정 280/240 | 확인(PerformancePage.tsx:99,124) |

---

## 4. 접근성 감사 (KWCAG 2.2 / WCAG 2.2 AA)

### 4.1 항목별 판정

| 지침 | 판정 | 근거 |
|---|---|---|
| 1.1.1 대체 텍스트 | **미흡** — recharts SVG에 `role/aria-label`·대체 데이터 표 없음. `accessibilityLayer` 미설정(v2 기본 false) | 확인(PerformancePage.tsx:99-143) |
| 1.3.1 정보와 관계(시맨틱) | **미흡** — 헤더가 `<div>`(`<header>` 아님), `<main>` 없음, 카드 `div.card`(`section` 아님), 표에 `caption`/`scope` 없음, 주문 표 마지막 `<th></th>` 빈 헤더 | 확인(Header.tsx:15, App.tsx:33-63, OrdersPage.tsx:87, PositionsCard.tsx:11-17) |
| 1.3.1/3.3.2 폼 레이블 | **불합격** — 모든 `<input>/<select>`가 `placeholder`만 사용, `<label>`·`aria-label` 없음(테스트 시그널 4개, 기간 select 2개, 날짜 2개, 공모주 8개) | 확인(TestSignalCard.tsx:50-72, OrdersPage.tsx:48, PerformancePage.tsx:73, DecisionsPage.tsx:66, IpoPage.tsx:180-189, 232-239) |
| 1.4.1 색에 의존 | 양호 — 상태 뱃지·on/off 점 모두 텍스트 병기 | 확인(SystemCard.tsx:22-25, OrderStatusBadge.tsx:13) |
| 1.4.3 텍스트 대비(4.5:1) | 대부분 통과. **불합격 3건**: 기본 버튼 `#fff/#4f8cff` 3.22:1, danger 버튼 `#fff/#ff5d5d` 3.01:1(13px·비굵게라 large text 아님), 중립 뱃지 `#8a95b0/#2a3550` 4.07:1(11-12px 굵게 — large 아님). disabled 버튼(opacity .5) 약 2.2:1, 비활성 지평 탭(opacity .35) 약 1.7:1(비활성은 예외지만 판독 불가 수준) | 계산: index.css:8-9,188-190,198-200,247-250,205-208,458-461 색상값으로 WCAG 상대휘도 산출 |
| 1.4.11 비텍스트 대비(3:1) | **미흡** — 입력 테두리 `--line #2a3550` vs 배경 1.31~1.51:1, 차트 그리드 1.31:1 | 계산: index.css:5,213-223, PerformancePage.tsx:29 |
| 1.4.4/1.4.12 텍스트 크기·간격 | 양호(px지만 브라우저 확대 가능). 11px 텍스트 4곳은 가독성 주의 | 확인(index.css:148,400,508,552) |
| 2.1.1 키보드 | **불합격** — 이벤트 필터 탭이 `<span onClick>`(포커스 불가), 공모주 행 펼침이 `<div onClick>`(포커스 불가) | 확인(EventFeedCard.tsx:70-78, IpoPage.tsx:111) |
| 2.4.2 페이지 제목 | 미흡 — 탭 전환 시 `document.title` 불변 | 확인(index.html:6, App.tsx) |
| 2.4.3/2.4.7 포커스 순서·표시 | 부분 — `outline: none` 없음(브라우저 기본 링 유지). `:focus-visible` 사용자 정의 없음. 다크 배경에서 기본 링 대비는 브라우저 의존(추론) | 확인(index.css 전체) |
| 2.4.1 블록 건너뛰기 / 랜드마크 | 미흡 — `<nav>` 1개만, `main/header` 없음 | 확인(TabNav.tsx:21) |
| 2.5.8 타깃 크기(24px) | 경계 통과(3.4절) | index.css:310-319 |
| 3.1.1 언어 | 양호 `lang="ko"` | 확인(index.html:2) |
| 3.2 예측 가능성 | 양호 — 탭은 `button`, 명령은 명시 버튼 | — |
| 3.3.1/3.3.3 오류 식별·제안 | **미흡** — 필드 단위 오류 미표시(2.2절), 명령 카드 3곳 오류 무음 | api.ts:78-81 |
| 4.1.2 이름·역할·값 | **미흡** — 탭 내비에 `role="tablist"/tab`·`aria-selected`·`aria-current` 없음, 서브탭 동일, 펼침 행 `aria-expanded` 없음, 일시정지 버튼 `aria-pressed` 없음, 슬리피지 설명이 `title` 속성만(터치·키보드 접근 불가) | 확인(TabNav.tsx:23-27, DecisionsPage.tsx:73-90, IpoPage.tsx:111-119, EventFeedCard.tsx:80, SlippageCard.tsx:26,30) |
| 4.1.3 상태 메시지(라이브 리전) | **불합격** — 오류 배너 `role="alert"` 없음, 로딩 `role="status"/aria-busy` 없음, 이벤트 피드 `aria-live` 없음, 명령 결과 안내 없음 | 확인(App.tsx:40-44, EventFeedCard.tsx:84-101) |
| 2.3.3 모션(AAA·KWCAG 권고) | 미흡 — 무한 애니메이션 뱃지, `prefers-reduced-motion` 없음 | 확인(index.css:584-588) |
| 1.3.5 입력 목적/자동완성 | 해당 없음(개인정보 입력 없음) | — |
| §16.1 차트 원본 수치 | BE는 수치를 JSON으로 제공(양호) — FE가 표로 노출하지 않음 | DailyPerformanceView.java |

### 4.2 라이브 리전 설계 메모(이벤트 피드)

- 2초 폴링 피드 전체를 `aria-live`로 두면 스크린리더가 과다 낭독 → 신규 항목만 별도 `aria-live="polite"` 영역에 요약 1줄("새 체결 1건: …")로 게시하고, "일시정지" 시 게시 중단이 적절(추론).
- 오류 배너 `role="alert"`, 로딩 `role="status"`, 명령 결과(발행됨/거부됨/409 현재 상태) `role="status"` 텍스트가 최소 요건.

---

## 5. 품질·빌드

### 5.1 테스트·린트·포맷

| 항목 | 현황 | 근거 |
|---|---|---|
| 단위/컴포넌트 테스트 | **없음** — `*.test.*` 0개, vitest/testing-library 미설치, `scripts`에 test 없음 | 확인(package.json:6-10, 파일 목록) |
| E2E | 없음 | — |
| ESLint | **없음** — 설정 파일 없음, 플러그인 미설치. 코드에 `eslint-disable-next-line react-hooks/exhaustive-deps` 주석만 존재(효력 없음) | 확인(EventFeedCard.tsx:52, frontend/ 파일 목록) |
| Prettier | 없음 | 확인(파일 목록) |
| CI | `ci.yml`은 Gradle 테스트만. FE 타입체크·빌드·"static 산출물이 소스와 일치하는지" 검증 없음 → 소스와 커밋된 번들 드리프트 위험 | 확인(.github/workflows/ci.yml:20-44) |
| TypeScript | `strict: true`, `noUnusedLocals/Parameters`, `noFallthroughCasesInSwitch`, `moduleResolution: bundler`, `isolatedModules`. `noUncheckedIndexedAccess` 없음. `include: ["src"]`라 `vite.config.ts`는 `tsc` 검사 대상 아님(tsconfig.node.json 부재) | 확인(tsconfig.json:2-19) |
| 빌드 스크립트 | `tsc && vite build` — 타입 오류 시 빌드 실패(양호) | 확인(package.json:8) |

### 5.2 번들 크기

| 산출물 | 원본 | gzip | 근거 |
|---|---|---|---|
| `assets/index-DEzaDXuB.js` | 619,107 B (605 KB) | 176,375 B (172 KB) | 확인(ls, gzip -c) |
| `assets/index-DQ51jXj5.css` | 7,590 B | 2,024 B | 확인 |
| `index.html` | 405 B | — | 확인 |

- E1 시점 PROGRESS 기록 "gzip 61KB"에서 recharts(+d3-shape, lodash 4.18.1, react-smooth 4.0.4 등 156 패키지) 도입 후 약 3배. 확인(PROGRESS.md:211, package-lock 해석).
- 단일 청크 — 성과 탭에서만 쓰는 recharts가 첫 로드에 포함. `React.lazy`로 분리 시 초기 gzip 약 60~70KB 수준으로 복귀 가능(추론).
- Vite 기본 500KB 청크 경고 임계(원본 605KB)를 넘김(추론).

### 5.3 의존성 버전 (2026-09 기준 npm `latest`, 레지스트리 조회)

| 패키지 | 사용(lock) | 최신 안정 | 배포일 | 주요 breaking change / 메모 |
|---|---|---|---|---|
| react / react-dom | 18.3.1 | **19.3.0** | 2026-09-09 | 19.0: `ReactDOM.render/hydrate/findDOMNode/string ref/legacy context/함수 컴포넌트 propTypes·defaultProps` 제거, `ref`가 일반 prop(`forwardRef` 불필요), `useRef` 인자 필수(타입), `@types/react` 19에서 `ReactElement.props`가 `unknown`. 19.2: Activity·useEffectEvent, 19.3: View Transitions·Fragment refs. 현 코드는 위 제거 API를 쓰지 않아 마이그레이션 부담 낮음(추론: `forwardRef`·propTypes 미사용 확인) |
| @tanstack/react-query | 5.102.8 | 5.104.0 | 2026-09-26 | 동일 메이저 — 패치 수준. v5 계약(객체 인자, `isPending`) 이미 준수 |
| recharts | 2.15.4 | **3.10.1** | 2026-07-25 | 3.0: `accessibilityLayer` 기본 true(키보드 내비 기본), `CategoricalChartState`/내부 props 제거, `TooltipProps`→`TooltipContentProps`, `ResponsiveContainer` ref 구조 변경, SVG 순서 = z-order, `react-smooth`·`recharts-scale` 내재화. 현 사용은 기본 컴포넌트만이라 API 표면은 좁지만 `Tooltip formatter` 타입 시그니처 재검토 필요(추론) |
| vite | 5.4.21 | **8.3.1** | 2026-09-24 | 6: 환경 API·Node 18+. 7(2025-06): Node 20.19+/22.12+, ESM 전용 배포, 기본 브라우저 타깃 `baseline-widely-available`. 8(2026-03-12): Rolldown 단일 번들러(esbuild/rollup 대체), Devtools, tsconfig paths 내장. 유지보수 라인: 8.3 정규, 7.3/8.2 백포트, 6.4/8.1 보안만 — **5.x는 지원 종료** |
| @vitejs/plugin-react | 4.7.0 | **6.1.1** | 2026-08-28 | 5: Vite 6/7 대응, 6: Babel→Oxc Refresh 변환(설치 크기 감소), Vite 8 전제 |
| typescript | 5.9.3 | **7.0.2** | 2026-07-08(레지스트리)·8월 GA 보도 | 6.0(2026-03, 마지막 JS 구현): `strict`·`esnext` 기본화, `moduleResolution node10/classic`·`baseUrl` 등 폐기 경고. 7.0: Go 네이티브 `tsc`(8~12배 빠름), 6.0 폐기 항목이 오류로, 프로그램 API 미완(7.1 예정). 권장 경로 5.9→6.0→7.0. 현 tsconfig는 `bundler`·`strict`라 충돌 없음(추론) |
| @types/react | 18.3.31 | 19.3.0 | — | React 19 동반 승급 |
| (신규 후보) vitest | — | 5.0.3 | 2026-09-30 | Vite 8 계열 대응 |
| (신규 후보) @playwright/test | — | 1.63.0 | — | 스모크 E2E |
| (신규 후보) react-router | — | 8.4.0 | 2026-09-15 | 7.x에서 `react-router-dom` 통합, 8.x 최신. 경량 탭 라우팅 대체 시 |
| (신규 후보) eslint | — | 10.11.0 | — | flat config 전용 |

권장 승급 순서(보강 정정): ① TS 5.9→**6.0.x에서 멈춤**(typescript-eslint가 TS <6.1만 지원 — [03 FE](03-research-fe.md) 보강), ② Vite 5→8 + plugin-react 6(plugin-react 6은 Vite 8 전제), ③ React 18→19.3 + @types 19, ④ recharts 2→3(차트 라이브러리 교체는 ADR-10 충돌). Node 20.19+/22.12+ 필요(빌드는 개발 PC에서만 수행, 호스트 node 불필요 구조는 유지 가능).

### 5.4 환경변수·프록시

| 항목 | 현황 | 근거 |
|---|---|---|
| 개발 프록시 | `/api` → `http://127.0.0.1:8080`(IPv6 `::1` 회피 이유 주석) | 확인(vite.config.ts:13-18) |
| 출력 경로 | `outDir: ../app/src/main/resources/static`, `emptyOutDir: true` | 확인(vite.config.ts:9-12) |
| 환경변수 | `.env`/`import.meta.env` 미사용, `base` 기본 `/`. 배포 경로·API 베이스 하드코딩 없음(상대 경로) | 확인(api.ts 전체, vite.config.ts) |
| 청크 전략 | `build.rollupOptions.manualChunks` 없음 | 확인(vite.config.ts) |
| SPA 폴백 | 라우터 없음 → 불필요. 라우터 도입 시 BE에 `/**`→`index.html` 포워딩 필요(BE 변경) | 추론 |

---

## 6. 개선 기회 목록

우선순위: P0 = 자금 손실·안전, P1 = 계약/접근성 불합격·유지보수, P2 = 개선. "충돌"은 ADR-10(FE 재전환 금지, 전역 상태 라이브러리 미도입, 라우터는 react-router) 및 ARCHITECTURE 10절(낙관적 업데이트 금지, View DTO)과의 관계.

| # | 우선 | 항목 | 영향 | 난이도 | BE 변경 | 기존 결정 충돌 |
|---|---|---|---|---|---|---|
| 1 | P0 | **테스트 시그널 2단계 확인**: 발행 전 `<dialog>`로 종목명·방향·기준가·수량·예상 금액(가격×수량, 자동 사이징이면 "자동(최대 N원)") 표시 후 확정. LIVE 모드면 빨간 배너 + 종목코드 재입력 확인 | 193주 사고 재발 차단 | 낮음 | 없음(예상 예산 표기를 원하면 `GET /api/dashboard`에 `maxBuyBudget` 추가 — 선택) | 없음 |
| 2 | P0 | **수동 주문 수량 필수화 + 기본 종목 공란**: `symbol` 기본값 `''`, `quantity` 필수·`inputMode="numeric"`·`pattern="[1-9][0-9]{0,8}"`, 가격도 BE 정규식과 동일 클라이언트 검증 | 오입력·무의식 발행 제거 | 낮음 | 선택(BE `TestSignalRequest.quantity`를 `@NotBlank`로 바꾸면 이중 방어) | 없음 — PROGRESS "수량 필수화 검토"와 일치 |
| 3 | P0 | **킬스위치 해제·매매 시작 확인 단계**(비상 정지는 1클릭 유지) | 자동 매매 재개 오조작 방지 | 낮음 | 없음 | 없음 |
| 4 | P0 | **명령 실패 표시 + ProblemDetail 파싱**: `postJson`에서 `application/problem+json` 본문(`title/detail/code/errors/currentStatus`) 읽어 `ApiError`로 throw, 3개 명령 카드에 `mutation.isError` 렌더, `errors[]`를 필드 옆에 표시 | 무음 실패 제거, §16.2 충족 | 낮음 | 없음(이미 제공) | 없음 |
| 5 | P0 | **LIVE 모드 인지**: `executionMode`를 App에서 헤더·TestSignalCard·OperationCard에 전달, 헤더 "SIM 모드 검증용" 하드코딩 제거, LIVE면 전역 상단 띠 | 실거래 오판 방지 | 낮음 | 없음 | 없음 |
| 6 | P1 | **FE-3 상장일 입력 추가**(`IpoMetricsInput.listingDate`), 지표 폼을 부분 갱신 규약과 일치(둘 중 하나만도 저장 가능) | 계약 불일치 해소, LISTED 상태 도달 가능 | 낮음 | 없음 | 없음 |
| 7 | P1 | **폼 레이블·시맨틱 랜드마크**: 모든 input에 `<label>`(시각 숨김 허용), `<header>/<main>/<section aria-labelledby>`, 표 `caption`·`scope`, 빈 `<th>`에 숨김 텍스트 | KWCAG 필수 항목 통과 | 낮음 | 없음 | 없음 |
| 8 | P1 | **키보드·ARIA**: 이벤트 필터 `span`→`button`(`role=tablist/tab`, `aria-selected`), 공모주 행 `button aria-expanded`, 탭 내비 `aria-current`, 일시정지 `aria-pressed`, `title` 툴팁을 가시 텍스트로 | 2.1.1/4.1.2 통과 | 낮음 | 없음 | 없음 |
| 9 | P1 | **라이브 리전**: 오류 `role="alert"`, 로딩 `role="status"`, 명령 결과 status 영역, 피드 신규 요약 `aria-live="polite"`(일시정지 연동) | 4.1.3 통과 | 낮음 | 없음 | 없음 |
| 10 | P1 | **색 대비 수정**: 버튼 글자 → `#0f1420` on accent(5.7:1) 또는 accent를 `#2f6fe0`급으로 어둡게, danger 버튼 동일, 중립 뱃지 글자 `#a9b3cc`급, `--line`을 `#3a4766`급으로(3:1), disabled는 opacity 대신 별도 색 | 1.4.3/1.4.11 통과 | 낮음 | 없음 | 없음 |
| 11 | P1 | **디자인 토큰 정리 + 다크/라이트**: 의미 토큰(`--ok-bg/fg --danger-bg/fg --warn-bg/fg --info-bg/fg --neutral-bg/fg`)으로 리터럴 20여 곳 치환, `prefers-color-scheme: light` 팔레트, 차트 색은 `getComputedStyle`로 토큰에서 읽기, `prefers-reduced-motion` | 유지보수·야간/주간 가독성 | 중간 | 없음 | 없음 |
| 12 | P1 | **CI에 FE 게이트**: `npm ci && npm run build` 후 `git diff --exit-code app/src/main/resources/static`(커밋 산출물 = 소스 보장), `tsc --noEmit`, lint | 산출물 드리프트 방지 | 낮음 | 없음(워크플로만) | 없음 |
| 13 | P1 | **테스트 도입**: vitest + @testing-library/react — `TestSignalCard`(확인 단계·필수 수량), `OperationCard`(상태별 disabled), `api.ts`(ProblemDetail 파싱), `IpoDdayBadge`(D-day 계산). Playwright 스모크 1건(대시보드 로드·탭 전환)은 BE mock 필요 | 회귀 방지 | 중간 | 없음 | 없음 |
| 14 | P1 | **ESLint 9(flat) + Prettier**: `typescript-eslint`, `react-hooks`, `jsx-a11y`(ESLint 10 미지원이라 9 고정 — 보강 확인) — 현 코드의 a11y 문제 상당수를 정적 검출 | 품질 자동화 | 낮음 | 없음 | 없음 |
| 15 | P1 | **거부 피드백**: 테스트 시그널 `onSettled`에서 `['decisions']`도 무효화하고 대시보드에 "최근 거부 사유" 1줄(오늘 TEST 지평 REJECTED 최신) 표시. 또는 BE `EventFeed`에 `REJECTED` 타입 추가 | "발행했는데 아무 일도 없음" 해소 | 낮음 | 선택(REJECTED 피드) | 없음 — CQRS Lite 유지 |
| 16 | P2 | **라우터 도입(react-router 8)**: 탭→URL(`/orders?days=7`, `/decisions?date=`), 새로고침·뒤로가기·북마크 유지, `document.title` 갱신 | 5화면 내비 품질 | 중간 | BE `/**`→`index.html` 포워딩 컨트롤러 1개 | 없음 — ADR-10이 "화면 3개 시점 react-router"를 이미 명시. 번들 +~15KB gzip(추론) |
| 17 | P2 | **코드 분할**: 성과 탭 `React.lazy(() => import('./pages/PerformancePage'))`, 공모주·판단 근거도 지연 | 초기 로드 ~60% 감소(추론) | 낮음 | 없음 | 없음 |
| 18 | P2 | **공용 포매터**(`format.ts`): `Intl.NumberFormat('ko-KR', {style:'currency', currency:'KRW'})`, `Intl.DateTimeFormat('ko-KR',{timeZone:'Asia/Seoul'})`, bps 소수 통일 | 표기 일관성, §17.6 | 낮음 | 선택(§10.2.3 "금액 문자열 십진수" 정합은 BE 과제) | 없음 |
| 19 | P2 | **의존성 승급**(5.3절 순서): TS 6→7, Vite 7→8 + plugin-react 6, React 19.3, recharts 3 | 지원 종료 라인 탈출(Vite 5) | 중간 | 없음 | 없음 — ADR-10 "재전환 금지"는 스택 교체 금지이지 버전 승급 금지 아님(추론) |
| 20 | P2 | **차트 접근성/대체**: recharts 3 `accessibilityLayer`(기본 true) 활용 + 표 토글(원본 수치는 이미 API에 있음). 라이브러리 교체(예: 경량 SVG 직접 그리기)는 번들 절감 크지만 ADR-10 "차트는 recharts" 결정과 충돌 | 1.1.1 통과, 번들 | 중간 | 없음 | **부분 충돌**(교체 시) — recharts 유지 + a11y 옵션이 무충돌 경로 |
| 21 | P2 | **실시간(폴링→SSE) 검토**: `GET /api/dashboard/stream`(Spring `SseEmitter`)로 이벤트 push, TanStack `queryClient.setQueryData`로 반영. 현 2초 폴링은 단일 운영자·루프백 환경에서 비용 미미 — 체결 반영 지연(≤2s+500ms 무효화 지연)이 문제될 때만 | 지연 감소 | 높음 | **필요**(SSE 엔드포인트, 이벤트 직렬화) | 없음(전역 상태 없이 가능). 우선순위 낮음 |
| 22 | P2 | **모바일 대응**: `.tab-nav` `overflow-x:auto`/wrap, 표 카드형 전환(<720px), `inputMode` | 폰 열람 | 낮음 | 없음 | 없음 |
| 23 | P2 | **PWA/모바일 알림**: 루프백 바인딩·무인증 상태라 원격 접근 자체가 막혀 있음 → 인증(Spring Security) 선행 없이는 불가. 알림은 이미 텔레그램 경로 존재 | — | 높음 | **필요**(인증·공개 바인딩·Web Push) | **충돌** — application.yml "원격 접속 필요 시 인증 먼저" 결정과 순서 종속. 보류 권고 |
| 24 | P2 | **UX 카피 정리**: OperationCard 개발자 주석 문구 제거, 헤더 부제 동적화, 성공 토스트("시그널 발행됨 — RiskGate 판정 대기") | 신뢰성 | 낮음 | 없음 | 없음 |
| 25 | P2 | **tsconfig 보강**: `noUncheckedIndexedAccess`, `tsconfig.node.json`으로 `vite.config.ts` 검사, `types.ts`의 `string \| number` 이중 타입을 BE 직렬화 확정(숫자)에 맞춰 단순화 | 타입 정확도 | 낮음 | 없음 | 없음 |

---

## 7. 요약: 상위 10개 개선 기회

| 순위 | 항목 | 우선 | 핵심 근거 | BE 변경 | 결정 충돌 |
|---|---|---|---|---|---|
| 1 | 테스트 시그널 발행 전 확인 다이얼로그(예상 금액·LIVE 경고) | P0 | TestSignalCard.tsx:87-92 무확인 1클릭, PROGRESS.md:181 사고 | 없음 | 없음 |
| 2 | 수량 필수화·기본 종목 공란·클라이언트 형식 검증 | P0 | TestSignalCard.tsx:10-13, RiskGate.java:146-150 자동 사이징 | 선택 | 없음 |
| 3 | 킬스위치 해제·매매 시작 확인 단계 | P0 | KillSwitchCard.tsx:28-30, OperationCard.tsx:31-35 | 없음 | 없음 |
| 4 | ProblemDetail 파싱 + 명령 카드 오류 표시 | P0 | api.ts:78-81, 3개 카드 `isError` 미렌더 | 없음 | 없음 |
| 5 | LIVE 모드 전역 인지(헤더 하드코딩 제거) | P0 | Header.tsx:19, App.tsx:51-52 | 없음 | 없음 |
| 6 | 폼 레이블·랜드마크·키보드·ARIA·라이브 리전 일괄 수정 | P1 | 4.1절 불합격 항목(2.1.1, 3.3.2, 4.1.2, 4.1.3) | 없음 | 없음 |
| 7 | 색 대비 수정(버튼 3.2:1·3.0:1, 테두리 1.3:1) | P1 | index.css:8-9, 188-200, 213-223 | 없음 | 없음 |
| 8 | FE-3 `listingDate` 입력 추가(계약 불일치) | P1 | types.ts:177-180 ↔ IpoMetricsRequest.java:16-20 | 없음 | 없음 |
| 9 | CI FE 게이트(빌드 재현·diff 검사) + vitest/testing-library + ESLint(jsx-a11y) | P1 | ci.yml FE 단계 없음, 테스트·린트 0 | 없음 | 없음 |
| 10 | 디자인 토큰 정리·라이트 테마·코드 분할·의존성 승급(Vite 5 지원 종료) | P1/P2 | index.css 리터럴 반복, 번들 172KB gzip 단일 청크, Vite 8.3.1/React 19.3/TS 7.0 | 없음 | 없음(recharts 교체·PWA만 충돌) |

**결론**: FE는 ADR-10·CQRS Lite·"Backend가 Source of Truth"를 충실히 따르고 타입 계약도 1건(`listingDate`)을 제외하면 일치한다. 그러나 (1) 실주문 경로인 테스트 시그널 폼이 확인 없이 자동 사이징으로 발행되는 구조가 사고를 이미 냈고, (2) 명령 실패가 무음이며, (3) 접근성은 폼 레이블·키보드·라이브 리전에서 KWCAG 필수 항목을 통과하지 못한다. 세 가지는 BE 변경 없이 FE만으로 며칠 내 해결 가능한 P0/P1이며, 기존 아키텍처 결정과 충돌하지 않는다.
