# autoStock 아키텍처 원칙

기준: 2026-08-13, 외부 제안(GPT) 검토 후 채택·조정·기각을 구분해 확정 (PLAN.md ADR-6).
이 문서는 "무엇을 따르는가"의 기준서다. 배경 결정은 PLAN.md의 ADR, 진행 상태는 PROGRESS.md.

---

## 1. 적용 원칙 조합

```
Spring Modulith  +  DDD Lite  +  Hexagonal(Port/Adapter)  +  Functional Core/Imperative Shell
+  Strategy Pattern  +  Risk Policy(Specification)  +  Position Sizing 분리
+  Order State Machine  +  Idempotency(ClientOrderId)  +  Domain Event  +  Reconciliation
+  CQRS Lite(View DTO)
```

핵심 문장: **Strategy는 주문하지 않는다.**

```
Market → Strategy → Signal → Risk Policy → Position Sizing
      → Order(State Machine) → Execution → Kiwoom → Reconciliation → Portfolio
```

## 2. 모듈 구조 — 현행과 목표

현행 구현(왼쪽)은 이미 도메인 기준 분리이며, 목표 구조(오른쪽)로 **점진 재편**한다.
리네이밍·분리는 진행 중 작업을 보호하기 위해 별도 사이클에서 일괄 수행한다.

| 현행 | 목표 | 책임 |
|---|---|---|
| marketdata | market | 시세·시장 데이터 |
| strategy | strategy | Signal 생성 (주문 금지) |
| risk | risk | 거래 허용 판단 (Policy 조합) |
| execution (주문 생성+전달 혼재) | **trading** / **execution** 분리 | trading: 주문 생성·상태 관리 / execution: 브로커 전달 |
| risk 내 PositionBook | **portfolio** 신설 | 보유 포지션·손익 |
| newsintel | analysis | 뉴스/FinBERT 분석 |
| audit, backtest, monitor, kiwoom, common | 유지 | 감사·백테스트·운영·브로커어댑터·계약 |

모듈 내부는 규모가 커지는 모듈부터 Hexagonal 구조(domain/application/adapter)를 적용한다.
`common`(shared)은 이벤트 계약·진짜 공통 유틸만 — 특정 도메인 개념이면 해당 모듈에 둔다.

## 3. DDD Lite — Value Object

중요 도메인 값의 primitive 사용을 금지한다 (Primitive Obsession 방지). 도입 순서:

1순위 (주문 안전): `ClientOrderId`, `BrokerOrderId`, `StockCode`, `Quantity`(양수 검증), `Price`
2순위: `Money`, `Confidence`, `ReturnRate`, `TradingDate`, `AccountNo`

기존 record 이벤트(common)는 스키마 v2로 VO를 단계 도입 — 한 번에 전면 교체하지 않는다.

## 4. Hexagonal — Port/Adapter

Domain/Application은 외부 시스템 타입을 모른다.

```java
public interface BrokerPort {            // trading·execution이 아는 것
    OrderResult placeOrder(OrderCommand cmd);
    AccountBalance balance();
    BrokerOrder findOrder(BrokerOrderId id);
}
// KiwoomBrokerAdapter(키움 REST) 가 구현 — Kiwoom 응답 Map/DTO는 어댑터 밖 노출 금지
public interface SentimentAnalyzer {     // analysis가 아는 것
    Sentiment analyze(NewsContent news); // FastAPI/KR-FinBERT는 어댑터 뒤에
}
```

현행 `KiwoomRestClient`/`KiwoomOrderService`/`KiwoomDailyChartService`가 사실상 어댑터 —
Port 인터페이스 추출이 재편 사이클의 첫 작업이다.

## 5. Functional Core / Imperative Shell

전략·리스크 판단은 순수 함수로: 입력(스냅샷)만 받고 DB/HTTP/시계 접근 금지.
외부 I/O는 Application 계층이 수집해 파라미터로 전달한다.
**이 원칙이 "백테스트=라이브 동일 전략 코드"를 가능하게 한다** — 이미 BacktestStrategy·BreakoutMath로 구현됨.

## 6. Order State Machine (확장 확정)

현행 4상태(SUBMITTED/FILLED/CANCELLED/REJECTED)를 다음으로 확장한다:

```
CREATED → VALIDATED → SUBMITTING → SUBMITTED → ACCEPTED → PARTIALLY_FILLED → FILLED
                          │            ├─ REJECTED
                          │            ├─ CANCEL_REQUESTED → CANCELLED
                          └─ UNKNOWN ──┘
```

**UNKNOWN이 핵심이다**: 주문 API 타임아웃 = "실패"가 아니라 "결과 모름".
FAILED 처리 후 재시도하면 중복 주문이 난다. UNKNOWN으로 두고 Reconciliation이 해소한다:

```
주문 → Timeout → UNKNOWN → 키움 주문/체결 조회 → 존재? ├─ 예: 기존 주문 반영
                                                  └─ 아니오: 정책에 따라 재주문
```

같은 이유로 **주문 API에는 무조건적 자동 Retry를 적용하지 않는다** (현행 429-only retry 유지).
조회 API에만 Timeout/Retry/CircuitBreaker/RateLimiter 전면 적용.

## 7. Idempotency — ClientOrderId

UUID 멱등키를 가독성 있는 ClientOrderId 포맷으로 교체한다:

```
20260813-MOMENTUM-005930-BUY-001
(날짜-전략-종목-방향-일련) + DB UNIQUE 제약 = 최종 방어선
```

흐름: Signal → Order 생성(ClientOrderId 부여) → **DB 저장** → 브로커 전송. 저장이 전송보다 먼저다.

## 8. Reconciliation — Broker가 Source of Truth

내부 DB만 신뢰하지 않는다. 실행 시점: 앱 시작, WS 재연결, 주문 타임아웃(UNKNOWN), 주기적, 장 마감 후.
대사 대상: 잔고↔portfolio, 주문↔orders, 체결↔fills. 현행 OrderRecoveryService 골격을 이 개념으로 승격.

## 9. Domain Event 사용 기준

```
조회            → 인터페이스 직접 호출 (공개 API)
명령            → Application Service
상태 변화 통지    → Domain Event (Modulith)
```

모든 통신을 이벤트로 만들지 않는다 — 단순 조회까지 이벤트화하면 복잡성만 는다. (현행 원칙과 일치)

## 10. CQRS Lite + 시스템 상태

- Query는 Domain Entity가 아닌 **View DTO** 반환 (`PortfolioView`, `DashboardView` 등), Command는 Application Service 호출. DB는 하나.
- 대시보드는 `GET /api/dashboard` 하나로 조합(Facade — 업무 규칙 금지, 조합만).
- 자동매매 상태는 boolean이 아니라 상태기계: `STOPPED→STARTING→RUNNING→STOPPING`, 장애 시 `DEGRADED/ERROR`. 시작 명령의 응답은 `STARTING`이고 FE는 재조회한다 — **Backend가 Source of Truth**.
- FE: 현 단계는 vanilla 단일 HTML 유지(PLAN 4-1절). 화면이 3개 이상으로 늘면 React+TypeScript+TanStack Query로 전환(전역 상태 라이브러리는 그때도 도입하지 않음).

## 11. 테스트 전략

Domain 단위 테스트 비중 최대: Strategy, Risk Policy, Position Sizing, Order State Machine, Portfolio 계산, Reconciliation.
모듈 경계는 Modulith `verify()`(현행 ModularityTests)로 CI에서 강제, 필요 시 ArchUnit 규칙 추가
(strategy→kiwoom 금지, risk→kiwoom 금지, strategy→execution 직접 호출 금지 등).
**실제 키움 API 없이 핵심 로직 전부 검증 가능해야 한다** (현행 방침: 실 API 호출 검증 금지와 일치).

## 12. 의도적으로 적용하지 않는 것

| 미채택 | 이유 |
|---|---|
| Event Sourcing | PostgreSQL + Order State + 체결 이력으로 충분. 현행 event_store는 감사·백테스트 리플레이용 append 로그이지 상태 재구성용 ES가 아니다 — 혼동 금지 |
| Full CQRS (DB 분리) | 단일 PG에서 코드 책임만 분리 |
| Saga | 물리 모놀리스 + DB 트랜잭션으로 충분 |
| 전면 이벤트 통신 | 9절 기준 유지 |
| Repository 남용 | Aggregate 단위로만 |

## 13. 설계 규칙 20 (요약)

1. Strategy는 주문하지 않는다 · 2. Strategy는 Signal까지만 · 3. Signal은 Risk 통과 후에만 Order
4. 수량 계산은 Strategy와 분리 · 5. Kiwoom은 Adapter로만 · 6. FinBERT도 Adapter로만
7. Domain은 외부 DTO를 모른다 · 8. 주문 API 무조건 Retry 금지 · 9. 모든 논리 주문에 ClientOrderId
10. 주문은 State Machine · 11. Broker와 Reconciliation 가능해야 함 · 12. Backend가 상태의 Source of Truth
13. FE는 Domain Entity 미사용 · 14. FE 조회는 View DTO · 15. Query/Command 코드 분리
16. 상태 변화는 Domain Event 우선 · 17. 매매 로직은 순수 함수 · 18. 실거래=백테스트 동일 Strategy
19. 의존 규칙은 테스트로 보호 · 20. 불필요한 분산 패턴 금지
