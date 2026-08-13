# autoStock 개발 계획 v4 (최종 조정)

키움증권 REST API 기반 국내주식 자동매매 시스템
작성: 2026-08-13 | 스택: Java 21 + Spring Boot 3.x (Spring Modulith) + PostgreSQL

---

## 0. 종합 분석 및 조정 결정

### 요구사항 변천

v1 단순 자동매매 → v2 검증 방법론 강화(이벤트 기반, DSR/PBO) → v3 MSA + 거시·뉴스 인텔리전스. 요구가 누적되며 다음 충돌이 발생, 아래와 같이 판단·조정한다.

### 충돌 지점과 결정 (ADR)

**ADR-1. 물리적 MSA vs 1인 개발 운영 부담**
8개 서비스 + Kafka를 처음부터 물리 분리하면 인프라 운영·디버깅 비용이 전략 개발을 압도하고, 장중 실패 지점만 늘어난다. 반면 서비스 경계 자체(특히 "주문은 risk 모듈 단일 관문")는 안전에 필수.
→ **결정: 논리적 MSA + 물리적 모놀리스.** Spring Modulith로 모듈 경계를 컴파일/테스트 수준에서 강제하고(모듈 간 직접 참조 금지, 이벤트로만 통신), 단일 JVM으로 배포. v3의 8개 서비스는 8개 Modulith 모듈로 유지. 물리 분리는 아래 트리거 충족 시점에 모듈 단위로 추출 — Modulith는 이벤트 외부화(Kafka)를 지원하므로 추출 비용이 낮다.

물리 분리 트리거: ① 뉴스 ML 스코어링 부하가 매매 루프 지연 유발 ② 모듈별 배포 주기 충돌 ③ 장중 가용성 요구가 다른 모듈의 재시작을 막을 때. 예외: **news-intel의 KR-FinBERT 서빙은 처음부터 Python FastAPI 사이드카** (언어 경계 = 자연스러운 물리 경계).

**ADR-2. Kafka 즉시 도입 vs 단순성**
백테스트=라이브 동형의 본질은 "이벤트 로그 리플레이"이지 Kafka 자체가 아니다.
→ **결정: PostgreSQL 이벤트 스토어(append-only)가 감사 추적 + 리플레이를 겸한다.** 모듈 간 통신은 Modulith 이벤트(트랜잭셔널 아웃박스 내장). Kafka는 물리 분리 시점에 이벤트 외부화로 도입. 이벤트 스키마 버전 관리는 지금부터 엄격히(`common` 모듈).

**ADR-3. 인텔리전스 우선순위 vs 매매 코어 검증**
거시·뉴스 계층은 가치가 있으나, 검증된 매매 루프 없이는 필터 기여도를 측정할 수 없다.
→ **결정: 매매 코어 루프(시세→시그널→리스크→주문→체결→감사)의 모의투자 검증이 최우선.** 인텔리전스는 2단계로: (a) 규칙 기반 최소형 필터(VIX·환율 임계치, DART 공시 종목 배제) 먼저, (b) KR-FinBERT 뉴스 감성은 필터 on/off 백테스트로 기여도 입증 후 도입.

**ADR-4. 유지 사항**
PostgreSQL(v2 결정), Java 21 + Spring Boot(사용자 스택), 과최적화 방지 게이트(DSR/PBO, walk-forward), 리스크 계층 명세, 정량 게이트는 그대로 유지. 시계열 볼륨 증가 시 TimescaleDB 확장 검토.

### 조정 후 총평

1인 개발 기준 실행 가능 범위로 축소하되, v3의 설계 의도(경계·계약·인텔리전스)는 모두 보존. 총 기간 약 13~16주(사이드 프로젝트 기준), 트레이딩 MVP는 7~8주차에 도달.

---

## 1. 목표와 성공 기준

**목표**: 검증된 전략을 이벤트 기반 엔진으로 자동 실행하고, 백테스트와 라이브가 동일 코드로 동작하는 시스템.

**정량적 게이트 (단계 통과 기준)**:

| 게이트 | 기준 |
|---|---|
| ① 백테스트 → 모의투자 | Walk-forward OOS 수익 양(+), DSR > 0.95 신뢰수준 통과, MDD < 15% |
| ② 모의투자 → 실계좌 소액 | 2주 이상 무인 운영, 백테스트 대비 슬리피지 격차 계측·허용범위 내, 치명 오류 0건 |
| ③ 소액 → 본운영 | 4주 이상 실계좌 성과가 모의와 통계적 유사, 킬스위치·복구 훈련 완료 |

