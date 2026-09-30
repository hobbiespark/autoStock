# 09. BE 코드 감사 (2026-10-01)

> 문서 묶음: [00-README](00-README.md) · [01 현황](01-current-state.md) · 조사 [02 BE](02-research-be.md) · [03 FE](03-research-fe.md) · [04 디자인](04-research-design.md) · [05 기획](05-research-planning.md) · [06 인프라](06-research-infra.md) · [07 주식거래①시장·제도](07-research-trading-market.md) · [08 주식거래②전략·LLM](08-research-trading-strategy.md) · 감사 [09 BE](09-audit-be.md) · [10 FE](10-audit-fe.md) · **[11 실행 계획](11-execution-plan.md)** · [12 결정 목록](12-decisions.md)


- 기준일: 2026-10-01
- 대상: `/home/claude/autostock` — `app/src/main/java/com/autostock/**`(181파일), `common/src/main/java/**`(28파일), Flyway V1~V8, `application.yml`, `build.gradle`, `.github/workflows/ci.yml`
- 읽은 기준 문서: `docs/ARCHITECTURE.md`, `aiDoc/refactoring-plan.md`(완료 항목 S1·S2·R1~R4·A1~A5·B1~B3·P3 제외), `aiDoc/large-classes.md`, `aiDoc/claude-rules/coding-rules.md` §18.3, `aiDoc/order-concurrency.md`, `aiDoc/http-timeouts.md`
- 근거 표기: **확인(파일:줄)** = 코드를 직접 읽어 확인, **추론** = 실측하지 않은 판단. 빌드·테스트는 이 환경에서 실행하지 않았다(**미검증**).

---

## 1. 모듈 지도

### 1.1 모듈별 클래스·책임

| 모듈 | 파일 수 | 주요 클래스 | 책임 | 참조하는 모듈 |
|---|---|---|---|---|
| `common`(별도 Gradle 모듈, OPEN) | 28 | `event.*` 18, `util.*` 9 | 이벤트 계약·값 객체·순수 유틸 | 없음(순수 자바, `spring-modulith-api` compileOnly) |
| `config`(shared) | 7 | `AsyncConfig`, `CacheConfig`, `ClockConfig`, `JacksonConfig`, `SchedulingConfig`, `logging.SecretMaskingConverter`, `logging.ThrowableMaskingConverter` | 인프라 배선(캐시·Clock·Jackson VO 직렬화·로그 마스킹) | common |
| `kiwoom`(shared) | 7 | `KiwoomRestClient`, `TokenManager`, `TrRateLimiter`, `TrId`, `KiwoomProperties`, `KiwoomApiException` | 키움 REST 단일 진입점(레이트리밋·429/1700 재시도·8005 토큰 재발급) | common |
| `market` | 16 | `KiwoomWebSocketClient`, `RealMessageParser`, `ReconnectBackoff`, `DisconnectionTracker`, `MarketDataPort`(+`KiwoomMarketDataAdapter`), `KiwoomDailyChartService`, `MarketCalendarService`, `MarketSessionService`, `MarketSession`, `HolidaySyncService`, `MarketHolidayEntity/Repository`, `HolidayApiProperties`, `MinuteBarArchiver` | 시세 WS/REST, 거래일·장 세션, 휴장일 동기화, 분봉 적재 | kiwoom, config, common |
| `strategy` | 8 | `C3LiveStrategy`, `StrategyEngine`, `MomentumMath`, `RegimeMath`, `VolTargetMath`, `BreakoutMath`, `C3StrategyProperties` | Signal 생성(주문 금지) | market, portfolio, monitor(`TradingSystemManager.status()` 조회), common |
| `risk` | 16 | `RiskGate`, `KillSwitch`, `DailyLimitTracker`, `DailyPnlTracker`, `PositionSizer`, `KrxTickSize`, `MacroGuard`, `DisclosureBlacklist`(+Entity/Repository/ExpiryScheduler), `EquitySource`, `PaperEquitySource`, `MarketDataStaleListener`, `RiskProperties` | 유일한 주문 관문, 한도·킬스위치·블랙리스트 | market, portfolio, macrointel(Properties·DisclosureType), common |
| `trading` | 15 | `TradingService`, `OrderEntity`, `OrderStatus`, `OrderRepository`, `OrderNoticeHandler`, `CumulativeFillTracker`, `PendingOrderNotices`, `OptimisticRetry`, `ReconciliationService`, `StaleOrderCanceller`, `OrderSequenceRestorer`, `TradingProperties`, `BrokerOrderIdConverter`, `StockCodeConverter` | 주문 생성·상태기계·체결 반영·대사·미체결 취소 | execution(`BrokerPort`), market(`MarketSessionService`), common |
| `execution` | 9 | `BrokerPort`, `KiwoomBrokerAdapter`, `BrokerBalance`, `BrokerOrderResult`, `BrokerOutstandingOrder`, `BrokerRejectedException`, `BrokerEquitySource`, `PositionRestorer` | 브로커 전달(어댑터), LIVE equity, 재기동 포지션 복원 | kiwoom, risk(`EquitySource` 구현), common |
| `portfolio` | 2 | `PositionBook` | 보유 포지션 장부(리프) | common |
| `audit` | 4 | `EventAuditListener`, `EventRecord`, `EventRecordRepository` | 이벤트 스토어 append | common |
| `monitor` | 46 | 컨트롤러 6(`DashboardController`, `TradingSystemController`, `OrderHistoryController`, `PerformanceController`, `DecisionController`, `IpoController`), `DashboardFacade`, `EventFeed`, `TradingSystemManager/Status`, `TradingAutoStarter`, `Notifier`(+`TelegramNotifier`/`LogOnlyNotifier`), `TelegramCommandPoller`, `TradeNotificationListener`, `IpoAlertListener`, `DisclosureBlacklistListener`, `SignalDecisionListener`(+Entity/Repo), `DailyReportScheduler`, `DailyPerformanceService/Recorder/Entity/Repo`, `SlippageTracker`, `SleepGuard`, `SystemSleepBlocker`, `ApiException/Handler`, `ErrorCode`, `view.*` 9 | REST API, 알림, 운영 상태기계, 리포트, 절전 대응 | risk, portfolio, trading, execution, market, ipo, kiwoom(`KiwoomApiException` 매핑), common |
| `ipo` | 10 | `IpoSyncScheduler`, `IpoDealCommandService`, `DartClient`, `IpoDealEntity/Repository`, `IpoStatus`, `IpoRecommendation`, `DartProperties`, `IpoFilterProperties` | 공모주 수집·필터·알림 | common |
| `macrointel` | 9 | `MacroSyncScheduler`, `FredClient`, `EcosClient`, `DisclosureBlacklistSyncScheduler`, `MajorDisclosureDartClient`, `DisclosureType`, `MacroIntelProperties`, `DisclosureBlacklistProperties` | 거시 지표·공시 수집(판단 없음) | common |
| `backtest` | 30 | 러너 5(`BacktestRunner`, `WalkForwardRunner`, `PortfolioBacktestRunner`, `CrossSectionalBacktestRunner`, `InverseSwitchWalkForwardRunner`), 전략 11, `PerformanceCalculator`, `SpaTest`, `StationaryBootstrap`, `CostModel`, `CandleCsvLoader/Writer` 등 | 오프라인 연구(trial 재현) | strategy(`*Math`), common |
| `analysis` | 1 | `package-info`만 | 예정 모듈(뉴스/FinBERT) | — |

확인: `AutostockApplication.java:16` `@Modulithic(sharedModules = {"kiwoom", "config"})`.

### 1.2 발행/구독 이벤트 매트릭스 (확인: `grep @EventListener`, `publishEvent`)

