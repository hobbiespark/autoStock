# autoStock 개발 계획 v5

키움증권 REST API 기반 국내주식 자동매매 시스템
작성: 2026-08-13 | v4.1: 2026-08-28 (근거 문헌 1차 자료 교체, CPCV 병행, 2026 세율) | **v5: 2026-09-11 (요구 확장 ADR-7~10: 단기 익절/손절 규칙, ETF 롱·숏, 공모주 반자동, FE React 전환 — 0-2절)** | 스택: Java 21 + Spring Boot 3.x (Spring Modulith) + PostgreSQL
진행 현황: [PROGRESS.md](PROGRESS.md) 참조 (Phase별 체크·실측 기록·전략 실험 결과)

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

**ADR-5. 엔지니어링 고도화 (2026-08-13 추가, 조사 기반 결정)**
- **스레드**: Java 21 가상 스레드 활성화(`spring.threads.virtual.enabled=true`) — 블로킹 I/O(키움 REST `.block()`) 중심 워크로드에 적합. JDK 21의 synchronized 핀닝 이슈 대응으로 핫패스의 `synchronized`는 `ReentrantLock`으로 교체. **핀닝은 [JEP 491](https://openjdk.org/jeps/491)로 JDK 24에서 해결됨** — LTS인 JDK 25 업그레이드 시 이 제약 소멸(업그레이드 검토 항목). 운영 관찰 전까지 되돌릴 수 있는 플래그로 유지
- **캐시**: Redis가 아닌 **Caffeine 로컬 캐시** — 단일 JVM 모놀리스(ADR-1)에서 Redis는 네트워크 홉·운영 부담만 추가. `recordStats()`로 히트율을 Micrometer에 노출(목표 >90%), 물리 분리 시점에 Caffeine(L1)+Redis(L2) 계층화로 확장 ([멀티레벨 캐시 패턴](https://blog.devops-monk.com/2026/05/spring-boot-caching-caffeine-redis/)). 대상: 현재가(TTL 1s)·일봉(TTL 1h)·토큰(자체 캐시 유지)
- **트랜잭션/CRUD**: 이벤트 스토어 append는 `@Transactional` 경계 명시 + Hibernate JDBC 배치(`batch_size`) 활성화. 감사 기록은 `@Async`(가상 스레드)로 핫패스에서 분리 — 매매 경로 지연에 영향 금지
- **성능 계측**: Micrometer `Timer`/`@Timed`로 키움 API 지연·주문 라운드트립·이벤트 처리 시간 계측, Actuator `/actuator/metrics` 노출. 백테스트 처리량은 러너 자체 계측. JMH는 필요 시점까지 보류(개인 프로젝트 과잉)
- **공통화**: `common`에 상수(`MarketConstants`: KST, 장 시간)·유틸(`KiwoomNumbers`: 부호 정규화) 집약, kiwoom/marketdata 중복 파싱 제거

**ADR-6. 아키텍처 원칙 확정 (2026-08-13, 외부 제안 검토 후)**
외부 아키텍처 제안(DDD Lite·Hexagonal·State Machine 등)을 현재 구현과 대조 분석해 채택/조정/기각 확정. 상세 기준서: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
- **채택**: Value Object(ClientOrderId·StockCode·Quantity 등, 주문 안전 관련 우선), Port/Adapter(BrokerPort·SentimentAnalyzer — 현행 kiwoom 클라이언트에서 Port 추출), 주문 상태기계 확장(**SUBMITTING/UNKNOWN 포함** — 타임아웃은 실패가 아니라 UNKNOWN→Reconciliation), ClientOrderId 가독 포맷+DB UNIQUE, Reconciliation 일급 기능(Broker=Source of Truth), CQRS Lite(View DTO), TradingSystemStatus 상태기계, Functional Core 명문화
- **점진 재편**: 모듈 재구성(marketdata→market, newsintel→analysis, execution→trading/execution 분리, portfolio 신설)은 별도 사이클에서 일괄 — 진행 중 작업 보호
- **조정**: FE는 현 단계 vanilla 유지(4-1절), 화면 3개 초과 시 React+TS+TanStack Query
- **기각 유지**: Event Sourcing(현행 event_store는 감사·리플레이 로그이지 ES 아님), Full CQRS, Saga
- **기존 결정과 일치 확인**: "Strategy는 주문하지 않는다"(RiskGate 단일 관문), 백테스트=라이브 동형, 주문 무조건 재시도 금지, Modulith 경계 테스트

**ADR-4. 유지 사항 (2026-08-28 갱신)**
PostgreSQL(v2 결정), Java 21 + Spring Boot(사용자 스택), 과최적화 방지 게이트(DSR/PBO, walk-forward — CPCV 병행 추가는 2절 (2)), 리스크 계층 명세, 정량 게이트는 그대로 유지. 시계열 확장은 **PostgreSQL [네이티브 선언적 파티셔닝](https://www.postgresql.org/docs/current/ddl-partitioning.html) 우선** — 개인 규모(일봉+분봉)에 충분하고 확장 의존성이 없다. TimescaleDB(개발사 Timescale→TigerData로 사명 변경, 2025-06)는 [Apache 2.0/TSL 이중 라이선스](https://www.tigerdata.com/legal/licenses)로 자가 호스팅 무료이나, 틱 단위 대량 수집 전환 시점에만 재검토. Spring Modulith는 1.4.x(Boot 3.5/Java 21 라인) 추종 — [2.0 GA(2025-11)는 Boot 4 기준선](https://spring.io/blog/2025/11/21/spring-modulith-2-0-ga-1-4-5-and-1-3-11-released/)이므로 Boot 4 전환 시 함께 이관. 이벤트 외부화(Kafka)는 [공식 문서](https://docs.spring.io/spring-modulith/reference/events.html)의 `spring-modulith-events-kafka` + `@Externalized` 경로 확정.

### 0-2. 요구 확장 ADR (v5, 2026-09-11) — 조사 기반 수용·재해석·설계

**ADR-7. "일반거래: 1일 최소 +1.8% / −1.5% (수수료 제외)" — 목표가 아닌 트레이드 규칙으로 재해석**

"매일 최소 +1.8%"를 **수익 보장 목표**로 받으면 연환산 수천%가 되어 어떤 검증도 통과할 수 없고, 실증도 정반대다: 대만 전체 데이트레이더 전수 연구(Barber, Lee, Liu & Odean)에서 반기 기준 **8할이 손실**, 지속적으로 수익을 내는 비율은 약 5% 미만 ([JFM 2014](https://www.sciencedirect.com/science/article/abs/pii/S1386418113000190), [원문 PDF](https://faculty.haas.berkeley.edu/odean/papers/day%20traders/The%20Cross-Section%20of%20Speculator%20Skill.pdf)). 본 시스템의 자체 실험 1·2에서도 고빈도 매매는 비용 드래그로 붕괴했다(PROGRESS 3절).

→ **결정: 트레이드 단위 익절 +1.8% / 손절 −1.5% 리스크 규칙으로 수용** (현행 config 손절 −3%/익절 +5%의 전략별 오버라이드로 구현 — 8절 리스크 명세 호환).
- **수학적 전제조건 명시**: 수수료 제외 기준이므로 총 익절 임계는 약 +2.03%(왕복 비용 0.23%). 손익비 1.2에서 EV>0 조건은 `p×1.8 − (1−p)×1.5 − 0.23 > 0` → **승률 p > 52.4%** 필요. 이 승률이 paper 운영에서 통계적으로 입증되기 전까지 이 규칙 기반 전략의 라이브 전환 금지(게이트 ① 동일 적용)
- 손절 규칙의 근거와 한계: [Kaminski & Lo (2014, J. Financial Markets)](https://www.sciencedirect.com/science/article/abs/pii/S138641811300030X) — 수익률이 랜덤워크면 손절은 기대수익을 오히려 낮추고, **모멘텀·국면전환이 있을 때만 개선**. 따라서 이 규칙은 단독 전략이 아니라 기존 국면필터·모멘텀 계열 위에 얹는다
- 검증 경로: 백테스트 trial 기록 → 모의 paper A/B (기존 C3 대비) — 반복 튜닝 금지 원칙 유지

**ADR-8. ETF 롱·숏 — 인버스 ETF 매수로 숏 노출 구현**

개인의 현물 공매도는 제도상 제한적이므로 숏은 **인버스 ETF 매수**로 구현한다(매수 자체는 일반 주문과 동일, 제도 제약 없음. 단 레버리지·인버스 ETP는 **금융투자교육원 사전교육 이수 + 기본예탁금** 요건(2020~) — 계좌 요건 사전 확인 필요).
- 설계: 국면필터가 OFF(하락 국면)일 때 현금 대기 대신 KODEX 인버스(114800) 진입을 허용하는 **C4 가설(국면 롱/숏)** — 기존 C 계열의 자연 확장. trial 기록·CPCV/DSR 게이트 동일 적용
- **안전 규칙**: ① 2X(252670 등) 금지, 1X만 — 일일 리밸런싱 복리 괴리(변동성 끌림)는 배수의 제곱에 비례 ([Cheng & Madhavan 2009, J. Investment Management](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=1539120)) ② 보유기간 상한(초기값 20거래일, config) — 횡보 구간 음의 복리 방어 ③ 백테스트에 인버스 총보수·추적오차 반영. 국내 근거: [금감원 소비자경보(2026-03)](https://eiec.kdi.re.kr/policy/materialView.do?num=278124) — 레버리지·인버스 거래대금 급증(일평균 5.6조)과 "지수 횡보에도 원금이 녹는 음의 복리" 경고
- 데이터: 114800 일봉을 백테스트 데이터셋에 추가 수집

**ADR-9. 공모주 청약 — 전량 자동화 불가 확정, 반자동 파이프라인으로 설계**

**실측 확정 (2026-09-11)**: 키움 REST API 공식 GitHub([Kiwoom-Securities/Kiwoom-REST-API](https://github.com/Kiwoom-Securities/Kiwoom-REST-API)) 전수 확인 결과 **공모주/청약 TR이 존재하지 않음** — 청약 실행은 영웅문S#/HTS 수동만 가능. 따라서 "청약 기능"은 판단·알림 자동화 + 실행 수동의 반자동으로 설계한다.

| 단계 | 자동화 | 내용 |
|---|---|---|
| ① 일정 수집 | 자동 (배치) | DART/KIND 공모 일정 수집 → ipo 캘린더 테이블 |
| ② 필터 판단 | 자동 | 기관경쟁률·의무보유확약비율 임계 필터 — 청약경쟁률→상장일 수익률 정(+) 관계 실증 ([KCI](https://www.kci.go.kr/kciportal/ci/sereArticleSearch/ciSereArtiView.kci?sereArticleSearchBean.artiId=ART001546569), [2023 제도 이후 분석](https://www.kci.go.kr/kciportal/ci/sereArticleSearch/ciSereArtiView.kci?sereArticleSearchBean.artiId=ART003117548)) |
| ③ 알림 | 자동 | 텔레그램: 청약 권고/비권고 + 근거 지표 + 청약 기간·증거금 |
| ④ 청약 실행 | **수동** | API 미지원 — 영웅문S#에서 직접 |
| ⑤ 상장일 매도 | 반자동 | 배정 수량 수동 입력 → 상장일 시초 매도 규칙(기본) 알림·기록 |

- **기대치 명시 (과대포장 금지)**: 2021~ 균등배정(일반물량 50%+, [중복청약 금지](https://www.fsc.go.kr/no010101/76078))에서 인기 딜은 계좌당 0~1주 추첨(예: 에이피알 균등 0.06주). 상장일 가격제한 60~400%(2023-06~). 시초 수익률은 2024 상반기 평균 +124% → 2025 상반기 +64.9%로 둔화·변동 큼 ([유진투자증권 IPO 리포트](https://www.eugenefn.com/common/files/amail/20250707_B90_jongsun.park_2402.pdf)). **성격: 계좌당 소액의 저위험 플러스 — 주 수익원이 아니라 부가 기능**
- Phase 7의 공모주 시초가 예측 연구(기존 인용, KCI)와 결합해 ② 필터 고도화 가능

**ADR-10. FE React+TypeScript 전환 (사용자 결정 2026-09-11)**

ADR-6의 "vanilla 유지, 화면 3개 초과 시 전환" 기준을 사용자 결정으로 앞당긴다 — 공모주 캘린더(ADR-9)·운영 관측 강화로 화면 증가가 확정되어 전환 조건이 사실상 충족됨. Vite + React + TS + TanStack Query(2초 폴링 대체), `frontend/` 디렉터리 신설, 빌드 산출물을 monitor 정적 서빙으로 배포. **전역 상태 라이브러리는 여전히 미도입**(ADR-6 유지). 범위: 기존 6카드 + 슬리피지·시스템 설정 카드 + 이벤트 피드 필터·일시정지 + 모바일 반응형.

**확장 확정 (2026-09-11, "화면·기능 더 많이" 요구)** — HTMX 회귀 검토는 기각(다화면·클라이언트 상호작용 확대가 React 선택의 정당화 그 자체). **FE 재전환은 요구사항의 근본 변화 없이는 금지**(churn 봉인). 화면 로드맵:

| # | 화면 | 백엔드 필요 작업 | 시점 |
|---|---|---|---|
| FE-1 | ✅ 주문·체결 이력 (상태 뱃지, ClientOrderId, 기간 필터) | `GET /api/orders` (커밋 adf9b3e) | 완료 |
| FE-2 | ✅ 성과 추이 (일별 실현손익·누적, 슬리피지 추이) | `daily_performance`(V4) + 15:50 스냅샷 + `GET /api/performance/daily` (커밋 adf9b3e) | 완료 |
| FE-6 | **종목 선정 이유 (지평별)** — "오늘 왜 이 종목을 사고/안 샀나": 지평 탭(단타/스윙/중기/장기), 종목별 판단 근거(모멘텀 N일 수익률·국면 ON/OFF·볼타겟 산출 비중·리스크 게이트 통과/거부 사유·최종 결론), 날짜 조회 | **판단 스냅샷 영속화**: `signal_decisions` 테이블(V5 — 판단시각·지평·전략·종목·지표값 JSON·결론·사유) + C3LiveStrategy 09:05 판단 시 저장 + RiskGate 거부 사유 기록 + `GET /api/decisions?date=` | **다음 증분** (중기 C3부터 — 다른 지평은 전략 추가 시 자동 편입) |
| FE-3 | **공모주 정보** — 청약 캘린더(D-day), 딜별 상세(공모가 밴드·기관경쟁률·의무보유확약률·주관사), 청약 권고/비권고 + 근거(ADR-9 ② 필터 지표), 내 청약·배정·상장일 매도 기록 | ADR-9 E2 백엔드 (ipo 테이블·DART 수집·필터) | DART 키 후 |
| FE-4 | 리스크 설정 조회 (한도·임계치 읽기 전용 — 수정은 config 재시작 원칙 유지) | RiskProperties View | 후순위 |
| FE-5 | 감사 이벤트 탐색 (이벤트 스토어 검색) | event_store 조회 API | 후순위 |

도출 원칙: 화면은 계획·전략 문서에서 나온다 — FE-6은 ADR-11(지평 프레임)·ARCHITECTURE "Strategy는 Signal까지만"(판단 근거가 곧 감사 대상)에서, FE-3은 ADR-9 반자동 파이프라인에서 도출. **판단 근거 저장은 화면 이전에 감사 요구사항이기도 하다** — "왜 샀는지"를 재구성할 수 있어야 한다는 2절 (4) 원칙의 구현.

내비게이션: 경량 탭 라우팅(라우터 라이브러리 도입 시 react-router 표준, 화면 3개 시점). 차트는 recharts. 조회 전용 화면은 CQRS Lite View DTO 원칙(ARCHITECTURE 10절) 그대로 — FE가 도메인 엔티티를 직접 받지 않는다.

**ADR-11. 보유기간 지평별 전략 프레임워크 (2026-09-11 요구: 단타·스윙·중기·장기 각각 필요)**

"각 지평마다 전략 1개"를 만들되, 지평마다 **작동 원리(엣지)와 비용 구조가 다르다**는 사실을 프레임에 고정한다. 왕복 비용 0.23%+슬리피지는 보유기간이 짧을수록 지배적이 된다 — 지평별 요구 엣지가 다른 이유.

| 지평 | 보유기간 | 채택 접근 (고인용 원전) | 비용 민감도 | 상태·우선순위 |
|---|---|---|---|---|
| **장기** | 수개월~1년+ | 자산 로테이션: 듀얼 모멘텀 ([Antonacci, SSRN 2042750](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2042750) — 절대+상대 모멘텀 결합이 MDD 최소) + 10개월 SMA 타이밍 ([Faber GTAA](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=962461)) + 저변동성(국내 실증 기인용). 유니버스: KODEX200·섹터 ETF·현금·인버스1X(ADR-8) | 최소 (연 수회) | **1순위** — 재설계 사이클(D0) 주 후보. 목표변동성 10~12%로 MDD<15% 게이트와 정합 |
| **중기** | 수주~수개월 | 현행 C 계열: TSM ([Moskowitz 2012](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2089463)) + 국면필터 + 볼타겟. 크로스섹션 모멘텀 ([Jegadeesh & Titman 1993, JF](https://onlinelibrary.wiley.com/doi/abs/10.1111/j.1540-6261.1993.tb04702.x) — 3~12개월 승자 지속) 확장 가능 | 낮음 (연 7~30회) | **2순위** — C3 동결(모의 검증 연장 중), C4(인버스 확장)는 게이트 대상 |
| **스윙** | 2일~2주 | 이벤트 드리븐: 뉴스 감성(KR-FinBert-SC, Phase 7)·공모주 상장일(ADR-9). 단기 반전 ([Jegadeesh 1990, JF](https://onlinelibrary.wiley.com/doi/abs/10.1111/j.1540-6261.1990.tb05110.x) — 월 ~2% 반전 프리미엄)은 **거래비용에 극도로 민감**해 개인 비용 구조에서 소멸 위험 — 필터 결합 시에만 | 중간 | **3순위** — Phase 7 감성 필터 기여도 입증과 결합 |
| **단타** | 분~1일 | ADR-7 규칙(익절 +1.8/손절 −1.5) + 국면·모멘텀 조건부 진입. 근거가 가장 약한 지평: 자체 실험 A/B 붕괴 + 대만 전수(<5% 지속 수익) | **지배적** (일 목표 대비 비용 11%+) | **최후순위** — 기본 비활성, paper 전용, 승률>52.4% 입증 전 라이브 금지 |

- **공통 규율**: 4개 지평 모두 동일 게이트(walk-forward+CPCV, DSR, MDD)·trial 기록·paper 검증을 거친다. 지평이 다르다고 검증이 느슨해지지 않는다
- **자본 슬리브 (초기 제안, config)**: 장기 코어 50% / 중기 30% / 스윙 15% / 단타 5% — 게이트 통과한 지평에만 실자본 배분, 미통과 지평의 슬리브는 현금 유지
- **구현 정합**: 전략은 이미 Strategy 인터페이스+슬리브 포트폴리오 러너로 병렬 실행 가능 — 지평별 전략은 별도 모듈이 아니라 strategy 모듈 내 구현체 추가

**ADR-12. 게이트 v2 (2026-09-11 제안 → ✅ 동일자 사용자 승인·채택 확정)**

**확정 게이트 ① (v2)**: ⓐ OOS 수익 양(+) ⓑ **Hansen SPA_c p < 0.05** (벤치마크 KODEX200 B&H, 선택풀 기준·전체풀 병기 — DSR·PBO는 참고 병기) ⓒ **부트스트랩 95퍼센타일 MDD < 지평 예산** (장기·중기 25%, 스윙 15%, 단타 10%) ⓓ 슬리브 배분(50/30/15/5) 환산 총계좌 MDD < 15%. 게이트 ②·③은 기존 유지.
채택 근거: F1·F2 병기 수치로 **v2를 적용해도 기존 10계열 판정이 전부 동일(미통과)** 확인 — 기존 결과 구제가 아니므로 데이터 스누핑에 해당하지 않음. A/B 포함이 SPA p에 무영향(실측)이라 N 풀 논쟁도 소멸.

**F4 재판정 (v2 적용, 기존 산출 수치 재계산만 — 2026-09-11)**: 전 계열 FAIL 유지. 대표 — C3: SPA 해당 없음(최선 C0의 p=0.193이 이미 >0.05), 부트스트랩 p95 MDD 38.2% > 25%. D2: p95 32.7% > 25%. **결론: v2에서도 실계좌 후보 없음. C3 모의 운영(검증 연장)·새 가설 연구만 유효.**

<details>원 제안 내용 (채택 전 기록):</details>

배경: 10계열(A,B,C0~C4,D0~D2) 전부 게이트 ① FAIL. 결과를 본 후의 기준 완화는 데이터 스누핑이므로, 보정은 방법론 근거를 갖춘 명시적 ADR로만 한다. 제안 내용:

1. **다중검정 판정을 DSR 단독 → Hansen SPA 검정 병행으로**: [White (2000) Reality Check](https://onlinelibrary.wiley.com/doi/abs/10.1111/1468-0262.00152) (Econometrica, 인용 1,900+) → [Hansen (2005) SPA](https://www.tandfonline.com/doi/abs/10.1198/073500105000000063) (JBES, 인용 1,200+)가 데이터 스누핑의 학계 표준 검정. SPA는 스튜던트화 + 표본 의존 널분포로 **열등 전략이 풀에 섞여도 검정력이 훼손되지 않아** "N 풀 정의 논쟁"(A/B 포함 여부)을 구조적으로 해소. DSR은 병기 지표로 유지(보완 관계 — DSR은 해석적 보정, SPA는 실제 성과 시계열의 부트스트랩 검정)
2. **MDD 점추정 → 부트스트랩 분포 판정**: [Politis & Romano (1994) Stationary Bootstrap](https://www.tandfonline.com/doi/abs/10.1080/01621459.1994.10476870) (JASA, 인용 2,500+)로 수익률 경로 리샘플 → **95퍼센타일 MDD**로 판정(단일 경로 점추정의 운 요소 제거). MDD 예산은 총계좌 15% 유지 + 지평 슬리브별 차등(장기/중기 25%, 스윙 15%, 단타 10% — 자본 배분 50/30/15/5 반영 시 계좌 기여도 15% 내 설계)
3. **참고 재계산 (기존 결과, 새 백테스트 없음, 커밋 2b0f407)**: 선택풀 N=8(수익 양수 계열만) DSR — C3 0.957(>0.95), C4 0.932, D1 0.926, D2 0.902. **보정안을 적용해도 C3는 "경계선"이지 자동 통과가 아니다** — MDD 25.0%는 슬리브 예산 25%의 정확히 경계, SPA 검정은 미실시

채택 절차: 사용자 승인 → 게이트 v2 확정 → 전 계열 1회 재판정(재계산만, 신규 튜닝 금지).

**ADR-13. 검증 인프라 확장 (확정 — 측정 도구 추가는 기준 변경이 아니므로 즉시 채택)**

| 도구 | 원전 | 용도 | 난이도 | 결정 |
|---|---|---|---|---|
| Stationary Bootstrap 엔진 | Politis & Romano 1994 (JASA) | MDD/성과 분포, SPA 널분포의 공통 기반 | 하 | **도입 (트랙 F1)** |
| Hansen SPA 검정 | Hansen 2005 (JBES) | 데이터 스누핑 공식 검정 — F1 위에 증분 구현 | 중 | **도입 (트랙 F2)** |
| MC Permutation Test | Masters 2018 (실무 서적) | 가격 셔플 재실행 우연성 검정 — peer-review 원전 약함 | 하~중 | 참고 기법 (선택) |
| HMM 국면 필터 | [Hamilton 1989](https://www.semanticscholar.org/paper/de6046f58a05a769b5aa526d95a09c5fa5e5b42c) (Econometrica, 인용 9,900+) | SMA200 대체 후보 — 단 [Blanchard 2025](https://onlinelibrary.wiley.com/doi/10.1002/asmb.70058): 평활 입력 없인 불안정, 우위 미보장 | 중 | 조건부 A/B 실험만 (기대 낮춤, 게이트로 심판) |
| HRP 배분 | [López de Prado 2016](https://jpm.pm-research.com/content/42/4/59.short) (JPM) | 슬리브 배분 고도화 | 중 | 후순위 (다전략 운용 시) |
| Meta-labeling | AFML 3장 | 신호 필터 ML | 상 | 후순위 (N 증가 자기모순 관리 필요) |
| 합성 데이터 (GAN/diffusion) | arXiv 2024~2026, [CFA 보고서 2025](https://rpc.cfainstitute.org/sites/default/files/docs/research-reports/tait_syntheticdataininvestmentmanagement_online.pdf) | — | 상 | **비도입** (연구 단계 — stylized facts 재현 미완, 부트스트랩이 동일 목적을 저위험 달성) |

**ADR-14. DART 재무·공시 데이터 전략 확장 (2026-09-11, 조사 기반)**

DART 연동(ADR-9)으로 확보된 데이터 접근권(일 한도 40,000건 — 현 사용량 수십 건, 여유 충분)을 공모주 외 일반 거래에 확장한다. 조사 결과 채택 3건 + 보류 2건:

| # | 전략 | 원전 (인용) | 한국 실증 | DART 구현 | 지평 | 결정 |
|---|---|---|---|---|---|---|
| G1 | **SEO/CB/BW 발행 회피 블랙리스트 자동화** | [Loughran & Ritter 1995 (JF)](https://doi.org/10.1111/j.1540-6261.1995.tb05166.x) 인용 3,400+ | 국내 SEO 장기 저성과 정설 | ✅ 주요사항 구조화 API(유상증자 2020023, CB/BW/EB 2020033~35) | 장기 회피 필터 | **1순위 — 즉시** (매수 아닌 회피라 구현 리스크 최소, 기존 DisclosureBlacklist 골격에 자동 공급) |
| G2 | **자사주 취득 이벤트 스윙** (S1 가설) | [Ikenberry et al. 1995 (JFE)](https://doi.org/10.1016/0304-405X(95)00826-Z) 인용 1,800+ | 공시일 양(+) CAR 실증 + **내부자 동시 매도 시 허위신호** ([국내 연구](https://www.dbpia.co.kr/journal/articleDetail?nodeId=NODE07227971)) | ✅ 취득 결정(2020038)·신탁(2020040) + elestock(임원 소유변동) 필터 | 스윙~중기 | **2순위 — 게이트 v2 백테스트 후보** (직접취득/신탁 구분 + 내부자 매도 필터 필수) |
| G3 | **F-Score 유니버스 필터** (M2 가설) | [Piotroski 2000 (JAR)](https://www.semanticscholar.org/paper/0559e92e06dae21e77ea79d79417b8a1d40be772) 인용 1,300+ | 국내 분위 스프레드 ~18%, 23년 중 20년 양(+) ([Ewha](https://pure.ewha.ac.kr/en/publications/fundamental-analysis-and-stock-returns-korean-evidence/), KAIST·KCI 다수) | ✅ fnlttSinglAcntAll로 9항목 전부 산출 (B/M 필터엔 키움 시총 보완) | 중기·장기 유니버스 | **3순위** — 신규 매매 전략이 아닌 기존 계열의 종목 풀 품질 개선 |
| — | PEAD (SUE 드리프트) | Ball & Brown 1968 (6,600+), Bernard & Thomas 1989/90 | 월 0.85%→위험조정 후 0.36%로 축소 ([KCI](https://www.kci.go.kr/kciportal/ci/sereArticleSearch/ciSereArtiView.kci?sereArticleSearchBean.artiId=ART001995137)) | ⚠️ 부분 — 잠정실적은 구조화 API 없음(원문 파싱 필요), 정기보고서 기준이면 신호 +45일 지연으로 드리프트 전반부 상실 | 스윙~중기 | **보류** — SUE는 컨센서스 불필요(seasonal random walk, BT 원방식)라 구현 자체는 가능. 원문 파싱 인프라 후 2단계 |
| — | Sloan 발생액 단독 | Sloan 1996 (TAR) 4,500+ | — | 가능 | — | **비도입** — 미국 소멸 증거([Green et al. 2011](https://pure.psu.edu/en/publications/going-going-gone-the-apparent-demise-of-the-accruals-anomaly/)). F-Score의 accrual 항목이 흡수 |

**연동 설계 원칙**:
- corp_code↔종목코드 매핑(corpCode.xml 전량 다운로드, 월 1회 갱신), 신규 테이블 `dart_events`(주요사항)·`dart_financials`(분기 재무)
- **신호 시점 규칙(룩어헤드 방지)**: 공시 접수일(rcept_dt) 다음 거래일 시가부터 진입 가능. 재무 데이터는 법정 제출기한(분기+45일/사업+90일) 이후에만 사용 가능한 것으로 백테스트에서 강제
- 내부자 지분 추종(Lakonishok & Lee 2001)은 2024-07 사전공시제도로 신호 구조가 변해 과거 백테스트 외삽 불가 — G2의 필터 용도로만 사용
- 시총·수정주가·거래대금은 키움 API 보완. **모든 신규 가설(G2·G3)은 게이트 v2 판정 — 예외 없음**

### 조정 후 총평

1인 개발 기준 실행 가능 범위로 축소하되, v3의 설계 의도(경계·계약·인텔리전스)는 모두 보존. 총 기간 약 13~16주(사이드 프로젝트 기준), 트레이딩 MVP는 7~8주차에 도달.

---

## 1. 목표와 성공 기준

**목표**: 검증된 전략을 이벤트 기반 엔진으로 자동 실행하고, 백테스트와 라이브가 동일 코드로 동작하는 시스템.

**정량적 게이트 (단계 통과 기준)**:

| 게이트 | 기준 (v2 — ADR-12 채택 2026-09-11) |
|---|---|
| ① 백테스트 → 모의투자 | OOS 수익 양(+) · **SPA_c p<0.05**(vs KODEX200 B&H) · **부트스트랩 p95 MDD < 지평 예산**(장기·중기 25%/스윙 15%/단타 10%) · 슬리브 환산 총계좌 MDD<15% (DSR·PBO 병기) |
| ② 모의투자 → 실계좌 소액 | 2주 이상 무인 운영, 백테스트 대비 슬리피지 격차 계측·허용범위 내, 치명 오류 0건 |
| ③ 소액 → 본운영 | 4주 이상 실계좌 성과가 모의와 통계적 유사, 킬스위치·복구 훈련 완료 |

## 2. 핵심 설계 원칙 (근거 자료 기반)

**(1) 이벤트 기반 아키텍처 — 백테스트 = 라이브 동형**
벡터화 백테스트는 룩어헤드 바이어스에 취약하고 라이브 코드와 이원화됨. 이벤트 기반 엔진은 시장데이터/시그널/주문/체결을 모두 이벤트로 처리해 백테스트와 실거래가 컴포넌트 교체만으로 전환됨 ([QuantStart](https://www.quantstart.com/articles/Event-Driven-Backtesting-with-Python-Part-I/), [IBKR Quant](https://www.interactivebrokers.com/campus/ibkr-quant-news/a-practical-breakdown-of-vector-based-vs-event-based-backtesting/)). 국내 사례: LEAN 기반 한국주식 툴킷 ([buylow](https://github.com/JeongSeongMok/buylow)).

**(2) 과최적화 방지 — 통계적 검증 내장**
소수 전략 구성만 반복 백테스트해도 높은 성과가 쉽게 나오며, 과최적화 전략은 OOS에서 체계적으로 언더퍼폼. 대응 3중: walk-forward 기본, [Deflated Sharpe Ratio](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2460551) (Bailey & López de Prado 2014, JPM)로 다중검정·비정규성 보정, 전략 trial 횟수 DB 기록 → [PBO](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2326253) (Bailey et al. 2017, J. Computational Finance) 산출. 최신 실증 근거 2건 반영:
- [Arian, Norouzi & Seco (2024, Knowledge-Based Systems)](https://www.sciencedirect.com/science/article/abs/pii/S0950705124011110): 통제 실험에서 **CPCV가 walk-forward·k-fold 대비 PBO/DSR 모두 우수, walk-forward 단독은 false discovery 방지에 취약** — 게이트 ① 재판정부터 walk-forward에 CPCV를 병행한다 (Phase 7 → 앞당김)
- [Suhonen et al. (2017, JPM)](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2757113): 글로벌 IB 판매 215개 실전 전략에서 **라이브 Sharpe가 백테스트 대비 중앙값 73% 하락**, 복잡한 전략일수록 저하 폭 확대 — "라이브 기대 성과 = 백테스트의 1/3~1/2" 가정과 게이트 ②의 격차 계측 기준의 직접 근거

**(3) 리스크 우선 포지션 사이징**
고정비율(fixed fractional)로 시작 — 엣지 추정 불확실 초기에 Kelly보다 강건. 거래 50~100건 축적 후 fractional Kelly(1/2 이하) 전환 검토 — 근거: [Thorp (2006)](https://gwern.net/doc/statistics/decision/2006-thorp.pdf), Princeton Newport Partners 실전 적용(약 20년 연 19%+); 실무 표준은 추정오차 대응을 위한 1/2~1/4 Kelly. 변동성 타게팅은 [Moreira & Muir (2017, Journal of Finance)](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2659431) 및 [Harvey et al. (2018, JPM)](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=3175538) (Man AHL 실무진 저술·Bernstein Fabozzi 수상) 근거 — 주식·위험자산에서 Sharpe 개선 + 꼬리위험 축소. **한계 명시**: [Cederburg et al. (2020, JFE)](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=3357038)은 팩터 전략 전반으로의 일반화에 부정적(실시간 구현 시 Sharpe 저하) — 본 시스템처럼 시장지수·단일 위험자산 계열에 적용하는 것이 문헌상 안전 영역.

**(4) 감사 추적 + 킬스위치는 필수 인프라 (규제 1차 자료 기준)**
- [SEC Rule 15c3-5](https://www.ecfr.gov/current/title-17/chapter-II/part-240/section-240.15c3-5) (Market Access Rule): 사전 리스크 통제·감독 절차 의무화. **최초 집행 사례가 Knight Capital(2012)** — 배포 오류로 45분간 400만+ 주문 오발주, 손실 $460M+, [SEC 제재 $12M](https://www.sec.gov/newsroom/press-releases/2013-222) ([명령서 원문](https://www.sec.gov/files/litigation/admin/2013/34-70694.pdf)). 킬스위치·통제된 배포·사전 한도검증의 실증 사례
- [MiFID II RTS 6 (EU 2017/589)](https://eur-lex.europa.eu/eli/reg_del/2017/589/oj/eng): Art.12 Kill functionality(전 미체결 즉시 취소 + 주문별 책임 알고리즘 식별), 배포 전 테스트, 연간 자체평가 의무
- 한국: KRX 파생상품시장 2025 제도개선으로 고빈도 알고리즘 거래자 사전 등록·식별 ID 도입(회원사 의무). 개인 API 매매에 별도 등록 의무는 현재 없으나 **시장질서 교란·시세조종 규제는 알고리즘 매매에 동일 적용** ([증선위 과징금 사례](https://fsc.go.kr/no010101/79339)). 초단타 규제 입법 논의 진행 중(2025-12 발의, 계류)
→ 설계 반영: 전 주문 이벤트 영속화(발원 추적), ClientOrderId로 주문별 전략 식별(RTS 6 Art.12 정합), 텔레그램 원격 킬스위치 + 자동 트리거, 배포 전 paper 프로필 강제.

## 3. 키움 REST API 실전 제약 (커뮤니티 검증 사항)

[알고랩 2026 가이드](https://algolab.co.kr/blog/kiwoom-rest-api-algotrading-guide-2026), [키움 공식 FAQ](https://openapi.kiwoom.com/assist/assist0202) 기준:

- TR(api-id)별 독립 rate limit — TR별 토큰버킷(약 1req/s, 버스트 2) + 429 백오프 필수
- 실전/모의 도메인 분리 — base URL 환경변수 토글, 모의 코드 실전 오적용 사고 다발
- api-id 헤더 오타 → 엉뚱한 TR 실행 위험 — TR 정의를 enum으로 중앙 관리
- WebSocket 단절 감지 실패 → 매매 정지 사고 — heartbeat 감시, 자동 재연결+재구독
- 장 시간 외 주문 → 무효 주문 누적 — 거래 캘린더/시간 가드
- 지원 범위: 국내주식(ETF/ETN 포함), Java 공식 지원 확인
- **공식 GitHub 저장소 개설 확인** (2025~2026): [Kiwoom-Securities/Kiwoom-REST-API](https://github.com/Kiwoom-Securities/Kiwoom-REST-API) — 공식 클라이언트·샘플·CLI(kwcli). 커뮤니티 래퍼보다 우선 참조
- 참고 구현: [kiwoom-rest-api (207 엔드포인트+WS 19종)](https://github.com/younghwan91/kiwoom-rest-api)
- rate limit 공식 수치는 문서 미공개 — 커뮤니티 관측치(TR당 약 1req/s) 기반 구현 + 429 백오프 유지, 구현 시점마다 [공식 공지](https://openapi.kiwoom.com/guide/index) 재확인

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
| monitor | 텔레그램 알림/원격 명령, 헬스체크, 성과 리포트, 경량 대시보드(FE, 4-1절) | — |

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

**2단계 — 뉴스 감성 (Phase 7)**: 뉴스 수집(RSS/주요 언론) → 종목·섹터 매핑 → KR-FinBERT 사이드카 스코어링 → `NewsSentiment`. 모델 선정 근거: [snunlp/KR-FinBert-SC](https://huggingface.co/snunlp/KR-FinBert-SC) — 뉴스 44만건+애널리스트 리포트 1.1만건 추가 사전학습, 감성분류 정확도 0.963(KoBERT 0.817 대비), **월 다운로드 7.8만회로 한국어 금융 감성분석의 사실상 표준** (2026-08 확인). 이론 계보: FinBERT — [Araci (2019, arXiv)](https://arxiv.org/abs/1908.10063) 및 [Huang, Wang & Yang (2023, Contemporary Accounting Research)](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=3910214), 두 계열 합산 인용 1,400+. 국내 실증: [KOSPI 예측](https://www.dbpia.co.kr/journal/articleDetail?nodeId=NODE11227781), [공모주 시초가 예측](https://www.kci.go.kr/kciportal/landing/article.kci?arti_id=ART002932250), [감성-주가 딥러닝](https://www.kci.go.kr/kciportal/ci/sereArticleSearch/ciSereArtiView.kci?sereArticleSearchBean.artiId=ART002869886). 2024~2026 트렌드는 금융 LLM(FinGPT 등)으로 이동 중이나 한국어 금융 특화로 검증된 공개 대체재 부재 → KR-FinBert-SC 유지 + LLM 프롬프팅 병행 실험은 선택 과제. 지정학 리스크는 뉴스 스코어로 자체 산출, 임계 초과 시 킬스위치 후보 → 텔레그램 승인 요청.

원칙: 감성·거시 시그널은 단독 매매 근거가 아닌 **필터/가중치**. 도입 전후 필터 on/off 백테스트로 기여도 입증 (DSR/PBO 검증 동일 적용).

### 4-1. 경량 대시보드 (FE)

원칙: **복잡하지 않게** — 프론트 빌드체인(React/번들러) 없이 monitor 모듈이 서빙하는 단일 정적 HTML + vanilla JS(주기 폴링) + REST API. 물리 분리 트리거 전까지는 이 구성 유지, 요구 증가 시에만 SPA 검토.

| 화면 요소 | 데이터 | API |
|---|---|---|
| 포지션 현황 | 종목/수량/평단 | GET /api/dashboard/positions |
| 최근 이벤트 피드 | Signal/Order/Fill 흐름 (링버퍼) | GET /api/dashboard/events |
| 킬스위치 상태·토글 | 비상 정지/해제 | GET·POST /api/dashboard/killswitch |
| 테스트 시그널 실행 | SIM 루프 수동 검증 (paper 전용) | POST /api/dashboard/test-signal |

경계 규칙: 조회·제어는 타 모듈의 공개 API(PositionBook, KillSwitch) 직접 참조 허용(읽기/운영 제어), 매매 흐름 개입은 이벤트로만.

## 6. 데이터 계층

| 데이터 | 수집 | 저장 |
|---|---|---|
| 일봉/분봉 | REST 배치 (rate limit 내 야간 수집) | PostgreSQL 파티셔닝(종목/월) |
| 실시간 체결/호가 | WebSocket 구독 | 핫패스 메모리, 비동기 배치 flush |
| 전 이벤트 (주문/체결/시그널) | 이벤트 스토어 append-only | 감사 + 백테스트 리플레이 원천 |
| 전략 trial 이력 | 백테스트 실행마다 기록 | PBO/DSR 계산 원천 |
| 거시/공시/뉴스 | 배치 + 사이드카 | 모듈별 스키마 |

비용 모델(위탁수수료 + 증권거래세 + 슬리피지 추정)을 SimExecution에 반영 — 단기 전략은 비용 모델 정확도가 성패 결정. 이론 원전: [Perold (1988) Implementation Shortfall](https://jpm.pm-research.com/content/14/3/4) (paper vs 실제 성과 갭 개념, 기관 TCA 표준), [Almgren & Chriss (2000)](https://www.smallake.kr/wp-content/uploads/2016/03/optliq.pdf) (인용 1,600+, 시장충격 모델).

**세율 변경 반영 (2026-01-01 시행, [기재부 2025 세제개편안](https://www.moef.go.kr/nw/mosfnw/detailInfograpView.do?searchNttId1=MOSF_000000000074691&menuNo=4040500))**: 매도 시 코스피 증권거래세 0.05% + 농특세 0.15% = **0.20%**, 코스닥 0.20% (2025년까지는 양 시장 0.15%). 키움 온라인 위탁수수료 0.015%. → 백테스트 비용 상수를 왕복 기준 약 0.23% + 슬리피지로 갱신하고, 기존 실험(0.35% 가정)은 보수적 상한으로 유지.

## 7. 전략 연구 프로세스

후보 — 국제 고인용 원전 + 국내 실증의 이중 근거로 선정:

| 전략 요소 | 국제 원전 (인용) | 실무 적용 | 국내 실증 |
|---|---|---|---|
| 시계열 모멘텀 (C 계열 핵심) | [Moskowitz, Ooi & Pedersen (2012, JFE)](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2089463) 1,400+ | AQR Managed Futures ([AQMIX](https://funds.aqr.com/funds/alternatives/aqr-managed-futures-strategy-fund/aqmix), AUM ~$3.4B). [137년 실증](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2993026) (Hurst et al. 2017): 60/40 최대 낙폭 10회 중 8회 양(+) 수익 | [모멘텀](https://scholarworks.sookmyung.ac.kr/item/0f2d4e15-f730-47ad-add3-23536003d26f) |
| 국면 필터 (SMA 추세) | [Faber (2007, J. Wealth Mgmt)](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=962461) — SSRN 최다 다운로드급 | Cambria 계열 ETF로 상품화 | — |
| 변동성 타게팅 | [Moreira & Muir (2017, JF)](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2659431), [Harvey et al. (2018, JPM)](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=3175538) | Man AHL 실무진 저술 | [변동성 조정](https://www.dbpia.co.kr/journal/detail?nodeId=T15120779) (KOSPI +3.23%p·KOSDAQ +15.70%p) |
| 변동성 돌파 (실험 1·2에서 비용 드래그로 폐기) | — | — | [한국콘텐츠학회](https://koreascience.kr/article/JAKO202211258153666.pub?lang=ko&orgId=kocon) |

현행 C3(`VolTarget(RegimeFilter(TSMomentum))`)는 위 세 고인용 계열의 교집합 — 임의 조합이 아니라 각각 독립적으로 검증된 요소의 결합. 최근 재검증(2025 arXiv: [CTA 재현](https://arxiv.org/abs/2507.15876), [Trend Premia 구조](https://arxiv.org/abs/2510.23150))도 3~12개월 TSM의 유의성 유지를 확인.

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

- **스케줄**: 08:00 토큰 갱신·정합성 점검 → 08:50 WS 연결·구독 → 09:00 가동 → 15:20 정리 → 15:50 일일 리포트(텔레그램) → 야간 배치(일봉·거시 수집, DB 정리)
- **거래 캘린더**: 공공데이터 특일 API(`getRestDeInfo`)로 매년 11/1 내년 휴장일 일괄 동기화 + 매월 15일 향후 30일 창 재동기화(대체공휴일 늦은 확정 흡수). DB 우선, 하드코딩 폴백. 연말휴장 등 거래소 자체 휴장은 MANUAL 등록
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
| 5.5 검증 고도화 | 1~2주 | **T 확장(2015~) 재검증 + CPCV 러너** (Arian 2024 근거로 Phase 7에서 앞당김), 비용 상수 2026 세율 갱신 | C3 동결 유지 하에 CPCV·walk-forward 이중 판정 |
| 6. 운영 검증 | 4주+ | 무인 모의 운영 = **진행형 검증**(새 데이터로 성과 축적), 슬리피지·백테스트 격차 계측(Suhonen 2017의 73% 저하를 기저 기대치로), 복구 훈련 | 게이트 ② 통과 → 실계좌 소액 |
| 7. 확장 | 지속 | news-intel(KR-FinBert-SC 사이드카), fractional Kelly(1/2~1/4, Thorp 2006), 물리 분리 트리거 평가, JDK 25 검토 | 게이트 ③ 통과 후 본운영 |
| 8. 요구 확장 (v5) | 병행 | **8a** FE React 전환(ADR-10, 착수 확정) → **8b** 공모주 반자동(ADR-9: 일정 수집·필터·알림·기록) → **8c** ETF 롱·숏 C4 백테스트(ADR-8, CPCV/DSR 게이트) → **8d** 익절/손절 규칙 paper A/B(ADR-7, 승률>52.4% 입증 필요) | 8b는 독립 기능으로 즉시 사용 가능. 8c·8d는 게이트 ① 통과 전 라이브 금지 |

## 11. 참고 문헌·자료 (2026-08-28 전면 개편 — 인용 수는 Semantic Scholar/OpenAlex 기준, Google Scholar는 통상 이보다 높음)

**A. 전략 근거 — 고인용 원전 + 실무 적용**
- Moskowitz, Ooi & Pedersen, *Time Series Momentum* (2012, JFE) — 인용 1,400+ — [SSRN](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2089463) · [무료 PDF](https://w4.stern.nyu.edu/facdir/lpederse/papers/TimeSeriesMomentum.pdf) | 실무: AQR
- Hurst, Ooi & Pedersen, *A Century of Evidence on Trend-Following Investing* (2017, JPM) — [SSRN](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2993026) · [AQR](https://www.aqr.com/Insights/Research/Journal-Article/A-Century-of-Evidence-on-Trend-Following-Investing) | 1880~2016, 67개 시장
- Moreira & Muir, *Volatility-Managed Portfolios* (2017, Journal of Finance) — 인용 500+ — [SSRN](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2659431)
- Harvey et al., *The Impact of Volatility Targeting* (2018, JPM) — Man AHL 실무진, Bernstein Fabozzi 수상 — [SSRN](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=3175538)
- Cederburg et al., *On the performance of volatility-managed portfolios* (2020, JFE) — **비판·한계 연구** — [SSRN](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=3357038)
- Faber, *A Quantitative Approach to Tactical Asset Allocation* (2007/2013) — SSRN 최다 다운로드급 — [SSRN](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=962461)
- Thorp, *The Kelly Criterion in Blackjack, Sports Betting, and the Stock Market* (2006) — [PDF](https://gwern.net/doc/statistics/decision/2006-thorp.pdf)
- 최신 재검증(2025): [CTA 재현](https://arxiv.org/abs/2507.15876) · [Network Momentum](https://arxiv.org/abs/2501.07135) · [Trend Premia 구조](https://arxiv.org/abs/2510.23150)

**A-2. 요구 확장(v5) 근거 — 단기 규칙·인버스·공모주**
- Kaminski & Lo, *When Do Stop-Loss Rules Stop Losses?* (2014, J. Financial Markets) — [ScienceDirect](https://www.sciencedirect.com/science/article/abs/pii/S138641811300030X) · [MIT OA](https://dspace.mit.edu/bitstream/handle/1721.1/114876/Lo_When%20Do%20Stop-Loss.pdf) | 손절은 모멘텀 국면에서만 유효
- Barber, Lee, Liu & Odean, *The Cross-Section of Speculator Skill* (2014, JFM) — [ScienceDirect](https://www.sciencedirect.com/science/article/abs/pii/S1386418113000190) | 대만 전수: 지속 수익 데이트레이더 <5%
- Cheng & Madhavan, *The Dynamics of Leveraged and Inverse ETFs* (2009, JOIM) — [SSRN](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=1539120) | 일일 리밸런싱 복리 괴리 원전 / [금감원 소비자경보 2026-03](https://eiec.kdi.re.kr/policy/materialView.do?num=278124)
- 공모주: [금융위 중복청약 금지(2021)](https://www.fsc.go.kr/no010101/76078) · [청약경쟁률→수익률 KCI](https://www.kci.go.kr/kciportal/ci/sereArticleSearch/ciSereArtiView.kci?sereArticleSearchBean.artiId=ART001546569) · [2023 제도 이후 주가행태 KCI](https://www.kci.go.kr/kciportal/ci/sereArticleSearch/ciSereArtiView.kci?sereArticleSearchBean.artiId=ART003117548) · [2025 상반기 IPO 리포트(유진)](https://www.eugenefn.com/common/files/amail/20250707_B90_jongsun.park_2402.pdf)

**B. 검증 방법론 — 과최적화·라이브 갭**
- Bailey & López de Prado, *The Deflated Sharpe Ratio* (2014, JPM) — [SSRN](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2460551)
- Bailey, Borwein, López de Prado & Zhu, *The Probability of Backtest Overfitting* (2017, J. Computational Finance) — [SSRN](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2326253)
- López de Prado, *Advances in Financial Machine Learning* (2018, Wiley) — CPCV 원전 — [Wiley](https://www.wiley.com/en-us/Advances+in+Financial+Machine+Learning-p-9781119482086)
- Arian, Norouzi & Seco, *Backtest Overfitting in the Machine Learning Era* (2024, Knowledge-Based Systems) — CPCV > walk-forward 정량 입증 — [ScienceDirect](https://www.sciencedirect.com/science/article/abs/pii/S0950705124011110)
- Suhonen et al., *Quantifying Backtest Overfitting in Alternative Beta Strategies* (2017, JPM) — 215개 실전 전략, 라이브 Sharpe 중앙값 −73% — [SSRN](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2757113)
- Perold, *The Implementation Shortfall* (1988, JPM) — [JPM](https://jpm.pm-research.com/content/14/3/4) / Almgren & Chriss, *Optimal Execution* (2000) — 인용 1,600+ — [PDF](https://www.smallake.kr/wp-content/uploads/2016/03/optliq.pdf)

**C. 규제·안전 — 1차 자료**
- SEC Rule 15c3-5 — [eCFR 원문](https://www.ecfr.gov/current/title-17/chapter-II/part-240/section-240.15c3-5) · [SEC FAQ](https://www.sec.gov/rules-regulations/staff-guidance/trading-markets-frequently-asked-questions/divisionsmarketregfaq-0)
- Knight Capital 제재(2013) — [SEC 보도자료](https://www.sec.gov/newsroom/press-releases/2013-222) · [명령서 원문](https://www.sec.gov/files/litigation/admin/2013/34-70694.pdf)
- MiFID II RTS 6 (EU 2017/589) — [EUR-Lex](https://eur-lex.europa.eu/eli/reg_del/2017/589/oj/eng) · [ESMA 리뷰(2021)](https://www.esma.europa.eu/sites/default/files/library/esma70-156-4572_mifid_ii_final_report_on_algorithmic_trading.pdf)
- 한국: [증선위 알고리즘 매매 과징금 사례](https://fsc.go.kr/no010101/79339) · [알고리즘 거래 금융규제 연구(KCI)](https://www.kci.go.kr/kciportal/ci/sereArticleSearch/ciSereArtiView.kci?sereArticleSearchBean.artiId=ART003241840)
- 거래 비용: [기재부 2025 세제개편안(2026 시행 세율)](https://www.moef.go.kr/nw/mosfnw/detailInfograpView.do?searchNttId1=MOSF_000000000074691&menuNo=4040500)

**D. 감성분석·데이터**
- Araci, *FinBERT* (2019, arXiv) — 인용 700+ — [arXiv](https://arxiv.org/abs/1908.10063) / Huang, Wang & Yang, *FinBERT* (2023, Contemporary Accounting Research) — 인용 700+ — [SSRN](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=3910214)
- KR-FinBert-SC (snunlp, 서울대) — 월 다운로드 7.8만, 정확도 0.963 — [HuggingFace](https://huggingface.co/snunlp/KR-FinBert-SC)
- 국내 실증: [KOSPI 예측(DBpia)](https://www.dbpia.co.kr/journal/articleDetail?nodeId=NODE11227781) · [공모주 시초가(KCI)](https://www.kci.go.kr/kciportal/landing/article.kci?arti_id=ART002932250) · [감성-주가 딥러닝(KCI)](https://www.kci.go.kr/kciportal/ci/sereArticleSearch/ciSereArtiView.kci?sereArticleSearchBean.artiId=ART002869886)
- 국내 전략 실증: [변동성 조정(DBpia)](https://www.dbpia.co.kr/journal/detail?nodeId=T15120779) · [변동성 돌파(KoreaScience)](https://koreascience.kr/article/JAKO202211258153666.pub?lang=ko&orgId=kocon) · [모멘텀(ScholarWorks)](https://scholarworks.sookmyung.ac.kr/item/0f2d4e15-f730-47ad-add3-23536003d26f)
- ECOS/FRED 수집 — [wikidocs](https://wikidocs.net/366487) · [PublicDataReader](https://github.com/WooilJeong/PublicDataReader/blob/main/assets/docs/ecos/ecos.md)

**E. 구현·인프라 — 공식 문서 우선**
- 키움 REST API — [공식 포털](https://openapi.kiwoom.com/guide/index) · [공식 GitHub](https://github.com/Kiwoom-Securities/Kiwoom-REST-API) · [커뮤니티 래퍼](https://github.com/younghwan91/kiwoom-rest-api) · [알고랩 가이드](https://algolab.co.kr/blog/kiwoom-rest-api-algotrading-guide-2026)
- Spring Modulith — [이벤트 외부화 공식 문서](https://docs.spring.io/spring-modulith/reference/events.html) · [2.0 GA 공지](https://spring.io/blog/2025/11/21/spring-modulith-2-0-ga-1-4-5-and-1-3-11-released/) · [Kafka 예제](https://github.com/spring-projects/spring-modulith/blob/main/spring-modulith-examples/spring-modulith-example-kafka/readme.adoc)
- JEP 491 (가상 스레드 핀닝 해결, JDK 24) — [openjdk.org](https://openjdk.org/jeps/491)
- PostgreSQL 파티셔닝 — [공식 문서](https://www.postgresql.org/docs/current/ddl-partitioning.html) / TimescaleDB(TigerData) 라이선스 — [tigerdata.com](https://www.tigerdata.com/legal/licenses)
- 텔레그램 봇 보안 — [Bot API 공식(secret_token)](https://core.telegram.org/bots/api): chat_id 화이트리스트(적용됨) + 토큰 회전 + 위험 명령 2단계 확인
- Event-Driven Backtesting — [QuantStart](https://www.quantstart.com/articles/Event-Driven-Backtesting-with-Python-Part-I/) · [IBKR](https://www.interactivebrokers.com/campus/ibkr-quant-news/a-practical-breakdown-of-vector-based-vs-event-based-backtesting/) · [buylow](https://github.com/JeongSeongMok/buylow)

**F. 강의/서적**
- Stefan Jansen, *Machine Learning for Trading* — [ml4t](https://stefan-jansen.github.io/machine-learning-for-trading/08_ml4t_workflow/01_multiple_testing/) (번역: 퀀트 투자를 위한 머신러닝·딥러닝 알고리듬 트레이딩 2/e, 에이콘)
- SBAI 백테스팅 가이드(업계 자율규제) — [PDF](https://www.sbai.org/static/a7fc0b57-9c84-4b72-adae25e4bfae1be2/ARP-Backtesting.pdf)