## 2. 핵심 설계 원칙 (근거 자료 기반)

**(1) 이벤트 기반 아키텍처 — 백테스트 = 라이브 동형**
벡터화 백테스트는 룩어헤드 바이어스에 취약하고 라이브 코드와 이원화됨. 이벤트 기반 엔진은 시장데이터/시그널/주문/체결을 모두 이벤트로 처리해 백테스트와 실거래가 컴포넌트 교체만으로 전환됨 ([QuantStart](https://www.quantstart.com/articles/Event-Driven-Backtesting-with-Python-Part-I/), [IBKR Quant](https://www.interactivebrokers.com/campus/ibkr-quant-news/a-practical-breakdown-of-vector-based-vs-event-based-backtesting/)). 국내 사례: LEAN 기반 한국주식 툴킷 ([buylow](https://github.com/JeongSeongMok/buylow)).

**(2) 과최적화 방지 — 통계적 검증 내장**
소수 전략 구성만 반복 백테스트해도 높은 성과가 쉽게 나오며, 과최적화 전략은 OOS에서 체계적으로 언더퍼폼 (Bailey & López de Prado). 대응: walk-forward 기본 적용, [Deflated Sharpe Ratio](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2460551)로 다중검정·비정규성 보정, 전략 trial 횟수 DB 기록 → PBO 산출, 고도화 시 [CPCV](https://www.sciencedirect.com/science/article/abs/pii/S0950705124011110) 도입.

**(3) 리스크 우선 포지션 사이징**
고정비율(fixed fractional)로 시작 — 엣지 추정 불확실 초기에 Kelly보다 강건. 거래 50~100건 축적 후 fractional Kelly(1/2 이하) 전환 검토 ([QuantInsti](https://blog.quantinsti.com/position-sizing/)). 변동성 타게팅으로 변동성 상승 시 자동 축소.

**(4) 감사 추적 + 킬스위치는 필수 인프라**
2025~2026 규제 흐름: 자동 주문은 발원자 추적, 작동하는 킬스위치, 검색 가능한 감사 로그 요구 ([NURP](https://nurp.com/algorithmic-trading-blog/future-of-algorithmic-trading-trends-and-predictions/)). 전 주문 이벤트 소싱 기록, 텔레그램 원격 킬스위치.

## 3. 키움 REST API 실전 제약 (커뮤니티 검증 사항)

[알고랩 2026 가이드](https://algolab.co.kr/blog/kiwoom-rest-api-algotrading-guide-2026), [키움 공식 FAQ](https://openapi.kiwoom.com/assist/assist0202) 기준:

- TR(api-id)별 독립 rate limit — TR별 토큰버킷(약 1req/s, 버스트 2) + 429 백오프 필수
- 실전/모의 도메인 분리 — base URL 환경변수 토글, 모의 코드 실전 오적용 사고 다발
- api-id 헤더 오타 → 엉뚱한 TR 실행 위험 — TR 정의를 enum으로 중앙 관리
- WebSocket 단절 감지 실패 → 매매 정지 사고 — heartbeat 감시, 자동 재연결+재구독
- 장 시간 외 주문 → 무효 주문 누적 — 거래 캘린더/시간 가드
- 지원 범위: 국내주식(ETF/ETN 포함), Java 공식 지원 확인
- 참고 구현: [kiwoom-rest-api (207 엔드포인트+WS 19종)](https://github.com/younghwan91/kiwoom-rest-api)

## 4. 아키텍처 — 논리적 MSA, 물리적 모놀리스 (Spring Modulith)

```
┌─────────────────────── autostock 단일 JVM (Spring Modulith) ───────────────────────┐
│                                                                                    │
│  [수집]            [판단]           [안전]          [실행]                            │
│  market-data ──▶ strategy ──▶     risk      ──▶  execution                         │
│  macro-intel ──▶ (국면필터)        (사이징/한도/     (키움 주문,      ┌── monitor        │
│  news-intel* ──▶                  킬스위치)        체결 추적)       └── backtest       │
│                                                                                    │
│   모듈 간 통신: Modulith 이벤트만 허용 (직접 참조 금지, ArchUnit 테스트로 강제)              │
└──────────────────────────────┬─────────────────────────────────────────────────────┘
                               ▼
     PostgreSQL: 이벤트 스토어(append-only, 감사+백테스트 리플레이) + 도메인 스키마(모듈별 분리)
     * news-intel의 KR-FinBERT 스코어링: Python FastAPI 사이드카 (별도 컨테이너)
     횡단: TokenManager · Resilience4j(TR별 RateLimit) · 이벤트 스키마 버전 관리(common)
     물리 분리 시: Modulith 이벤트 외부화 → Kafka (트리거는 0절 ADR-1)
```

**모듈 경계와 책임** (= 미래 서비스 경계):

| 모듈 | 책임 | 발행 이벤트 |
|---|---|---|
| market-data | 키움 WS/REST 시세 수집·정규화, WS 재연결 | `MarketTick` |
| macro-intel | ECOS·FRED·DART 배치 수집, 국면 지표 산출 | `MacroIndicator` |
| news-intel | 뉴스 수집 → 사이드카 감성 스코어링 → 종목 매핑 | `NewsSentiment` |
| strategy | 시그널 생성 (시세 + 국면 필터 + 뉴스 필터) | `Signal` |
| risk | **주문 유일 관문**: 사이징, 한도, 킬스위치 | `OrderRequest` |
| execution | 키움 주문 API, 멱등성, 체결 추적 | `Fill` |
| backtest | 이벤트 스토어 리플레이, 비용 모델, walk-forward, DSR/PBO | 리포트 |
| monitor | 텔레그램 알림/원격 명령, 헬스체크, 성과 리포트 | — |

불변 원칙: **모든 주문은 risk 모듈을 통과. 모든 이벤트는 이벤트 스토어에 영속화. 전략 코드는 백테스트/모의/실전을 구분하지 않는다.**

**리포 구조**:

```
autoStock/
├── PLAN.md
├── build.gradle                  # 멀티모듈
├── common/                       # 이벤트 스키마(버전 관리 엄격), 공용 타입
├── app/                          # Spring Boot 진입점, Modulith 조립
│   └── src/main/java/com/autostock/
│       ├── marketdata/  ├── macrointel/  ├── newsintel/
│       ├── strategy/    ├── risk/        ├── execution/
│       ├── backtest/    └── monitor/
├── sidecar-nlp/                  # Python FastAPI + KR-FinBERT
├── infra/                        # docker-compose.yml (app, sidecar, PostgreSQL)
└── docs/
```

## 5. 거시·뉴스 인텔리전스 계층

역할: **국면 필터**(위험 국면에서 축소/진입 금지) + **이벤트 가드**(전쟁·정책 급변 방어). 2단계 도입(ADR-3).

**1단계 — 규칙 기반 (Phase 5)**:

| 소스 | 데이터 | 활용 규칙 |
|---|---|---|
| [ECOS API](https://github.com/WooilJeong/PublicDataReader/blob/main/assets/docs/ecos/ecos.md) | 기준금리, CPI, 환율 | 환율 급등·금리 서프라이즈 시 포지션 상한 하향 |
| [FRED API](https://wikidocs.net/366487) | VIX, 달러인덱스, 유가 | VIX 임계 초과 시 신규 진입 금지 |
| [DART OpenAPI](https://huggingface.co/datasets/eddmpython/dartlab-data) | 공시, 지분 변동 | 유상증자·소송 등 발생 종목 진입 배제 |
| 키움 REST | 선물 베이시스, 외국인 수급 | 수급 악화 시 보수 모드 |

**2단계 — 뉴스 감성 (Phase 7)**: 뉴스 수집(RSS/주요 언론) → 종목·섹터 매핑 → KR-FinBERT 사이드카 스코어링 → `NewsSentiment`. 국내 실증: [KOSPI 예측](https://www.dbpia.co.kr/journal/articleDetail?nodeId=NODE11227781), [공모주 시초가 예측](https://www.kci.go.kr/kciportal/landing/article.kci?arti_id=ART002932250), [감성-주가 딥러닝](https://www.kci.go.kr/kciportal/ci/sereArticleSearch/ciSereArtiView.kci?sereArticleSearchBean.artiId=ART002869886). 지정학 리스크는 뉴스 스코어로 자체 산출, 임계 초과 시 킬스위치 후보 → 텔레그램 승인 요청.

원칙: 감성·거시 시그널은 단독 매매 근거가 아닌 **필터/가중치**. 도입 전후 필터 on/off 백테스트로 기여도 입증 (DSR/PBO 검증 동일 적용).

## 6. 데이터 계층

| 데이터 | 수집 | 저장 |
|---|---|---|
| 일봉/분봉 | REST 배치 (rate limit 내 야간 수집) | PostgreSQL 파티셔닝(종목/월) |
| 실시간 체결/호가 | WebSocket 구독 | 핫패스 메모리, 비동기 배치 flush |
| 전 이벤트 (주문/체결/시그널) | 이벤트 스토어 append-only | 감사 + 백테스트 리플레이 원천 |
| 전략 trial 이력 | 백테스트 실행마다 기록 | PBO/DSR 계산 원천 |
| 거시/공시/뉴스 | 배치 + 사이드카 | 모듈별 스키마 |

비용 모델(위탁수수료 + 증권거래세 + 슬리피지 추정)을 SimExecution에 반영 — 단기 전략은 비용 모델 정확도가 성패 결정.

## 7. 전략 연구 프로세스

후보 (국내 실증 문헌 기반): 변동성 돌파 ([한국콘텐츠학회](https://koreascience.kr/article/JAKO202211258153666.pub?lang=ko&orgId=kocon)), 저변동성/변동성 조정 (KOSPI +3.23%p·KOSDAQ +15.70%p 실증, [서울대](https://www.dbpia.co.kr/journal/detail?nodeId=T15120779)), 모멘텀 ([숙명여대](https://scholarworks.sookmyung.ac.kr/item/0f2d4e15-f730-47ad-add3-23536003d26f)).

```
가설 → 인샘플 백테스트 → walk-forward OOS → DSR/PBO 검증
→ 모의투자 (슬리피지 실측) → 실계좌 소액 → 본운영
   ↑ 게이트 미달 시 폐기/재설계, trial 기록 보존
```

ML 확장(선택): gradient boosting 시그널 필터부터 — [ML4T 워크플로](https://stefan-jansen.github.io/machine-learning-for-trading/08_ml4t_workflow/01_multiple_testing/) 참고.

## 8. 리스크 관리 명세

| 계층 | 규칙 (초기값, config 관리) |
|---|---|
| 주문 단위 | 종목당 최대 계좌의 10%, 1회 주문 상한, 호가 검증 |
| 포지션 | 동시 보유 최대 5종목, 손절 -3% / 익절 +5% (전략별 오버라이드) |
| 일간 | 일 최대 손실 -2% 도달 시 신규 진입 차단, 일 주문 횟수 상한 |
| 국면 | VIX·환율 임계 초과 시 보수 모드 (5절 규칙) |
| 시스템 | 킬스위치(텔레그램 + 자동 트리거), 15:15 전량 청산 옵션 |
| 이상 감지 | 멱등키 중복 방지, 미체결 타임아웃 취소, WS 단절 시 진입 금지 모드 |

## 9. 운영 설계

- **스케줄**: 08:00 토큰 갱신·정합성 점검 → 08:50 WS 연결·구독 → 09:00 가동 → 15:20 정리 → 장후 성과 리포트(텔레그램) → 야간 배치(일봉·거시 수집, DB 정리)
- **복구**: 재시작 시 미체결/잔고 REST 재조회로 상태 복원, 이벤트 스토어와 대사
- **관측성**: Actuator + 메트릭(주문 지연, WS 단절, 슬리피지), 임계 초과 알림
- **배포**: Docker Compose (app + sidecar-nlp + PostgreSQL), 실전 전환은 환경변수만 변경

## 10. 로드맵 (v4 조정)

| Phase | 기간 | 산출물 | 완료 기준 |
|---|---|---|---|
| 0. 준비 | 3일 | 키움 REST API·모의투자 신청, ECOS/FRED/DART 키 발급 | 키 확보 |
| 1. 기반 | 2주 | Modulith 멀티모듈 스캐폴딩, common 이벤트 스키마, 이벤트 스토어, Flyway, 키움 클라이언트(토큰/rate limit) | 모의계좌 조회, 이벤트 저장·리플레이 왕복 |
| 2. 매매 코어 | 3주 | market-data(WS), risk 골격, execution(주문·멱등성) | 모의투자: 수동 시그널 → risk → 주문 → 체결 이벤트 왕복 |
| 3. 백테스트 | 2~3주 | 리플레이 러너, 비용 모델, walk-forward, DSR/PBO | 동일 전략이 백테스트/모의 동일 코드 구동 |
| 4. 전략+리스크 | 3주 | 변동성 돌파 v1, 리스크 전 계층, 킬스위치, monitor | **게이트 ① 통과 = 트레이딩 MVP** |
| 5. 거시 필터 | 2주 | macro-intel (ECOS/FRED/DART 규칙 기반) | 필터 on/off 백테스트로 기여도 검증 |
| 6. 운영 검증 | 2주+ | 무인 운영, 슬리피지 계측, 복구 훈련 | 게이트 ② 통과 → 실계좌 소액 |
| 7. 확장 | 지속 | news-intel(KR-FinBERT 사이드카), fractional Kelly, CPCV, 물리 분리 트리거 평가 | 게이트 ③ 통과 후 본운영 |

## 11. 참고 문헌·자료

**논문/학술**
- Bailey & López de Prado, *The Deflated Sharpe Ratio* (2014) — [SSRN](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2460551)
- Bailey et al., *Statistical Overfitting and Backtest Performance* — [LBL](https://sdm.lbl.gov/oapapers/ssrn-id2507040-bailey.pdf)
- *Backtest overfitting in the ML era: CPCV 비교* (2024) — [ScienceDirect](https://www.sciencedirect.com/science/article/abs/pii/S0950705124011110)
- *Walk-Forward Validation Framework for Microstructure Signals* (2025) — [arXiv](https://arxiv.org/pdf/2512.12924)
- KR-FinBERT 뉴스 감성분석 KOSPI 예측 — [DBpia](https://www.dbpia.co.kr/journal/articleDetail?nodeId=NODE11227781)
- 뉴스 감성 딥러닝 주가 예측 — [KCI](https://www.kci.go.kr/kciportal/ci/sereArticleSearch/ciSereArtiView.kci?sereArticleSearchBean.artiId=ART002869886)
- 뉴스·댓글 기반 공모주 시초가 예측 — [KCI](https://www.kci.go.kr/kciportal/landing/article.kci?arti_id=ART002932250)
- 신다슬, 변동성 조정 투자전략 — [DBpia](https://www.dbpia.co.kr/journal/detail?nodeId=T15120779)
- 심층신경망 변동성 돌파 전략 — [KoreaScience](https://koreascience.kr/article/JAKO202211258153666.pub?lang=ko&orgId=kocon)
- 융합적 모멘텀 전략 — [ScholarWorks](https://scholarworks.sookmyung.ac.kr/item/0f2d4e15-f730-47ad-add3-23536003d26f)

**커뮤니티/실무**
- 키움 REST API 자동매매 가이드 2026 — [알고랩](https://algolab.co.kr/blog/kiwoom-rest-api-algotrading-guide-2026)
- 키움 공식 문서·FAQ — [openapi.kiwoom.com](https://openapi.kiwoom.com/)
- kiwoom-rest-api 래퍼 — [GitHub](https://github.com/younghwan91/kiwoom-rest-api)
- buylow (LEAN 기반 한국주식 툴킷) — [GitHub](https://github.com/JeongSeongMok/buylow)
- Event-Driven Backtesting — [QuantStart](https://www.quantstart.com/articles/Event-Driven-Backtesting-with-Python-Part-I/), [IBKR](https://www.interactivebrokers.com/campus/ibkr-quant-news/a-practical-breakdown-of-vector-based-vs-event-based-backtesting/)
- Position Sizing — [QuantInsti](https://blog.quantinsti.com/position-sizing/), [QuantifiedStrategies](https://www.quantifiedstrategies.com/position-sizing-strategies/)
- ECOS/FRED 수집 가이드 — [wikidocs](https://wikidocs.net/366487), [PublicDataReader](https://github.com/WooilJeong/PublicDataReader/blob/main/assets/docs/ecos/ecos.md)

**강의/서적**
- Stefan Jansen, *Machine Learning for Trading* — [ml4t](https://stefan-jansen.github.io/machine-learning-for-trading/08_ml4t_workflow/01_multiple_testing/) (번역: 퀀트 투자를 위한 머신러닝·딥러닝 알고리듬 트레이딩 2/e, 에이콘)
- 러닝스푼즈 퀀트 머신러닝 — [learningspoons](https://learningspoons.com/course/detail/quantml/)
- 인프런 트레이딩 봇 개발 — [inflearn](https://www.inflearn.com/course/%EB%B9%84%ED%8A%B8%EC%BD%94%EC%9D%B8-%ED%8A%B8%EB%A0%88%EC%9D%B4%EB%94%A9-%EB%B4%87)