| 이벤트(common.event) | 발행자 | 구독자(동기 `@EventListener`) | 비동기 구독자(`@Async`) |
|---|---|---|---|
| `MarketTick` | `KiwoomWebSocketClient.handleTextMessage`(market:399) | `StrategyEngine.onTick`(trace 로그만) | `EventAuditListener` |
| `OrderNotice` | `KiwoomWebSocketClient`(market:399) | `OrderNoticeHandler.onOrderNotice`(trading:103) | — |
| `Signal` | `C3LiveStrategy`(strategy:379,384), `DashboardController.testSignal`(monitor:123) | `RiskGate.onSignal`, `EventFeed` | `EventAuditListener` |
| `SignalDecision` | `C3LiveStrategy`(363), `RiskGate.publishRejected`(373) | — | `SignalDecisionListener`, `EventAuditListener` |
| `OrderRequest` | `RiskGate`(200) | `TradingService.onOrderRequest`, `TradeNotificationListener`, `EventFeed`, `SlippageTracker` | `EventAuditListener` |
| `Fill` | `TradingService.executeSim`(156), `OrderNoticeHandler`(163) | `PositionBook`, `DailyPnlTracker`, `TradeNotificationListener`, `EventFeed`, `SlippageTracker` | `EventAuditListener` |
| `CancelRequest` | `DashboardController.cancelOrder`(141) | `TradingService.onCancelRequest` | — |
| `KillSwitchChanged` | `KillSwitch.engage/release`(risk:330,337) | `TradeNotificationListener`, `TradingSystemManager` | — |
| `MarketDataStale` | `DisconnectionTracker.observe`(market:222) | `MarketDataStaleListener` → `KillSwitch.engage` | — |
| `MacroIndicator` | `MacroSyncScheduler.publish`(159) | `MacroGuard` | `EventAuditListener` |
| `DisclosureRisk` | `DisclosureBlacklistSyncScheduler` | `DisclosureBlacklist.onDisclosureRisk` | — |
| `DisclosureBlacklisted` | `DisclosureBlacklist`(risk:224) | `DisclosureBlacklistListener` | — |
| `IpoAlert` | `IpoSyncScheduler.publishAlert`(252) | `IpoAlertListener` | — |
| `OrderSequenceRestored` | `OrderSequenceRestorer`(trading:70) | `DailyLimitTracker` | — |
| `PositionRestored` | `PositionRestorer`(execution:212) | `PositionBook` | — |
| `NewsSentiment` | 발행자 없음(analysis 미구현) | — | `EventAuditListener` |
| `Candle` | 이벤트가 아닌 값 객체(market↔backtest 공유) | — | — |

특이점(확인): `@ApplicationModuleListener`/`@TransactionalEventListener` 사용처 0건 → Modulith 이벤트 발행 로그(`event_publication`, V1)는 **한 번도 기록되지 않는다**. 4.4절·5절 참고.

### 1.3 스케줄 전수 목록 (확인: `grep @Scheduled`)

| 클래스.메서드 | 스케줄 | 시간대 | 가드 |
|---|---|---|---|
| `C3LiveStrategy.run` | cron `0 5 9 * * MON-FRI` | Asia/Seoul | `strategy.c3.enabled`, `TradingSystemStatus==RUNNING`, 거래일 |
| `IpoSyncScheduler.syncScheduled` | cron `0 20 8 * * MON-FRI` | Asia/Seoul | `dart.enabled` |
| `IpoSyncScheduler.catchUpHourly` | cron `0 5 * * * *` | Asia/Seoul | 키 있음 + 오늘 미성공 |
| `MacroSyncScheduler.syncScheduled` | cron `0 30 8 * * MON-FRI` | Asia/Seoul | `macrointel.enabled` |
| `MacroSyncScheduler.catchUpHourly` | cron `0 5 * * * *` | Asia/Seoul | 키 있음 + 오늘 미성공 |
| `DisclosureBlacklistSyncScheduler.syncScheduled` | cron `0 35 8 * * MON-FRI` | Asia/Seoul | `macrointel.blacklist.enabled` |
| `DisclosureBlacklistSyncScheduler.catchUpHourly` | cron `0 5 * * * *` | Asia/Seoul | 동상 |
| `DisclosureBlacklistExpiryScheduler.releaseExpiredScheduled` | cron `0 0 8 * * *` | Asia/Seoul | 없음 |
| `HolidaySyncService.syncNextYear` | cron `0 0 9 1 11 *` | Asia/Seoul | `market.holiday-api.enabled` |
| `HolidaySyncService.catchUpHourly` | cron `0 5 * * * *` | Asia/Seoul | 키 있음 + 창에 행 없음 + 오늘 미시도 |
| `HolidaySyncService.resyncUpcomingWindow` | cron `0 10 9 15 * *` | Asia/Seoul | enabled |
| `MinuteBarArchiver.archiveToday` | cron `0 45 15 * * MON-FRI` | Asia/Seoul | `autostock.minute-archive.enabled` |
| `DailyReportScheduler.sendDailyReport` | cron `0 50 15 * * MON-FRI` | Asia/Seoul | 없음(휴장일에도 발송) |
| `KiwoomWebSocketClient.watchdog` | fixedDelay 10s, initialDelay 30s | — | `autostock.ws.enabled`, `MarketSession` |
| `OrderNoticeHandler.replayPendingNotices` | fixedDelay 1s | — | 없음 |
| `StaleOrderCanceller.cancelStaleOrders` | fixedDelay 60s | — | `MarketSession.isActive`, LIVE |
| `ReconciliationService.scheduledReconcile` | fixedDelay 5m | — | `MarketSession.isActive`, LIVE |
| `PositionRestorer.retryUntilRestored` | fixedDelay 5m, initialDelay 5m | — | LIVE, 미복원 |
| `MarketSessionService.logTransition` | fixedDelay 30s | — | `autostock.session.enabled` |
| `SleepGuard.tick` | fixedDelay 30s | — | 없음 |
| `TelegramCommandPoller.poll` | **fixedRate 5s** | — | `monitor.telegram.enabled` |

`ArchitectureRulesTest.cron_스케줄은_시간대를_명시한다`가 cron의 zone 누락을 빌드에서 잡는다(확인: test:115-128). `fixedRate`는 잡지 않는다(5절 P2-7).

### 1.4 REST 엔드포인트 전수 목록 (확인: monitor 컨트롤러 6개)

| 메서드 | 경로 | 요청 | 응답 | 검증/비고 |
|---|---|---|---|---|
| GET | `/api/dashboard` | — | `DashboardView` | Facade 조합 |
| GET | `/api/dashboard/positions` | — | `List<PositionView>` | |
| GET | `/api/dashboard/events` | — | `List<EventFeed.FeedItem>`(최대 100) | |
| GET | `/api/dashboard/killswitch` | — | `KillSwitchView{engaged}` | |
| POST | `/api/dashboard/killswitch` | `KillSwitchRequest{engage:@NotNull Boolean}` `@Valid` | `KillSwitchView` | fail-closed(B2) |
| POST | `/api/dashboard/test-signal` | `TestSignalRequest{symbol,side,price,quantity?}` `@Valid` | `TestSignalResponse{accepted}` | LIVE에서도 동작(5절 P1-6) |
| POST | `/api/dashboard/orders/{clientOrderId}/cancel` | path | `TestSignalResponse` | path 변수 검증 없음 |
| GET | `/api/dashboard/quote/{symbol}` | path | `QuoteView` | **symbol 형식 검증 없음**(5절 P2-9) |
| POST | `/api/trading/start` | — | `StatusResponse{status}` / 409 ProblemDetail | |
| POST | `/api/trading/stop` | — | 동상 | |
| GET | `/api/orders?days=` | 1~90(기본 7) | `OrderHistoryView{orders[]}` | 페이지네이션 없음 |
| GET | `/api/performance/daily?days=` | ≤90 | `List<DailyPerformanceView>` | |
| GET | `/api/decisions?date=` | ISO date(기본 오늘 KST) | `List<SignalDecisionView>` | 페이지네이션 없음 |
| GET | `/api/ipo?status=` | enum 문자열 | `List<IpoDealView>` | 페이지네이션 없음 |
| POST | `/api/ipo/{id}/record` | `IpoRecordRequest` `@Valid` | `IpoDealView` / 404 / 409 | 트랜잭션은 `IpoDealCommandService` |
| POST | `/api/ipo/{id}/metrics` | `IpoMetricsRequest` `@Valid` | 동상 | |
| — | `/actuator/{health,info,metrics,caches,shutdown}` | | | `shutdown access: unrestricted`(yml:64-66), 루프백 바인딩(yml:54) |
| — | `/swagger-ui.html`, `/v3/api-docs` | | | try-it-out 활성(yml:178) |

에러 형식: RFC 9457 `ProblemDetail` + `code`(`ErrorCode` 9종), `ApiExceptionHandler`(확인: monitor/ApiExceptionHandler.java:59-124). 인증: 없음(Spring Security 의존성 없음, S2에서 루프백 바인딩으로 확정).

### 1.5 설정 키 전수 목록

`@ConfigurationProperties` record (확인: grep):

