# 01. 현황 진단 — 고도화 출발점 (2026-10-01)

> 문서 묶음: [00-README](00-README.md) · [01 현황](01-current-state.md) · 조사 [02 BE](02-research-be.md) · [03 FE](03-research-fe.md) · [04 디자인](04-research-design.md) · [05 기획](05-research-planning.md) · [06 인프라](06-research-infra.md) · [07 주식거래①시장·제도](07-research-trading-market.md) · [08 주식거래②전략·LLM](08-research-trading-strategy.md) · 감사 [09 BE](09-audit-be.md) · [10 FE](10-audit-fe.md) · **[11 실행 계획](11-execution-plan.md)** · [12 결정 목록](12-decisions.md)

- 근거: `PROGRESS.md`(2026-09-30), `PLAN.md` v5(ADR-1~14), `docs/ARCHITECTURE.md`, `docs/RUNBOOK.md`, `aiDoc/refactoring-plan.md`, `aiDoc/large-classes.md`, 코드 정독([09 BE 감사](09-audit-be.md), [10 FE 감사](10-audit-fe.md)).
- 표기: **확인** = 문서·코드에서 직접 확인 / **추론** = 실측 없는 판단. 빌드·테스트는 이 조사 환경에서 실행하지 않았다(**미검증** — 계획서의 검증 절차로 확인한다).

## 1. 한 줄 요약

매매 코어(시세→전략→RiskGate→주문→체결→감사)와 검증 인프라(게이트 v2: SPA·부트스트랩 MDD)는 완성도가 높고, 2026-09-11부터 모의 무인 운영(게이트 ②) 중이다. 반면 **21계열 전략이 전부 게이트 ① FAIL**이라 "무엇을 실계좌에 넣을 것인가"는 미결(ADR-15 초안 대기)이고, 무인 운영에서 **재기동·취소·킬스위치 영속성**에 P0급 결함 3건이 남아 있으며, 플랫폼(Spring Boot 3.5)은 **OSS 지원이 2026-06-30에 종료**된 상태다.

## 2. 시스템 규모·구성 (확인)

| 항목 | 값 |
|---|---|
| 스택 | Java 21 · Spring Boot 3.5.16 · Spring Modulith 1.4.12 · PostgreSQL 16(Docker) · Flyway V1~V8 · Gradle 8.14.2 |
| 코드 | app 181파일/16,394줄, common 28파일/1,023줄 (Java) · frontend 2,264줄(TS/TSX/CSS) |
| 모듈(Modulith) | market, strategy, risk, trading, execution, portfolio, audit, monitor, ipo, macrointel, backtest, analysis(빈), shared: kiwoom·config |
| 테스트 | 114파일 · `@Test` 557건(2026-09-30 실행 기록 573건 통과·16 skip) · DB 통합 2건 · 아키텍처 11건 · 스모크 3건(키 있을 때만) |
| 외부 연동 | 키움 REST(mockapi)·WS, DART(공시·IPO), FRED, ECOS, 공공데이터 특일, Telegram(미활성) |
| 스케줄 | 21건(cron 13 + fixedDelay 7 + fixedRate 1). 핵심: 09:05 C3 판단, 08:20 IPO, 08:30 매크로, 08:35 공시, 15:45 분봉, 15:50 리포트 |
| REST API | 18 엔드포인트(6 컨트롤러) + actuator + swagger, RFC 9457 ProblemDetail, 인증 없음(루프백 127.0.0.1 바인딩) |
| FE | Vite 5.4 + React 18.3 + TanStack Query 5 + recharts 2, 탭 5개, 단일 `/api/dashboard` 2초 폴링, 빌드 산출물 커밋(gzip 172KB 단일 청크) |
| 운영 | Windows 11 PC 1대 24h, `start_autostock.bat`(로그인 자동시작) → bootRun, 장중 08:30~16:00 ACTIVE·장외 STANDBY, JNA 절전 차단, 백업 없음 |

## 3. 완료된 것 (건드리지 않는다)

`aiDoc/refactoring-plan.md` 상단 상태 목록 기준 — S1·S2·R1~R4(a)·A1(조각 22)·A2~A5·B1~B3·P3 Testcontainers 완료. 값 객체(StockCode·Quantity·Price·BrokerOrderId), 포트 분리(BrokerPort·MarketDataPort), ArchUnit 규칙, Clock 주입, 시간대 명시, 낙관적 잠금(V8), RFC 9457, 루프백 바인딩, 타임아웃 공통 적용, WS 백오프·단절 추출, 누적→증분 체결 계산 추출.

