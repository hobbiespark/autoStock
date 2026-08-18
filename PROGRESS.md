# autoStock 진행 현황

기준일: 2026-08-13 (2차 갱신) | 계획: [PLAN.md](PLAN.md) v4 (ADR 6건) | 아키텍처: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 리포: github.com/hobbiespark/autoStock

> **작업 방침 (확정)**: 실제 API(모의 포함) 호출 검증 금지 — 회사망 모니터링 사유.
> 계획·문서 기반 예상 구현 + `TODO 실측` 표기로 진행하고, 실측은 자택망에서 별도 수행.

---

## 1. 한눈에 보기

| Phase (PLAN 10절) | 상태 | 비고 |
|---|---|---|
| 0. 준비 (키 발급) | ✅ 완료 | 모의투자 일반 계좌 키 확보, GitHub 시크릿 3단 구분(G/P/실전) |
| 1. 기반 (Modulith·이벤트스토어·키움 클라이언트) | ✅ 완료 | **완료 기준(모의계좌 조회) 실측 달성** |
| 2. 매매 코어 (WS·risk·execution) | 🔶 90% | 주문 왕복 실측 완료. 잔여: WS 실측(로컬 ws_probe 실행 필요) |
| 3. 백테스트 (리플레이·비용모델·DSR/PBO) | ✅ 완료 | walk-forward + DSR/PBO + 실데이터 검증 파이프라인 가동 |
| 4. 전략+리스크 | 🔶 진행 중 | 전략 3안 실험 완료, **게이트 ① 미통과** — 판정 도구 교정 + 포트폴리오 검증 예정 |
| 5. 거시 필터 (macro-intel) | ⬜ 미착수 | |
| 6. 운영 검증 (무인 모의 운영) | ⬜ 미착수 | |
| 7. 확장 (news-intel·Kelly·CPCV) | ⬜ 미착수 | sidecar-nlp 골격만 존재 |

부가 완료: 경량 대시보드 FE(킬스위치·테스트시그널·포지션·이벤트 피드), GitHub Actions CI(시크릿 연동 스모크 포함), Gradle Wrapper.

테스트: **68건 통과** (Modulith 경계 검증 포함). 실행 위임 체계: 계획·조사·분석 = Fable 5, 코드 실행 = sonnet 에이전트.

## 2. 실측 검증 기록 (mockapi.kiwoom.com, 2026-08-13)

| 항목 | 결과 | 반영 커밋 |
|---|---|---|
| 토큰 발급 (au10001) | ✅ `expires_dt`(yyyyMMddHHmmss, KST) 파싱 확정 | e063b67 |
| 잔고 조회 (kt00018) | ✅ 예탁자산·보유종목 확인. 종목코드 `A` 접두 발견 | e063b67 |
| 현재가/일봉 (ka10001/ka10081) | ✅ 일봉 수집 서비스 구현, `+/-` 부호 정규화 | e063b67 |
| 매수 주문 (kt10000) | ✅ 시장가 1주 즉시 체결 → 잔고 반영 확인 | 5e2aebf |
| 매도 주문 (kt10001) | ✅ 전량 청산 → 잔고 0 원상복구 | 5e2aebf |
| 미체결 조회 (ka10075) | ✅ 응답 키 `oso` 확정 | 5e2aebf |
| WS (포트 10000) | ⬜ 샌드박스 차단 — **로컬에서 `python3 scripts/ws_probe.py` 실행 필요** | d5de321 |
| 계좌 이슈 | 최초 키는 공매도 교육 전용 계좌(RC5006, 주문 불가) → 일반 계좌 키로 교체 해결 | — |

## 3. 전략 연구 현황 (PLAN 7절 파이프라인)

### 실험 1 — 변동성 돌파 v1 (무필터): **폐기 사유 확보**
5종목(삼전·하이닉스·NAVER·카카오·KODEX200, 2019~2026 일봉) walk-forward OOS 전 종목 손실(-1.6%~-84.7%). 원인: 연 250~300회 왕복 × ~0.35% 비용 드래그. trial 기록 보존.