| prefix / 클래스 | 필드 | yml 값 |
|---|---|---|
| `kiwoom` / `KiwoomProperties`(@Validated @NotBlank ×4, strip) | restBaseUrl, wsUrl, appKey, appSecret | 프로필별(paper: mockapi, live: api) |
| `execution` / `TradingProperties` | mode(SIM/LIVE), staleOrderTimeout(기본 5m) | SIM, 5m |
| `risk` / `RiskProperties` | maxPositionPctPerSymbol, maxConcurrentPositions, stopLossPct, takeProfitPct, dailyMaxLossPct, dailyMaxOrders, paperEquity, feeRate, sellTaxRate, enforceMarketHours | 0.10, 5, -0.03, 0.05, -0.02, 30, 1e7, 0.00015, 0.0020, true |
| `strategy.c3` / `C3StrategyProperties` | enabled, symbols, lookbackN, decisionIntervalDays, targetVolAnnual, regimeIndexSymbol, regimeSmaDays | false, 5종목, 120, 21, 0.20, 069500, 200 |
| `market.holiday-api` / `HolidayApiProperties` | enabled, serviceKey, baseUrl | true, env, data.go.kr |
| `macrointel` / `MacroIntelProperties` | enabled, fredApiKey, ecosApiKey, vixCautionThreshold, vixSevereThreshold, usdKrwCautionThreshold | true, env, env, 25, 35, 1450 |
| `macrointel.blacklist` / `DisclosureBlacklistProperties` | enabled, dartApiKey, lookbackDays, retentionDays | true, env, 7, 180 |
| `dart` / `DartProperties` | enabled, apiKey, lookbackDays | true, env, 14 |
| `ipo.filter` / `IpoFilterProperties` | minInstitutionalCompetitionRate, minLockupCommitRate | 500, 0.20 |
| `monitor.telegram` / `TelegramProperties` | enabled, botToken, chatId | false, env, env |

`@Value`로만 읽는 키(record 없음 — 확인: grep `@Value`): `autostock.ws.enabled`, `autostock.ws.stale-after`, `autostock.session.{enabled,wake-time,sleep-time,keep-awake}`, `autostock.minute-archive.{enabled,symbols,dir}`, `autostock.trading.auto-start`(yml에 없음, `start_autostock.bat`가 `--autostock.trading.auto-start=true`로 주입), `execution.mode`(`PositionRestorer`가 `TradingProperties` 대신 문자열로 재해석), `strategy.c3.enabled`(`DashboardFacade`, 순환 회피 의도).

`risk.stopLossPct`/`takeProfitPct`는 선언만 있고 **사용처가 없다**(확인: grep `stopLossPct` → RiskProperties.java만).

---

## 2. DB 스키마 (Flyway V1~V8)

| 버전 | 테이블 | 컬럼 요약 | 인덱스·제약 | 엔티티 매핑 특이점 |
|---|---|---|---|---|
| V1 | `event_store` | id BIGSERIAL, event_type VARCHAR(64), schema_version INT, payload TEXT, occurred_at/recorded_at TIMESTAMPTZ | `idx_event_store_occurred`, `idx_event_store_type_occurred` | `audit.EventRecord` — 컬럼명이 `@Column(name)` 없이 필드명(eventType 등)에 의존: Boot 기본 네이밍(`SpringPhysicalNamingStrategy`)이 snake_case로 변환(추론: validate 통과 전제, `SchemaAndTimeZoneDbTest`가 V8까지 검증) |
| V1 | `event_publication` | id UUID, listener_id, event_type, serialized_event TEXT, publication_date, completion_date | 부분 인덱스(completion_date IS NULL) | Modulith 관리 테이블. **엔티티·리스너 모두 없음 → 항상 비어 있음**(1.2절) |
| V2 | `orders` | client_order_id VARCHAR(128) UNIQUE, broker_order_id VARCHAR(64), symbol VARCHAR(16), side VARCHAR(8), quantity/filled_quantity BIGINT, limit_price NUMERIC(19,4), status VARCHAR(20), strategy_id, submitted_at, updated_at | `idx_orders_broker_order_id`, `idx_orders_status` | `OrderEntity`: `@Convert` `BrokerOrderId`/`StockCode`(패키지 전용 컨버터), `@Enumerated(STRING)`, `@Version`(V8). `limit_price` NULL 허용이나 생성자는 `Price`(non-null) 요구 — 과거 행에 NULL 가능성만 남음. `symbol` VARCHAR(16)인데 `StockCode`는 6자리 강제(읽기 시 예외, 의도된 fail-fast) |
| V3 | `market_holidays` | holiday_date DATE PK, name VARCHAR(100), source VARCHAR(20), is_market_closure BOOLEAN, synced_at | PK만 | `MarketHolidayEntity` — `@Id LocalDate` |
| V4 | `daily_performance` | trade_date DATE UNIQUE, realized_pnl NUMERIC(19,4), order_count/fill_count INT, avg/max_slippage_bps DOUBLE, conservative_mode, kill_switch_engaged, created_at | UNIQUE(trade_date) | upsert(`findByTradeDate`) |
| V5 | `signal_decisions` | decided_at, trade_date, horizon(16), strategy_id(64), symbol(16), conclusion(16), reason TEXT, metrics_json TEXT, created_at | `(trade_date, horizon)` | append-only |
| V6 | `ipo_deals` | 24컬럼(공모가 3, 일정 4, 지표 2, 기록 6, status/recommendation DEFAULT, source, 시각 2) | UNIQUE(rcept_no), `idx_ipo_deals_status`, `idx_ipo_deals_subscription_start` | `IpoDealEntity` `@Version`(V8) |
| V7 | `disclosure_blacklist` | symbol(12), corp_name, disclosure_type(32), rcept_no(20), rcept_dt, expires_on, created_at | UNIQUE(symbol, rcept_no), symbol/expires_on 인덱스 | `DisclosureBlacklistEntity` — symbol이 `String`(StockCode 미적용) |
| V8 | `orders.version`, `ipo_deals.version` | BIGINT NOT NULL DEFAULT 0 | | 낙관적 잠금(R2) |

공통 특이점(확인): 감사 컬럼(주체)은 없다(P3 표에서 보류 결정). FK 없음(테이블 간 관계 없음). `orders.status` VARCHAR(20)에 CHECK 제약 없음 — 상태기계는 앱만 강제(§7 "무결성은 DB 제약으로도"). 벌크 정리 SQL은 `scripts/sql/*.sql`로 수기 실행.

---

## 3. 이벤트 카탈로그 (`common.event.*`)

| 이벤트 | 필드(타입) | VO 적용 | JSON 직렬화 규칙 | 비고 |
|---|---|---|---|---|
| `Signal`(v2) | strategyId String, symbol **StockCode**, side, refPrice **Price?**, confidence double, fixedQuantity Long?, timestamp | 부분 | StockCode→문자열, Price→숫자(`JacksonConfig`) | v1 호환 생성자(fixedQuantity 없음) |
| `SignalDecision` | horizon, strategyId, symbol **StockCode**, conclusion, reason, metrics Map<String,String>, decidedAt | 부분 | | horizon/conclusion은 문자열(enum 아님) |
| `OrderRequest` | idempotencyKey String(ClientOrderId 포맷), strategyId, symbol **StockCode**, side, quantity **Quantity**, limitPrice **Price**, timestamp | 대부분 | | idempotencyKey는 `ClientOrderId` VO가 아닌 String(A1 조각에서 미전환) |
| `Fill` | orderIdempotencyKey String, brokerOrderId **BrokerOrderId**, symbol **StockCode**, side, filledQuantity **Quantity**, fillPrice **Price**, timestamp | 대부분 | | 증분 수량 전제 |
| `OrderNotice` | brokerOrderId **BrokerOrderId**, symbol StockCode?(null 가능), status String(원문 "접수"/"체결"), filledQuantity long(누적), fillPrice Price?(누적 평균가), remainingQuantity long(-1=미상), rawType, timestamp | 부분 | | 실측 확정 필드(6절) |
| `MarketTick` | symbol **StockCode**, price **Price**, volume long, timestamp, source enum | 적용 | | |
| `CancelRequest` | clientOrderId String, requestedBy, timestamp | 없음 | | |
| `PositionRestored` | symbol **StockCode**, quantity long, avgPrice **Price?** | 적용 | | avgPrice null=평단 미상 |
| `OrderSequenceRestored` | day LocalDate, lastSequence int | — | | |
| `KillSwitchChanged` | engaged boolean, reason, timestamp | — | | |
| `MarketDataStale` | disconnectedSince, seconds | — | | |
| `MacroIndicator` | indicatorId, scope, value BigDecimal, detail, timestamp | — | | indicatorId 문자열 계약(`MacroGuard`가 "FRED_VIX"/"ECOS_USDKRW" 상수 중복 선언 — risk:52-53 vs macrointel:38-40) |
| `DisclosureRisk` | symbol String, corpName, disclosureType String(enum name), rceptNo, rceptDt, expiresOn, at | 없음 | | |
| `DisclosureBlacklisted` | symbol String, corpName, disclosureType(라벨), rceptNo, expiresOn, at | 없음 | | |
| `IpoAlert` | corpName, phase String, message, at | — | | |
| `NewsSentiment` | symbol String, score double, headline, sourceUrl, timestamp | 없음 | | 발행자 없음 |
| `Candle` | symbol String, date, OHLC BigDecimal, volume | 보류(사용자 결정) | | 이벤트 아님 |