## 4. 이번 조사에서 새로 확인한 결함 (계획 Phase 0의 근거)

| # | 결함 | 근거(확인) | 왜 P0인가 |
|---|---|---|---|
| P0-3 | `StaleOrderCanceller`가 **SUBMITTED만** 취소 대상. LIVE에서는 WS "접수" 통보로 즉시 ACCEPTED가 되므로 **미체결 타임아웃 취소가 사실상 동작하지 않음**. 취소 성공 후 CANCEL_REQUESTED에 영구 체류. Reconciliation 대상도 UNKNOWN·SUBMITTED뿐 | `trading/StaleOrderCanceller.java:66`, `ReconciliationService.java:117-118`, `OrderNoticeHandler.java:244-257` | 미체결이 쌓이면 동시 보유 한도·자본이 잠기고, 취소 상태가 화면과 어긋난다 |
| P0-2 | 킬스위치(`AtomicBoolean`)·일 손실 누적·PnL 장부가 **전부 인메모리** → 재기동 시 킬스위치 해제, 일 손실 0으로 리셋. `TradingAutoStarter`는 "킬스위치가 켜진 채 재시작해도 안전"이라 적었으나 실제로는 켜져 있지 않음 | `risk/KillSwitch.java:38`, `risk/DailyPnlTracker.java`, `monitor/TradingAutoStarter.java` Javadoc | auto-start 재기동 = 사람 확인 없는 비상 정지 해제(설계 원칙 "해제는 사람만" 위반) |
| P0-1 | `DisclosureBlacklist.remove/releaseExpired`가 파생 delete 쿼리를 **트랜잭션 없이** 호출(추론: readOnly 기본 트랜잭션에서 미플러시 또는 오류) | `risk/DisclosureBlacklist.java:92,127` | 만료 행 영구 누적·수동 해제가 재기동 후 되살아남. mock 테스트라 못 잡음 |
| P1-1 | **백테스트≠라이브**: C3 판단 주기가 백테스트는 21봉(거래일), 라이브는 `plusDays(21)` 역일 | `strategy/C3LiveStrategy.java:370` vs `backtest/TimeSeriesMomentumStrategy.java:63` | ARCH 규칙 18 위반. 라이브가 약 1.4배 자주 판단 → 게이트 ② 격차 계측이 오염 |
| P1-2/3 | RiskGate가 미체결 주문을 모름(같은 종목 BUY 시그널 중복 통과), 일련번호 슬롯 획득과 번호 읽기가 비원자 | `risk/RiskGate.java:177-197,244-255` | 중복 주문·`ClientOrderId` 충돌(UNIQUE로 조용히 스킵) |
| P1-5 | `Fill` 소비자(텔레그램 HTTP `.block()` 최대 20s)가 **WS 리더 스레드에서 동기 실행** | `market/KiwoomWebSocketClient.java:394-402` → `monitor/TradeNotificationListener.java:31-35` | PING 에코 지연 → 서버 측 연결 종료 가능(추론) |
| P1-6 | `POST /api/dashboard/test-signal`이 LIVE에서도 실주문 경로, FE는 기본 종목 005930·수량 공란=자동 사이징·확인 단계 없음 | `monitor/DashboardController.java:112-132`, `frontend/src/components/TestSignalCard.tsx:10-13,87-92` | 2026-09-18 "193주 5,000만 원 즉시 체결" 사고의 직접 원인, 재발 가능 |
| P1-7 | `spring-modulith-starter-jpa`·`event_publication` 테이블은 있으나 `@ApplicationModuleListener` 사용처 0건 → 발행 로그 **한 번도 기록되지 않음**. 감사 리스너 Javadoc의 "발행 로그가 유실을 보완" 주장은 사실이 아님 | `audit/EventAuditListener.java:29-31` | 앱 사망 시 감사 기록 유실 복구 원천 없음 |
| P1-8 | `MacroGuard`가 지표 신선도를 검사하지 않음 — 수집이 며칠 실패해도 낡은 VIX로 판단 | `risk/MacroGuard.java:59,74-104` | 낡은 값으로 매수 차단/허용 |
| FE | 명령 실패 무음(ProblemDetail 미파싱), 폼 레이블 0개·키보드 불가 요소·라이브 리전 없음(KWCAG 불합격), 색 대비 3.0~3.2:1, `IpoMetricsInput.listingDate` 계약 누락, 테스트·린트·CI 게이트 없음, Vite 5 지원 종료 | [10 FE 감사](10-audit-fe.md) 2~5절 | 운영자 오판·오조작, 접근성 기준 미달 |
| P1-9/10 | 키움 유량 재시도가 `[1700`만 판정(공식 `1701`·`1702` 누락), 인증 오류(`8010` IP 불일치, `8001/8002/8040/8050/8103` 키·단말 인증 실패)를 구분하지 않음 | `kiwoom/KiwoomRestClient.java:152-159`, 키움 공식 스펙 오류코드 | IP 변경·서비스 해지가 "원인 모를 실패"로 보임 |
| 운영 | 백업 없음, 외부 감시(dead-man switch) 없음, 텔레그램 미활성, 로그인 세션 종속 자동시작(bootRun). **키움 허용 IP 등록 필수(10개)·3개월 미접속 시 자동 해지(실서버 기준 — 모의만 쓰면 해지 가능)** | RUNBOOK, scripts/, [키움 안내](https://openapi.kiwoom.com/intro?dummyVal=0) | 무인 운영의 "침묵" 장애를 사람이 못 알아챔, 서비스 해지 시 전면 중단 |
| 플랫폼 | Boot 3.5 OSS 지원 2026-06-30 종료, Vite 5 지원 종료, `spring.http.reactiveclient.*` 4.0에서 deprecated | [02 BE](02-research-be.md), [03 FE](03-research-fe.md) | 보안 패치 부재 |

## 5. 전략·검증 관점 현황 (확인, PROGRESS 3절·4-1절)

- 게이트 v2(ADR-12): OOS 양(+) · SPA_c p<0.05 vs KODEX200 B&H · 부트스트랩 p95 MDD < 지평 예산 · 슬리브 환산 총계좌 MDD<15%. **21계열 전부 FAIL**(시계열 17 + 횡단면 4). X0 진단(동일가중 무선별)도 t=−2.01로 유의하게 하회.
- 진단 문서(`docs/research/gate1_diagnosis_20260918.md`, `x0_diagnostic_20260923.md`)의 권고 = **ADR-15: A+C(벤치마크 유지 + 코어 KODEX200 B&H + 위성)**, 트랙 X 종결(X3 취소). **사용자 결정 대기**.
- 최신 문헌([08 주식거래②](08-research-trading-strategy.md))은 이 진단을 **강화**한다: 지수 1종 볼타겟은 추정오차+거래비용이 이득 상쇄(DeMiguel 2024 JF), 삼성전자+하이닉스 코스피 비중 58.9%(2026-06), 시총가중 우위 구간은 5~12년 지속(Cambria 2025), 라이브 헤어컷 ~57%(Azevedo 2025), **2026-07-01~09-01 KODEX 200 −22.15% vs KODEX 200동일가중 +2.34%(삼성전자+SK하이닉스 편입 비중 ≈60%)**, LLM 시그널 백테스트는 학습 컷오프 이전 구간 오염(arXiv 2512.23847 외 3편). 단 "10년 OOS FAIL = 영구 무효"가 아니라 "구간+검정력" 문제라는 해석 주석이 필요하다.
- C3 모의 운영은 실계좌 후보가 아니라 **운영 능력 검증 + 새 데이터 성과 축적 + 슬리피지 실측** 목적(RUNBOOK 0절). 이 성격은 유지한다.

## 6. 시장·제도 변화 (2026-10-01 확인, [07 주식거래①](07-research-trading-market.md))

| 변화 | 시스템 영향 |
|---|---|
| **키움 허용 IP 필수(최대 10개)·공인 IP 변경 시 `8010`, 3개월 미접속 자동 해지(실서버 기준)** | 새 호스트는 IP 등록 선행, IP·인증 오류 알림, 해지 방지 절차(D-02) |
| **KRX 애프터마켓 2026-09-14 개장(16:00~20:00)**, 공식 종가는 15:30 정규장 종가 유지, ETF 제외, 시장가 불가 | 지표·백테스트 불변. 보유 포지션의 장후 가격 변동 감시(0B 구독 20:00까지)는 선택. 시간외단일가(trde_tp 62) 경로 폐기 |
| 프리마켓·12시간 체제는 **2027년 말**로 연기, 단일 호가장(세션 간 미체결 유효) 계획 | "장 마감=미체결 소멸" 가정을 세션 개념으로 추상화(2027 대비, 낮음) |
| 키움 스펙: 유량 오류 **1700(TR)·1701(총)·1702(그룹)**, 토큰 **IP 바인딩(8010)**, 주문 `dmst_stex_tp` KRX/NXT/SOR, 종목코드 `_NX`/`_AL`, 체결 조회 `ka10076`, 계좌 체결내역 `kt00007` | 1701/1702 백오프 추가(현재 1700만), 클라우드 이전 시 토큰 재발급, R4(b) 체결 조회 TR 확정 |
| 금융위 HFT 규율 검토 첫 공식 언급(2026-09-28), 과다호가부담금 법안 계류 | 정정·취소 남발 금지 유지, 일별 OTR(호가 대비 체결) 메트릭 기록 시작 |
| 2026 세율: 코스피 0.05%+농특세 0.15%=0.20%, 코스닥 0.20% | 현행 `sell-tax-rate=0.0020` 정확. 연 1회 점검 |
| IPO 2026: 시초가 평균 +126.8%, 청약경쟁률 1,316:1, 확약률 27.2%(제도 변경), 하반기 보유 수익 −25.6% | ADR-9 필터 임계 재정의(분위수), 시초가 매도 규칙 유지, 12:00 추가 동기화 검토 |

## 7. 건드리면 안 되는 곳 (전 Phase 공통 제약)

| 대상 | 이유 |
|---|---|
| `backtest/*Runner`, `PerformanceCalculator`, `SpaTest`, `StationaryBootstrap`, `CostModel`, `TradeIntent` | PROGRESS trial 기록 재현성(사용자 결정 2026-09-30). 리팩토링이 수치를 바꾸면 기존 21계열과 비교 불가 |
| `strategy/*Math`, `risk/KrxTickSize` | 백테스트=라이브 동일 계산(ARCH 규칙 17·18), ArchUnit이 순수성 고정 |
| `app/src/test/.../backtest/RealData*ExperimentTest`, `Gate*Test`, `CrossSectionalStrategies` | 사전 선언 실험 — 결과를 본 뒤 수정은 새 trial 선언 없이 금지 |
| `RealMessageParser` FID 매핑, `OrderNotice` 누적 의미, `KiwoomBrokerAdapter` 요청·응답 키, `KiwoomMarketDataAdapter` 필드, `TokenManager.EXPIRES_DT_FORMAT`, `DartClient` 파싱 | 2026-08~09 실측 확정 값 |
| `OrderStatus.TRANSITIONS`, `OrderEntity.transitionTo` | 상태기계 = 안전 규칙(전이 추가만 허용, 기존 전이 변경 금지) |
| `common.event.*` 필드 이름·JSON 형식 | `event_store` 과거 행 역직렬화 호환 |
| `TradingProperties` prefix `execution`, 환경변수 3단 체계(`KIWOOM_MOCK_G_*`/`KIWOOM_LIVE_*`) | yml·bat·CI 호환 결정 |

## 8. 확장 지점 (계획에서 활용)

- 새 전략: `strategy/`에 `@Component` 추가 + `RiskGate.horizonFor`(현재 "C3" 접두 하드코딩) 일반화 — 코어 슬리브 전략(Phase 3)이 첫 사용처.
- risk가 trading 상태를 알아야 할 때: `EquitySource`(risk 정의, execution 구현) 선례를 따라 **risk가 포트를 정의하고 trading이 구현**(순환 없음).
- 새 알림 채널: `monitor.Notifier` 구현체 + `@ConditionalOnProperty` 배타 조건 재설계.
- Boot 4 이관 핵심 접점: `ClientHttpConnectorBuilder`(DART 커넥터), `JacksonConfig`(Jackson 3), springdoc, `spring.http.reactiveclient.*`, starter 이름.

## 9. 이 문서를 읽은 다음

- 무엇을 어떤 순서로 바꾸는지: [11 실행 계획](11-execution-plan.md)
- 먼저 정해야 할 것: [12 결정 목록](12-decisions.md) — 특히 D-05·D-06(10/2), D-01(10/16)