### 실험 2 — 3안 비교 + 비용 민감도 (커밋 14c1ed6)

| 전략 | 거래수 | OOS 성적 | 비용 2배 강건성 |
|---|---|---|---|
| A 무필터 돌파 | 841~1,103 | 전 종목 손실 | 붕괴 |
| B 필터 돌파(SMA20+양봉) | 173~232 | 2/5종목 흑자 전환 | 취약 |
| C 시계열 모멘텀(월간, N∈{60,120,200}) | 7~14 | 4/5종목 흑자 (삼전 +592%, 하이닉스 +1,551%) | 거의 무영향 |

해석 (과대해석 금지): C는 알파가 아니라 "추세 노출 + 하방 방어" — 승자 종목에선 buy&hold 하회, 하락 종목(카카오)에서 방어 우위. 거래 빈도가 성과의 지배 변수임을 실증.

### 판정 도구 결함 발견 (다음 사이클에서 교정)
- DSR에 N=125(후보×윈도)를 적용해 전부 0.000 — walk-forward OOS는 선택 완료된 단일 결과이므로 N=비교 전략 계열 수(3)가 방법론상 옳음
- MDD를 전량 투입 단일 종목 기준으로 판정 — 게이트의 MDD<15%는 5종목 분산 + 사이징 반영한 포트폴리오 수준에서 판정해야 공정

### 게이트 현황 (PLAN 1절) — 2026-08-13 3차 갱신

**게이트 ① 잠정 통과 (C3 전략)** — 커밋 cee55e0, 실험: RealDataRegimeVolExperimentTest

| 변형 | OOS수익률 | CAGR | MDD | Sharpe | 교정DSR(N=4) | 게이트① |
|---|---|---|---|---|---|---|
| C0 시계열모멘텀 | +466.1% | 31.4% | 29.2% | 1.23 | 0.998 | FAIL(MDD) |
| C1 +국면필터(KODEX SMA200) | +231.3% | 20.8% | 19.8% | 1.09 | 0.995 | FAIL(MDD) |
| C2 +변동성타게팅(연 20%) | +233.3% | 20.9% | 25.8% | 1.22 | 0.998 | FAIL(MDD) |
| **C3 +둘 다** | **+124.1%** | **13.6%** | **13.4%** | **1.05** | **0.992** | **PASS** |

- 구성: `VolatilityTargetingStrategy(RegimeFilteredStrategy(TimeSeriesMomentum))` — 국면필터가 "할지 말지", 볼타겟이 "얼마나". 국면 OFF일수 35.8%
- MDD 29.2%→13.4% 절감 실증. TradeIntent에 투입 비중(fraction) 하위호환 추가
- ~~유보 조건: N=6 재계산으로 확인 후 확정~~ → **보수 검증 결과 통과 철회** (커밋 e7f6023, GateDsrRobustnessTest)

**N=6 강건성 검증 결과 (2026-08-13)**: 연구 전체에서 OOS를 비교한 6계열(A/B/C0~C3) 기준으로 재계산하면 C3의 DSR = **0.314** (N=4의 0.992에서 급락). A/B의 큰 음의 샤프가 계열 간 분산을 키워 "운으로 기대되는 최대 샤프"가 상승한 결과. **게이트 ① 최종 판정: FAIL** (MDD 13.4%·수익 +는 충족, DSR만 미달).

**해석과 방향 (분석 확정)**:
- 이는 시스템 실패가 아니라 과최적화 방지 장치가 설계 목적대로 작동한 것. C3의 성과가 "6번 다양하게 시도한 것 중 최고가 우연히 나온 수준"과 95% 신뢰로 구분되지 않는다는 뜻
- **백테스트 반복 튜닝은 여기서 중단** — 반복할수록 N만 늘어 DSR이 더 나빠지는 구조. C3를 후보 전략으로 동결(등록)한다
- 신뢰도를 올리는 정당한 수단 2가지: ① 관측 T 확장 — 데이터 기간을 2015년~ 등으로 늘려 재검증(더 많은 국면 포함) ② **진행형 검증** — 모의 무인 운영(게이트 ② 단계)을 "검증 연장"으로 활용해 백테스트가 아닌 새 데이터로 성과 축적. 단 실계좌 진입 게이트는 그대로 유지