직렬화 규칙(확인: `config/JacksonConfig.java:29-62`): `StockCode`·`BrokerOrderId`→JSON 문자열, `Quantity`→정수, `Price`→숫자(자릿수 보존). `common`은 Jackson 애너테이션 없이 순수 자바. `ValueObjectJsonTest` 8건이 고정. `event_store.schema_version`은 `EventAuditListener.SCHEMA_V1 = 1` 고정 — `Signal`이 v2로 진화했음에도 1로 기록(확인: audit:44, 60). 리플레이 시 버전으로 구분 불가(5절 P2-12).

---

## 4. 핫패스 분석

### 4.1 흐름 추적(코드 기준)

| 단계 | 코드 | 스레드 | 트랜잭션 |
|---|---|---|---|
| ① 시세 수신 | `KiwoomWebSocketClient.handleTextMessage`(market:358) → `RealMessageParser.parse` → `publishEvent(MarketTick)` | Tomcat WS 리더 스레드 | 없음 |
| ② 전략 | `MarketTick` 소비자는 `StrategyEngine.onTick`(trace만). **실제 전략 C3는 09:05 cron**(`C3LiveStrategy.run`) → `MarketDataPort.dailyCandles/bestQuote/stockQuote`(REST, 캐시) → `publishEvent(Signal)` | 스케줄러(가상 스레드) | 없음 |
| ③ RiskGate | `RiskGate.onSignal`(risk:121): 장시간 → 킬스위치 → 사이징(보수모드·블랙리스트·보유·동시보유·equity) → 호가단위 → 일 한도 슬롯 → `ClientOrderId.generate` → `publishEvent(OrderRequest)` | ②와 동일 스레드(동기 리스너) | 없음 |
| ④ 주문 | `TradingService.onOrderRequest`(trading:114): 인메모리 Set 멱등 → SIM: 엔티티 CREATED→…→FILLED 저장 + `Fill` 즉시 발행 / LIVE: VALIDATED 저장(DB UNIQUE 2차 방어) → SUBMITTING 저장 → `BrokerPort.placeOrder` → SUBMITTED 저장 + 맵 등록 | 동일 스레드 | **없음** — `save` 각각 독립 트랜잭션(리포지토리 기본). 외부 호출은 트랜잭션 밖(§7 준수) |
| ⑤ 브로커 | `KiwoomBrokerAdapter.placeOrder` → `KiwoomRestClient.call`: `TrRateLimiter`(TR당 1건/초, 대기 10s) → 8005 토큰 재발급 1회 → 429/1700 재시도(최대 4회, 1.1s) → WebClient `.block()`(per-call 15s, 공통 connect 5s/read 20s) | 동일 스레드(블로킹) | 없음 |
| ⑥ 체결통보 | WS `OrderNotice` → `OrderNoticeHandler.onOrderNotice`(trading:103): "접수"→ACCEPTED / "체결"→ 인메모리·DB로 원주문 조회(없으면 `PendingOrderNotices` 30s 보류, 1s 리플레이) → `OptimisticRetry`(3회) 안에서 누적→증분 변환·`applyFill`·저장 → `Fill` 발행 | WS 리더 스레드 또는 리플레이 스케줄 스레드 | `save` 단위 |
| ⑦ 포지션 | `PositionBook.onFill`(compute 원자), `DailyPnlTracker.onFill`(ReentrantLock), `SlippageTracker`(synchronized), `TradeNotificationListener`→`TelegramNotifier.notify`(**HTTP block, 동기**), `EventFeed` | ⑥과 동일 스레드 | 없음 |

### 4.2 스레드 모델

- 가상 스레드 활성(`spring.threads.virtual.enabled=true`, yml:13): 스케줄러(`SimpleAsyncTaskScheduler`)·`@Async`·HTTP 처리가 가상 스레드. `synchronized` 핫패스는 `ReentrantLock`으로 교체됨(`TokenManager`, `BrokerEquitySource`, `EventFeed`, `DailyPnlTracker`). 예외: `SlippageTracker`(synchronized, 확인: monitor:112,128 — 내부는 순수 계산이라 핀닝 무해, 관례 불일치).
- `@Async`는 `EventAuditListener`·`SignalDecisionListener` 두 클래스만(확인). 나머지 리스너는 전부 **발행 스레드에서 동기 실행**. 따라서 WS 리더 스레드가 ⑥→⑦ 전체(DB 저장 + Telegram HTTP 최대 20s)를 붙잡는다 → PING 에코(10초 주기) 지연 가능(추론, 5절 P1-5).
- `TradingSystemManager.start/stop`은 `Thread.ofVirtual()`로 백그라운드.
- `ReconciliationService.lastFullReconcileMs` 가드는 read-then-write(volatile, 비원자, trading:111-116) — 기동 시 동시 2회 호출 가드가 뚫릴 수 있음(추론, 낮음).

### 4.3 잠금·동시성 장치 요약

| 장치 | 위치 | 보호 대상 |
|---|---|---|
| `ConcurrentHashMap.newKeySet` | `TradingService.seenClientOrderIds` | 프로세스 내 멱등 1차 |
| DB UNIQUE(client_order_id) | V2 | 멱등 2차(재기동 후) |
| `@Version` + `OptimisticRetry`(3회) | orders, ipo_deals | 체결통보·취소·대사·타임아웃 취소 간 lost update |
| `ReentrantLock` single-flight | `TokenManager.issue` | 토큰 중복 발급 |
| `AtomicBoolean connecting` | WS `connect` | 중복 연결 |
| `AtomicInteger` + CAS 롤오버 | `DailyLimitTracker` | 일 주문 카운터 |
| `RateLimiterRegistry` TR별 | `TrRateLimiter` | 키움 유량 |

### 4.4 실패 경로

| 실패 | 처리 | 근거 |
|---|---|---|
| 주문 API 타임아웃/네트워크 | `catch Exception` → UNKNOWN 저장 → `requestReconcile` 즉시 단건 대사 | trading:200-209 |
| 브로커 명시 거부(RC4027·800033) | `KiwoomApiException`→`BrokerRejectedException`→ REJECTED(대사 없음) | execution:80-85, trading:193-199 |
| UNKNOWN 대사 | ka10075 미체결 목록에 있으면 SUBMITTED로 확정; 없으면 `probeFillStatus` 스텁(항상 empty)→ **UNKNOWN 유지·경고만**(R4(b) 미착수) | trading:157-219 |
| brokerOrderId 없는 UNKNOWN(SUBMITTING 중 사망) | 경고만, 당일 주문내역 TR 미연동 | trading:158-165 |
| HTTP 429 / return_code 5 `[1700]` | resilience4j Retry 4회, 1.1s 간격 | kiwoom:76-83 |
| `[8005]` 토큰 거부 | `TokenManager.invalidate`(발급 60s 이내는 유지) 후 1회 재전송 | kiwoom:116-127, 121-133 |
| 15초 무응답 | `KiwoomApiException("응답 없음")` — 주문 TR이면 UNKNOWN 경로 | kiwoom:144-146 |
| WS 로그인 실패 | 토큰 폐기 + 세션 close → watchdog 재연결(백오프 10s→300s) | market:379-382 |
| WS 180초 단절 | `MarketDataStale` → 킬스위치(수동 해제만) | market:220-223, risk:239-242 |
| 감사 저장 실패 | `log.error` 후 스킵(B4 보류) | audit:88-90 |
| `@Async` 리스너 유실(앱 사망 시) | Javadoc은 "Modulith 발행 로그가 보완"이라 하나 **해당 로그는 기록되지 않음**(1.2절) | audit:29-31 |

---

## 5. 결함·리스크 후보 (완료 항목 제외, 신규·잔여만)

### P0 — 즉시 조치 권고