게이트 ②·③: 미도달

## 3-1. 완료 추가 기록 (2차 갱신)

- ✅ **DSR 교정 + 포트폴리오 슬리브 러너** (커밋 0b5d7c2): OOS 레벨 DSR(`deflatedSharpeAcrossFamilies`, N=전략 계열 수), 5종목 슬리브 포트폴리오(교차 자금이동 없음·carry-forward·공통 시작일)
- ✅ **엔지니어링 고도화 ADR-5** (커밋 2b50208): 가상 스레드(+EventFeed ReentrantLock 핀닝 회피), Caffeine 캐시(stockPrice 1s/dailyChart 1h, 히트율 계측), 비동기 감사(@Async+@Transactional), JDBC 배치, Micrometer(`kiwoom.api.latency`·`order.submit.latency`), 공통 유틸(KiwoomNumbers·MarketConstants). 테스트 80건 통과
- ✅ **ADR-6 아키텍처 원칙 확정**: 외부 제안 검토 → docs/ARCHITECTURE.md 기준서 작성 (설계 규칙 20)
- 🔶 **WIP (컴파일·테스트 통과, 미완)**: 주문 영속화 1차(OrderStatus 4상태, V2__orders.sql), 게이트① 포트폴리오 재판정 테스트(RealDataPortfolioGateTest) — ADR-6 상태기계 확장(UNKNOWN 등)과 통합해 완성 예정

## 4. 다음 작업 (우선순위 순)

0. ✅ ~~아키텍처 정렬 사이클 (ADR-6 구현)~~ — 완료 (커밋 01e924f): 11상태 주문 상태기계(UNKNOWN 포함, 합법 전이표 강제), ClientOrderId 가독 포맷, BrokerPort/KiwoomBrokerAdapter(구 3개 서비스 흡수), Reconciliation·StaleOrderCanceller 골격. 브로커 필드명·취소 body는 `TODO 실측`
0-1. ✅ **C3 라이브 탑재** (커밋 c06db04): Momentum/Regime/VolTarget Math를 strategy 모듈 순수 클래스로 동형화(백테스트가 참조 — 기존 실험 테스트 무수정 통과로 수치 동일성 증명), `C3LiveStrategy`(09:05 KST 스케줄, 기본 비활성 `strategy.c3.enabled=false`), confidence=투입비중 사이징. **판단 주기 결정: 21일 유지, 5일 변형은 백테스트 재검증 대신 모의 운영 paper A/B로 비교**(trial 수 증가 방지)
0-2. ✅ **텔레그램 알림·원격 킬스위치·일일 리포트** (커밋 0b13a48): Notifier Port + TelegramNotifier(기본 비활성), /stop·/resume·/status 폴링 명령(chat_id 화이트리스트), KillSwitchChanged 이벤트, 15:50 일일 리포트. 실행 검증은 자택망
0-3. ✅ CI 수정 (커밋 53c963e): gradlew 실행 권한 비트 — Actions Permission denied 해결
0-4. ✅ **안전장치 잔여 완성** (커밋 7e0a3c4, 테스트 222건): 일 손실 한도(-2%) 실현손익 추적→킬스위치, WS 장시간 단절(180초)→MarketDataStale 이벤트→킬스위치, LIVE 잔고 연동 EquitySource(60초 캐시+2단 폴백), 거래 캘린더·장시간 가드
0-5. ✅ **특일 API 휴장일 DB 동기화** (커밋 7f37936·d05e4d7): 공공데이터 SpcdeInfoService `getRestDeInfo`(공식 명세 확인, `_type=json` 지원). **매년 11/1 내년 일괄 + 매월 15일 향후 30일 창 재동기화**(대체공휴일 늦은 확정 흡수 — 명세: 임시공휴일 1일 내, 대체공휴일 대통령령 시행 후 반영). DB 우선·하드코딩 폴백 판정, MANUAL 등록으로 연말휴장 보완. 기본 비활성(서비스키 `DATA_GO_KR_SERVICE_KEY` 발급 후 활성화)
0-6. ✅ **운영 상태기계 + CQRS Lite** (커밋 73b4184, 테스트 225건): TradingSystemStatus 6상태 전이표(STOPPED→STARTING→RUNNING→STOPPING, DEGRADED/ERROR), start/stop Command(`POST /api/trading/start|stop`, 응답은 STARTING — Backend가 Source of Truth), 킬스위치↔DEGRADED 자동 연동, View DTO+DashboardFacade(`GET /api/dashboard` 단일 폴링), FE 상태 배지·시작/정지 버튼·실현손익 표시
1. ✅ ~~게이트 ① 재판정~~ — 완료 (3절 게이트 현황 참조: C3 잠정 통과 → N=6 보수 검증에서 철회, C3 후보 동결)
1-1. ✅ **모듈 재편 완료** (커밋: "refactor: ADR-6 모듈 재편 — market/analysis/portfolio/trading/execution 목표 구조 정렬"): marketdata→market, newsintel→analysis, risk 내 PositionBook→portfolio 신설, execution→trading/execution 분리(trading: 주문 생성·상태 관리·대사 / execution: 브로커 전달만, 단방향 의존). ModularityTests 통과(순환 없음), 테스트 253건 무손상(실패 0, 개수 동일)
4. Phase 5: macro-intel 규칙 기반 필터 (수집 클라이언트는 예상 구현 + TODO 실측)
5. 텔레그램 알림/원격 킬스위치 (monitor)
6. **[자택망에서]** WS 실측(`scripts/ws_probe.py`), 주문 왕복 재검증, KiwoomSmokeIT 키 주입 실행