| # | 항목 | 근거 | 영향 | 제안 |
|---|---|---|---|---|
| P0-1 | **파생 delete 쿼리가 트랜잭션 없이 호출됨** — `DisclosureBlacklist.remove`/`releaseExpired`가 `deleteBySymbol`/`deleteByExpiresOnBefore`를 호출하나 호출부에 `@Transactional`이 없다 | 확인: risk/DisclosureBlacklist.java:92,127; 리포지토리 risk/DisclosureBlacklistRepository.java:21,24; `@Transactional` 사용처는 ipo·monitor·audit 4곳뿐(grep). 추론: Spring Data 기본 트랜잭션(`SimpleJpaRepository` 클래스 레벨 readOnly=true)이 파생 쿼리에 적용 → Hibernate FlushMode.MANUAL → `em.remove` 미플러시, 또는 PG `READ ONLY` 트랜잭션 오류 | 수동 `remove()`한 종목이 재기동 후 되살아남(캐시만 지워짐), 만료 행이 DB에 영구 누적. 테스트는 mock 리포지토리라 못 잡음(`DisclosureBlacklistTest.java:42`) | `DisclosureBlacklist.remove/releaseExpired`에 `@Transactional`(또는 리포 메서드에 `@Transactional @Modifying` JPQL). `PostgresDataJpaTest` 기반 DB 테스트 1건 추가해 실측 |
| P0-2 | **킬스위치·일 손실 누적·포지션 장부가 전부 인메모리** — 재기동 시 킬스위치 해제, 오늘 실현손실 0으로 리셋, `DailyPnlTracker.lots`는 `PositionRestored`를 받지 않음 | 확인: risk/KillSwitch.java:315(`AtomicBoolean`), risk/DailyPnlTracker.java:151-155(맵·BigDecimal 필드), `PositionRestored` 리스너는 `PositionBook`뿐(portfolio:99). `TradingAutoStarter` Javadoc(monitor:234-236)은 "킬스위치가 켜진 채 재시작해도"라 하나 실제로는 켜져 있지 않다 | auto-start 재기동 = 사람 확인 없이 비상 정지 해제(설계 원칙 "해제는 사람만" 위반). 장중 재기동 뒤 일 손실 한도 우회. 복원 포지션 매도 시 실현손익 계산 스킵(경고만) → 한도 미작동 | 킬스위치 상태·사유를 DB(신규 테이블 또는 `daily_performance` 확장) 저장·기동 시 복원. `DailyPnlTracker`가 `PositionRestored`를 구독하거나 당일 `Fill`을 event_store에서 리플레이해 재구성 |
| P0-3 | **StaleOrderCanceller가 SUBMITTED만 보고, ACCEPTED 주문은 영원히 취소되지 않음; 취소 후 CANCEL_REQUESTED에서 멈춤** | 확인: trading/StaleOrderCanceller.java:286(`findByStatusIn(List.of(SUBMITTED))`); WS "접수" 통보가 SUBMITTED→ACCEPTED로 전이(trading/OrderNoticeHandler.java:244-257) → LIVE에서 정상 주문은 거의 즉시 ACCEPTED. 취소 성공 뒤 상태 갱신 없음(StaleOrderCanceller:311-313), Reconciliation 대상도 UNKNOWN/SUBMITTED뿐(ReconciliationService:117-118) | 미체결 타임아웃 취소가 사실상 동작하지 않음 → 동시 보유 한도·자본 잠식. 취소된 주문이 CANCEL_REQUESTED로 영구 체류(대시보드 오표시, `isCancellable` 재취소 불가) | 대상 상태를 `SUBMITTED, ACCEPTED, PARTIALLY_FILLED`로 확장; 취소 성공 시 `TradingService.applyCancelOutcome`과 같은 규칙으로 CANCELLED 확정; Reconciliation에 ACCEPTED·CANCEL_REQUESTED 포함 |

### P1 — 정확성·복원력

| # | 항목 | 근거 | 영향 | 제안 |
|---|---|---|---|---|
| P1-1 | **백테스트≠라이브: C3 판단 주기가 백테스트는 21봉(거래일), 라이브는 21역일** | 확인: strategy/C3LiveStrategy.java:370 `last.plusDays(decisionIntervalDays)` vs backtest/TimeSeriesMomentumStrategy.java:63 `REBALANCE_INTERVAL = 21`(캔들 카운터). Properties Javadoc은 "봉 수"(strategy:20) | ARCH 규칙 18 위반. 라이브가 약 1.4배 자주 판단 → 회전율·비용 증가, trial 결과와 불일치 | `MarketCalendarService`로 거래일 수를 세거나 판단 후 캔들 수 기준으로 비교. 라이브 `lastDecisionDate` 영속화(TODO 명시됨) |
| P1-2 | **RiskGate가 미체결 주문을 모른다(TOCTOU)** — `positionBook.holds`/`openPositionCount`만 검사, LIVE에서 체결 전 같은 종목 BUY 시그널이 또 오면 통과 | 확인: risk/RiskGate.java:244-255; 미체결 조회 경로 없음. 추론: 수동 test-signal 2회 클릭, 또는 C3 재기동 후 재판단(P1-1)에서 발생 가능 | 중복 매수·한도 초과 | `OrderRepository.findByStatusIn(SUBMITTED, ACCEPTED, PARTIALLY_FILLED)`의 심볼을 "예약 포지션"으로 계산해 사이징에 반영(trading→risk 순환 없이 이벤트/포트로) |
| P1-3 | **일련번호·슬롯 비원자** — `tryAcquireOrderSlot()` 증가와 `todayOrderCount()` 읽기가 분리돼 동시 시그널 2건이 같은 sequence를 받음 | 확인: risk/RiskGate.java:177,197; risk/DailyLimitTracker.java:45-53 | 같은 `ClientOrderId` → DB UNIQUE로 한 건이 조용히 스킵(사용자가 "주문 안 나감" 경험, 2026-09-11 사례와 유사) | `tryAcquireOrderSlot()`이 획득한 번호를 반환(`OptionalInt`)하도록 변경 |
| P1-4 | **포지션 대사 부재** — ARCH §8 "잔고↔portfolio" 대사는 기동 1회(`PositionRestorer`)뿐. WS 유실·수동 HTS 매매 시 `PositionBook` 드리프트 | 확인: execution/PositionRestorer.java:159-179(성공 후 재실행 없음), `putIfAbsent`(portfolio:103) | 매도 시그널 거부/수량 불일치(800033 계열 재발) | 주기 대사(5분)에 잔고 비교·경고 추가, 불일치 시 킬스위치 또는 알림 |
| P1-5 | **WS 리더 스레드에서 동기 리스너 체인 실행** — `Fill` 소비자 `TradeNotificationListener`가 Telegram HTTP를 `.block()`(read 20s) | 확인: market:394-402 → trading:163 → monitor/TradeNotificationListener.java:31-35 → monitor/TelegramNotifier.java:224-233; `@Async`는 2곳뿐 | Telegram 지연 시 PING 에코 지연 → 서버 측 연결 종료 → 재연결·체결통보 유실 가능(추론) | 알림 리스너를 `@Async`(전용 executor, 격벽) 또는 큐+단일 발송 스레드로 분리 |
| P1-6 | **`POST /api/dashboard/test-signal`이 LIVE에서도 실주문 경로** — Javadoc "paper 전용"이나 모드 가드 없음 | 확인: monitor/DashboardController.java:112-132 | 루프백 바인딩으로 외부 차단은 됐으나(S2), 로컬 오조작 1회 = 실주문(2026-09-18 "193주" 사고 유형) | LIVE에서는 `execution.mode` 확인 + 확인 토큰/2단계 확인, 또는 프로파일 조건부 등록(§5.7) |
| P1-7 | **`event_publication` 미사용 + 감사 리스너 `@Async` 유실 보완 주장 오류** | 확인: 1.2절. audit:29-31 Javadoc | 앱 사망 직전 Fill/OrderRequest 감사 기록 유실 시 복구 원천 없음. `spring-modulith-starter-jpa` 의존성·테이블만 존재(죽은 인프라) | `EventAuditListener`를 `@ApplicationModuleListener`(=`@TransactionalEventListener` + `@Async` + 발행 로그)로 전환하거나, 발행 로그를 쓰지 않을 것이면 Javadoc·V1 테이블·의존성 정리(ADR) |
| P1-8 | **MacroGuard 지표 신선도 미검사** — FRED/ECOS 수집이 며칠 실패해도 마지막 값으로 보수모드/킬스위치 판단 | 확인: risk/MacroGuard.java:59,74-104(타임스탬프 미보관) | 낡은 VIX로 매수 차단/허용 | `MacroIndicator.timestamp` 보관, N일 이상이면 "알 수 없음"으로 보수적 처리 + 경고 |

### P2 — 설계·관측·규칙 미충족

| # | 항목 | 근거 |
|---|---|---|
| P2-1 | `BrokerBalance.holdings`가 `List<Map<String,Object>>`로 키움 원시 Map을 포트 밖으로 노출(ARCH §4 위반, "typed record TODO") | 확인: execution/BrokerBalance.java:71; 소비 `PositionRestorer`:196-213 |
| P2-2 | `KiwoomBrokerAdapter.toOutstandingOrder` 필드(`trde_tp`, `un_qty`)가 문서 추정(TODO 실측), 알 수 없는 매매구분은 BUY로 간주 | 확인: execution:162-190. 대사 정확성 영향 |
| P2-3 | `ReconciliationService.probeFillStatus` 스텁(R4(b) 미착수) — UNKNOWN 자동 해소 불가, 브로커에 없는 주문이 UNKNOWN으로 무기한 체류 | 확인: trading:217-219 |
| P2-4 | `TradingService.seenClientOrderIds`·`brokerOrderIdToRequest` 무한 증가(일별 정리 없음) | 확인: trading:70,82. 일 30건 상한이라 실무 영향 낮음 |
| P2-5 | `IpoSyncScheduler.syncNow`가 `findAll()`로 전 딜을 매일 재저장(LISTED/PASSED 포함); 선언된 `findBySubscriptionStartGreaterThanEqualOrSubscriptionStartIsNull`은 미사용 | 확인: ipo:131-143, IpoDealRepository:109 |
| P2-6 | 재기동 시 IPO D-1/START 알림 재발송(`lastSuccessDate` 인메모리) | 확인: ipo:69-102, 233-249 |
| P2-7 | `TelegramCommandPoller.poll`이 `fixedRate` — 절전 복귀 후 밀린 회차 동시 실행(2026-09-22 사고 계열, `StaleOrderCanceller` Javadoc 근거)으로 같은 offset의 `getUpdates`가 병렬 실행 → 명령 중복 처리 가능 | 확인: monitor:78 vs trading/StaleOrderCanceller.java:270-275 |
| P2-8 | `DailyReportScheduler`가 휴장일·SIM에서도 `brokerPort.balance()` 호출(실패 로그 소음), `MarketSession` 미확인 | 확인: monitor:73-77,120-129 |
| P2-9 | `GET /api/dashboard/quote/{symbol}`·`/orders/{clientOrderId}/cancel` 경로 변수 형식 미검증(캐시 키 오염, 브로커에 임의 문자열 전달) | 확인: monitor/DashboardController.java:158,140 |
| P2-10 | 목록 API 페이지네이션 없음(`/api/orders`, `/api/decisions`, `/api/ipo`) — §10.2.4 | 확인: 1.4절 |
| P2-11 | `OrderHistoryController`의 days 경계가 UTC `Instant` 기준(§9 "날짜 경계는 업무 시간대") | 확인: monitor:220 |
| P2-12 | `event_store.schema_version` 항상 1(`Signal` v2 포함) | 확인: audit:44 |
| P2-13 | `risk.stopLossPct`/`takeProfitPct` 미사용 설정(죽은 키) | 확인: grep |
| P2-14 | `MacroGuard`가 indicatorId 상수를 macrointel과 별도 선언(SSOT 이중화) | 확인: risk:52-53, macrointel:38-41 |
| P2-15 | `@Value` 산재 12키(1.5절) — `@ConfigurationProperties` record 관례와 불일치; `PositionRestorer`가 `execution.mode`를 문자열로 재해석 | 확인: execution:153-156 |
| P2-16 | `TokenManager`가 `ZoneId.of("Asia/Seoul")`를 자체 선언(`MarketConstants.KST` SSOT 미사용) | 확인: kiwoom:63 |
| P2-17 | `DailyLimitTracker.rollDayIfNeeded` — CAS 성공과 `orderCount.set(0)` 사이에 증가가 끼면 카운트 유실(자정 직후, 낮음) | 확인: risk:71-77 |
| P2-18 | 서킷브레이커 부재(P3 표 "유지" 결정). 조회 API(ECOS/FRED/DART/휴장일)는 타임아웃만, 재시도는 catch-up cron. 키움 REST는 429/1700만 재시도 | 확인: kiwoom:76-83, `resilience4j-circuitbreaker` 의존성 없음 |
| P2-19 | `SecretMasking` 마스킹 규칙과 `HolidaySyncService.parseItems`의 응답 전체 로그(`log.error(... {}, response, e)`) — 응답에 키는 없으나 §6 "본문 통째 로그 금지" 미충족 | 확인: market/HolidaySyncService.java:317 |
| P2-20 | `KiwoomWebSocketClient`가 `afterConnectionClosed`/`handleTransportError`를 오버라이드하지 않아 종료 사유가 로그에 남지 않음(watchdog 10초 후 "단절"로만 관측) | 확인: market:304-403 |

### 5.1 관측성 현황

| 축 | 있음 | 없음 |
|---|---|---|
| 메트릭 | `kiwoom.api.latency`(TR 태그), `order.submit.latency`, `order.slippage.bps`, Caffeine 캐시 통계 | WS 연결 상태 게이지, 킬스위치 게이지, UNKNOWN/CANCEL_REQUESTED 체류 건수, 대사 실패 카운터, 이벤트 감사 실패 카운터, 보류 통보 수 |
| 헬스 | Actuator `health` 기본(DB) | 커스텀 `HealthIndicator` 0건(WS·토큰·브로커) — readiness/liveness 구분 없음 |
| 로그 | 마스킹 컨버터 2겹, 롤링 파일 | 구조화 JSON(P3 유지 결정), 추적 ID(MDC) 없음 |
| 알림 | Telegram(킬스위치·체결·주문·절전 복귀·IPO·블랙리스트) | 감사 실패(B4 보류), 대사 UNKNOWN 장기 체류, WS 재연결 반복, 토큰 발급 실패 |

### 5.2 테스트 공백(전용 테스트 파일이 없는 운영 클래스)

확인: `app/src/test` 파일명 대조. `EventAuditListener`, `KiwoomDailyChartService`, `OrderSequenceRestorer`, `OptimisticRetry`(간접만), `DisclosureBlacklistListener`, `SystemSleepBlocker`(SleepGuardTest에서 mock), `StrategyEngine`, `DisconnectionTracker`(WS 테스트 간접), `PendingOrderNotices`(OrderNoticeHandlerTest 간접), 컨버터 2종, `BacktestExecutionHandler`, `CandleCsvWriter`, `TradingAutoStarter`는 있음. DB 통합 테스트는 `OrderRepositoryDbTest`·`SchemaAndTimeZoneDbTest` 2건뿐 — `DisclosureBlacklistRepository`(P0-1), `IpoDealRepository @Version`(order-concurrency.md 6절 "남음"), 파생 쿼리 전반이 미검증. 동시성 테스트: 2스레드 `@Version` 충돌 1건뿐, RiskGate 동시 시그널(P1-2/3) 없음. 스모크 3건은 키 없으면 스킵.

### 5.3 §18.3 점검표 대비

| 항목 | 상태 | 근거 |
|---|---|---|
| 도메인이 Spring/HTTP/SQL을 모르는가 | 부분 — `*Math`·VO는 순수(ArchUnit 고정), `OrderEntity`는 JPA만. 모듈 내부 계층 분리는 없음(P3 "필요할 때만") | ArchitectureRulesTest:100-112 |
| 기본값이 한 곳에만 | 미충족 — indicatorId(P2-14), KST(P2-16), `execution.mode` 문자열(P2-15) | |
| 트랜잭션 안 외부 호출 없음 | 충족 — `@Transactional` 4곳 모두 DB만 | grep |
| 목록 N+1·페이지 상한 | 페이지 상한 없음(P2-10) | |
| UTC 저장·KST 날짜 경계 | 대부분 충족(A5). `OrderHistoryController` 예외(P2-11) | |
| 상태 코드 의미 | 충족(B1) | |
| 쓰기 API 멱등 | `test-signal` POST에 멱등 키 없음(재시도 시 2건 시그널 → P1-2와 결합) | |
| 외부 호출 타임아웃 | 충족(R1: 공통 5s/20s, 키움 15s, WS IO 5s) | yml:43-49, kiwoom:52, market:137-138 |
| 테스트가 경계값 | 도메인은 양호(VO·상태기계·틱사이즈). DB·동시성 얇음(5.2) | |