## 5. 보안 메모

- `.env`(로컬 전용, git 제외)에 모의투자 일반 계좌 키 보관. **채팅에 노출된 키이므로 실전 전환 전 전량 재발급 필수**
- GitHub 시크릿: `KIWOOM_MOCK_G_*`(모의 일반, CI 사용) / `KIWOOM_MOCK_P_*`(모의 선물, 예비) / `KIWOOM_APP_*`(실전, **CI 사용 금지**)
- 기본 프로필 `paper` 고정, `live` 프로필은 게이트 ② 통과 전 사용 금지

## 6. 주요 커밋 이력

| 커밋 | 내용 |
|---|---|
| 6f3e1d3 | 개발 계획 v4 (ADR 3건: Modulith 모놀리스, PG 이벤트스토어, 매매 코어 우선) |
| 5f2c40c | Phase 1 스캐폴딩 — 멀티모듈, 이벤트 계약, 키움 클라이언트(TR rate limit) |
| ca8df5b | Phase 2 코어 — 사이징/포지션북/일한도, SIM·LIVE 실행, WS 재연결 |
| 112b8a6 | 경량 대시보드 FE + 상세 주석 보강 |
| a4b2909 | Phase 3 백테스트 코어 — 비용 모델, 룩어헤드 방지, Sharpe/MDD/DSR |
| f965155 | Phase 4 — 변동성 돌파 v1(TradeIntent), walk-forward(DSR trial 연동) |
| b48d3c3 | 공개 일봉 실검증 — 야후 수집 스크립트 + 5종목 테스트 |
| e063b67 | 키움 모의투자 실측 반영 — 토큰/return_code/일봉 수집/스모크 IT |
| d18186c | CI + Gradle Wrapper |
| d5de321 | Phase 2 마무리 — 체결통보→Fill 파이프라인, ws_probe 스크립트 |
| 5e2aebf | 주문 API 실측 확정 — 매수/매도 왕복 검증, oso 키 고정 |
| 14c1ed6 | 전략 재설계 — 필터 돌파·시계열 모멘텀 3안 비교, 비용 민감도 |