---

## 6. 건드리면 안 되는 곳

| 대상 | 이유 | 근거 |
|---|---|---|
| `backtest/*Runner`, `PerformanceCalculator`, `SpaTest`, `StationaryBootstrap`, `CostModel`, `TradeIntent` | PROGRESS의 trial 기록 재현성. 리팩토링이 수치를 바꾸면 기존 trial과 비교 불가(사용자 결정 2026-09-30) | aiDoc/large-classes.md:56; 백테스트 클래스 30개 Javadoc "사전 선언" |
| `strategy/MomentumMath`, `RegimeMath`, `VolTargetMath`, `BreakoutMath`, `risk/KrxTickSize` | 백테스트=라이브 동일 계산(ARCH 규칙 17·18). ArchUnit이 순수성 고정 | ArchitectureRulesTest:100-112; C3LiveStrategy:42-47 "판단식 한 줄도 새로 계산하지 않는다" |
| `app/src/test/.../backtest/RealData*ExperimentTest`, `Gate*Test`, `CrossSectionalStrategies` | 사전 선언 실험(파라미터 스윕·재튜닝 금지). 결과를 본 뒤 수정은 새 trial 선언 없이는 금지 | 각 테스트 Javadoc(예: RealDataCrossSectionalExperimentTest "결과를 본 뒤의 수정은 새 trial 선언 없이는 금지") |
| `RealMessageParser` FID 매핑(10/15/20, 9203/9001/913/911/910/902), `OrderNotice` 누적 의미 | 2026-09-11 실측 확정(283건 시세 + 4건 통보 + 3회 분할 체결) | market/RealMessageParser.java:33-35,66-70,121-125; trading/OrderNoticeHandler.java:30-45 |
| `KiwoomBrokerAdapter.placeOrder/cancelOrder/balance` 요청·응답 키(`ord_no`, `oso`, `cncl_qty=0`, `tot_evlt_pl`, `prsm_dpst_aset_amt`, `acnt_evlt_remn_indv_tot`) | 실측 확정 2026-08-13/09-11. 단 `toOutstandingOrder` 세부는 미확정(P2-2) | execution:29-37,97-101,195-199; BrokerBalance:50-58 |
| `KiwoomMarketDataAdapter` 응답 필드(ka10001/10004/10081/10080) | 실측(docs/measured/tr_probe_20260918_*) | market:73-77 |
| `TokenManager.EXPIRES_DT_FORMAT`(KST 14자리) | 실측 2026-08-13 | kiwoom:56-62 |
| `DartClient` 파싱 계약, `DisclosureType.reportNameFragment` | 실측 픽스처 테스트 | ipo/DartClient.java:32-70; macrointel/DisclosureType.java:239-245 |
| `TrRateLimiter` 1건/초, `KiwoomRestClient` 429/1700/8005 규칙 | 실측 2026-09-23/09-30 | kiwoom:257-261, 60-64, 112-115 |
| `OrderStatus.TRANSITIONS` 전이표, `OrderEntity.transitionTo` | 상태기계 = 안전 규칙; `OrderStatusTest` 9건·`OrderEntityTest` 8건이 고정 | trading:229-243 |
| `common.event.*` 필드 이름·JSON 형식 | `event_store` 과거 행 역직렬화 호환(A1 조각들이 "형식 동일" 전제) | JacksonConfig:21-24 |
| `TradingProperties` prefix `execution` | yml·bat 호환 결정 | trading:116-119 |

---

## 7. 확장 지점

| 확장 | 영향 파일 | 난이도 | 비고 |
|---|---|---|---|
| 새 전략(중기, 일봉) | `strategy/`에 새 `@Component`(cron 또는 `StrategyEngine.onTick`), `RiskGate.horizonFor`(risk:384-386 "C3" 접두 하드코딩), `C3StrategyProperties`와 유사한 record, `ClientOrderId` strategyId 규칙(영숫자·하이픈) | 낮음 | Signal 계약만 지키면 됨. `horizonFor` 매핑을 전략이 `SignalDecision.horizon`으로 직접 싣게 바꾸면 더 낮음 |
| 새 지평(단타/스윙) | 위 + `MarketTick` 소비자(`StrategyEngine`), 분봉 데이터(`MinuteBarArchiver` 축적 중), `RiskGate`의 "보유 중 추가 매수 금지"(risk:244) 정책이 지평 간 충돌(같은 종목을 두 전략이 보유) → 포지션을 전략별로 나누는 `PositionBook` 확장 필요 | 중간~높음 | `PositionBook` 키가 `StockCode`만(portfolio:57) |
| 새 브로커 | `execution.BrokerPort` 구현체 1개 + `MarketDataPort` 구현체 1개 + WS 클라이언트(현재 `KiwoomWebSocketClient`는 포트 없이 market 내부 직결) + `TokenManager`/`TrId` 대응 + `@ConditionalOnProperty` 선택 | 중간 | WS는 포트가 없어 새 클래스 병행이 필요. `BrokerBalance.holdings` Map(P2-1) 먼저 typed로 |
| 새 알림 채널(Slack 등) | `monitor.Notifier` 구현체 추가, `@ConditionalOnProperty` 배타 조건(`LogOnlyNotifier`:102, `TelegramNotifier`:203) 재설계(현재 enabled=false↔true 이분법) | 낮음 | 다중 채널이면 `CompositeNotifier` |
| 이벤트 외부화(Kafka) | 발행은 전부 `ApplicationEventPublisher`(동기). Modulith 외부화(`@Externalized` + `spring-modulith-events-kafka`)를 붙이려면 `@ApplicationModuleListener` 전환(P1-7)과 발행 로그 활성화가 선행. VO JSON 직렬화는 이미 `JacksonConfig`로 준비됨 | 중간 | 동기 리스너 순서에 의존하는 곳(`DailyPnlTracker` Javadoc이 순서 비의존 설계를 명시)은 안전 |
| Boot 4 / Modulith 2 마이그레이션 | `build.gradle`(BOM), `ClientHttpConnectorBuilder`/`ClientHttpConnectorSettings`(Boot 3.5 신규 API, ipo/DartClient:6-7, macrointel/MajorDisclosureDartClient) — Boot 4에서 패키지 이동 가능, `spring.http.reactiveclient.*` 키 변경 가능, `ResponseEntityExceptionHandler` 시그니처, Jackson 3 전환(`JacksonConfig` `Module`/`JsonSerializer` API), `springdoc` 3.x 필요, `archunit` 버전, Testcontainers/zonky BOM, `@Modulithic` API | 높음 | 외부 HTTP 커넥터·Jackson·springdoc 3곳이 핵심. `ModularityTests`·`ArchitectureRulesTest`가 회귀 안전망 |

---

## 8. 의존성·빌드

### 8.1 의존성 전수 (확인: `build.gradle`, `app/build.gradle`, `common/build.gradle`)

| 구분 | 아티팩트 | 버전 |
|---|---|---|
| 플러그인 | `org.springframework.boot` | 3.5.16 |
| 플러그인 | `io.spring.dependency-management` | 1.1.7 |
| BOM | `spring-modulith-bom` | 1.4.12 |
| 툴체인 | Java | 21 |
| Gradle wrapper | | 8.14.2 |
| app impl | `spring-boot-starter-{web, webflux, websocket, data-jpa, validation, actuator, cache}` | Boot BOM |
| app impl | `springdoc-openapi-starter-webmvc-ui` | 2.8.9 |
| app impl | `caffeine` | Boot BOM |
| app impl | `spring-modulith-starter-core`, `spring-modulith-starter-jpa` | 1.4.12 |
| app impl | `flyway-core`, `flyway-database-postgresql` | Boot BOM |
| app runtime | `postgresql` | Boot BOM |
| app impl | `resilience4j-ratelimiter`, `resilience4j-retry` | 2.2.0 |
| app impl | `jna-platform` | 5.14.0 |
| app test | `spring-boot-starter-test`, `spring-modulith-starter-test` | BOM |
| app test | `archunit` | 1.4.2 |
| app test | `testcontainers:postgresql` | Boot BOM |
| app test | `io.zonky.test:embedded-postgres` / `embedded-postgres-binaries-bom`(enforcedPlatform) | 2.1.0 / 16.15.0 |
| common compileOnly | `spring-modulith-api` | 1.4.12(별도 고정 — BOM 미적용, app BOM과 수동 동기 필요) |
| common test | `junit-bom`, `junit-jupiter`, `junit-platform-launcher` | 5.11.4 |

관찰: 버전 카탈로그 없음(P3 "유지"). `common`의 modulith 버전이 app BOM과 따로 적혀 있어 업그레이드 시 두 곳 수정. SCA(Dependency-Check)·SAST·gitleaks 없음(§18.2 도구 미적용).

### 8.2 테스트 수

| 항목 | 값 |
|---|---|
| 테스트 파일 | 114(app 107, common 7) |
| `@Test` | 557 + `@ParameterizedTest` 1 (grep; large-classes.md 실행 기록 573건·건너뜀 16과 정합) |
| DB 통합 테스트 | 2 클래스(`PostgresDataJpaTest` 상속) |
| 아키텍처 테스트 | `ModularityTests` 1, `ArchitectureRulesTest` 10 |
| 스모크(IT) | 3(키움·DART 2) — 키 없으면 `assumeTrue` 스킵 |
| 실데이터 실험 | `RealData*`/`Gate*` 11 클래스 — `data/` 없으면 스킵 |

### 8.3 CI (`.github/workflows/ci.yml`, 확인)

- 트리거: push main, PR, 수동. ubuntu-latest, Temurin JDK 21, `gradle/actions/setup-gradle@v4`, `./gradlew test --no-daemon`, 실패 시 `app/build/reports/tests/test` 업로드(7일).
- 시크릿: `KIWOOM_MOCK_G_APP_KEY/SECRET`만 전달(LIVE 금지). `DART_API_KEY` 미전달 → DART 스모크 항상 스킵.
- 없음: 빌드 캐시 외 정적 분석, 의존성 취약점 검사, 커버리지 리포트, Docker 서비스(Testcontainers는 러너의 Docker 사용, 없으면 zonky 폴백).

---

## 9. 코드 규모

측정: `wc -l`(주석 포함 전체 줄), `app/src/main/java/com/autostock`, 2026-10-01.

| 모듈 | 파일 | 줄 |
|---|---|---|
| backtest | 30 | 4,392 |
| monitor | 46 | 2,743 |
| market | 16 | 1,868 |
| trading | 15 | 1,566 |
| risk | 16 | 1,395 |
| ipo | 10 | 1,036 |
| macrointel | 9 | 901 |
| strategy | 8 | 713 |
| execution | 9 | 636 |
| kiwoom | 7 | 529 |
| config | 7 | 277 |
| audit | 4 | 164 |
| portfolio | 2 | 144 |
| analysis | 1 | 6 |
| 루트 | 1 | 24 |
| **app 합계** | **181** | **16,394** |
| common | 28 | 1,023 |
| **총계** | **209** | **17,417** |

상위 10개 큰 클래스(전체 줄):

| 순위 | 클래스 | 줄 | 성격 |
|---|---|---|---|
| 1 | `backtest/CrossSectionalBacktestRunner` | 580 | 오프라인(불변) |
| 2 | `backtest/InverseSwitchWalkForwardRunner` | 446 | 오프라인(불변) |
| 3 | `market/KiwoomWebSocketClient` | 404 | 운영(B3 추출 후) |
| 4 | `strategy/C3LiveStrategy` | 391 | 운영 |
| 5 | `risk/RiskGate` | 387 | 운영(유지 결정) |
| 6 | `ipo/DartClient` | 361 | 운영(ACL) |
| 7 | `market/HolidaySyncService` | 348 | 운영 |
| 8 | `backtest/PerformanceCalculator` | 340 | 오프라인 |
| 9 | `trading/TradingService` | 311 | 운영 |
| 10 | `backtest/BacktestRunner` | 306 | 오프라인 |

---

## 요약: 상위 10개 개선 기회

| 순위 | 개선 기회 | 영향 | 난이도 | 선행 조건 |
|---|---|---|---|---|
| 1 | 미체결 타임아웃 취소 대상에 ACCEPTED·PARTIALLY_FILLED 포함, 취소 성공 시 CANCELLED 확정, Reconciliation에 ACCEPTED/CANCEL_REQUESTED 포함(P0-3) | 높음(주문 안전·자본 잠식) | 낮음 | 없음. `StaleOrderCancellerTest`·`ReconciliationServiceTest` 확장 |
| 2 | 킬스위치·일 손실·PnL 장부 영속화 및 재기동 복원, `DailyPnlTracker`가 `PositionRestored` 구독(P0-2) | 높음(비상 정지 무력화) | 중간 | 신규 Flyway V9(킬스위치 상태), 사용자 결정(복원 정책) |
| 3 | 파생 delete 쿼리 트랜잭션 경계 + DB 통합 테스트(P0-1) | 중간(블랙리스트 정합) | 낮음 | `PostgresDataJpaTest` 인프라(있음) |
| 4 | C3 판단 주기를 백테스트와 동일한 거래일(봉) 기준으로 통일 + `lastDecisionDate` 영속화(P1-1) | 높음(trial 재현성·회전율) | 낮음~중간 | 거래일 카운트 API(`MarketCalendarService`), 저장 위치 결정 |
| 5 | RiskGate에 미체결 주문(예약 포지션) 반영 + 슬롯 번호 원자 반환(P1-2, P1-3) | 높음(중복 주문) | 중간 | trading→risk 역방향 없이 전달할 포트/이벤트 설계 |
| 6 | 알림·감사 등 부수효과 리스너를 WS 리더 스레드에서 분리(`@Async` 전용 실행기, 격벽)(P1-5) | 중간(WS 안정성) | 낮음 | `AsyncConfig`에 executor 빈 추가 |
| 7 | `probeFillStatus` 실측 구현(체결내역 TR) + brokerOrderId 없는 UNKNOWN 매칭(당일 주문내역 TR)(P2-3, R4(b)) | 높음(UNKNOWN 자동 해소) | 중간 | 키움 TR 실측(모의 서버), `TrId` 추가 |
| 8 | Modulith 발행 로그를 실제로 쓰거나 제거(`@ApplicationModuleListener` 전환 vs 테이블·의존성 정리) + `schema_version` 실제 반영(P1-7, P2-12) | 중간(감사 신뢰성, Kafka 외부화 선행) | 중간 | ADR(이벤트 유실 허용 범위) |
| 9 | 주기 포지션 대사(잔고↔PositionBook) + 불일치 알림(P1-4) | 중간 | 중간 | `BrokerBalance.holdings` typed record(P2-1) 선행 |
| 10 | 관측성 보강: WS 연결·킬스위치·UNKNOWN 체류·대사 실패 게이지/카운터, 커스텀 `HealthIndicator`, `test-signal` LIVE 가드·경로 변수 검증(5.1, P1-6, P2-9) | 중간(무인 운영 감시) | 낮음 | 없음 |

--- 
검증 상태: 이 보고서는 코드 정독과 grep 기반이다. Gradle 빌드·테스트·DB 실측은 이 환경에서 실행하지 않았다(**미검증**). "추론" 표기 항목(P0-1의 readOnly 트랜잭션 동작, P1-5의 WS 연결 종료, P2-7의 fixedRate 동시 실행)은 `PostgresDataJpaTest`·운영 로그로 확인이 필요하다.

## 부록 — 보강 확인으로 추가된 BE 결함 후보 (2026-10-01)

| # | 항목 | 근거 | 제안 |
|---|---|---|---|
| P1-9 | 키움 유량 재시도가 `[1700`만 판정 — 공식 오류 `1701`(전체 총유량)·`1702`(그룹)는 일반 실패 | `kiwoom/KiwoomRestClient.java:157-159`, 공식 스펙 오류코드 | 판정에 1701·1702 추가(같은 1.1초 간격·4회) |
| P1-10 | 인증 오류 분류 부재 — `8010`(IP 불일치)은 재발급으로 회복 가능, `8001/8002/8040/8050/8103`(키·단말 인증 실패)은 재시도 무의미한데 모두 일반 예외 | 동상, [07 §1-8](07-research-trading-market.md) | `8010`은 8005와 같은 1회 재발급·재시도, 나머지는 즉시 P1 알림 + 원인 안내(IP 재등록·키 상태·서비스 해지 확인) |
| P2-21 | `event_publication`이 구형(1.1) 스키마 — Modulith 2.x 이관 시 `ddl-auto: validate` 실패 예상(추론) | `V1__event_store.sql:15-24`, [Modulith 부록](https://docs.spring.io/spring-modulith/reference/appendix.html) | 널 허용 3컬럼 추가 마이그레이션을 이관 전에 선행(Expand) — 또는 발행 로그를 쓰지 않기로 하면 starter-jpa 제거(D-07) |
