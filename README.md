# autoStock

키움증권 REST API 기반 국내주식 자동매매 시스템.
논리적 MSA + 물리적 모놀리스 (Spring Modulith).

- 설계 근거·로드맵: [PLAN.md](PLAN.md) (ADR 6건)
- 아키텍처 원칙 기준서: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (설계 규칙 20)
- 진행 현황: [PROGRESS.md](PROGRESS.md)

## 핵심 흐름 — Strategy는 주문하지 않는다

```
Market → Strategy → Signal → Risk Policy → Position Sizing
      → Order(State Machine) → Execution → Kiwoom → Reconciliation → Portfolio
```

전략 판단과 주문 실행은 반드시 분리한다. 모든 주문은 risk 관문을 통과하고,
주문 타임아웃은 실패가 아니라 UNKNOWN → Reconciliation으로 처리한다(중복 주문 방지).

## 스택

Java 21 · Spring Boot 3.5 · Spring Modulith 1.4 · PostgreSQL · Flyway · Resilience4j · Python FastAPI 사이드카(KR-FinBERT, Phase 7)

## 구조

```
common/        이벤트 스키마(모듈 간 계약, OPEN)
app/           Modulith 모놀리스 — kiwoom(공유) · marketdata · strategy · risk · execution · audit · backtest · macrointel · newsintel · monitor
sidecar-nlp/   뉴스 감성 스코어링 사이드카
infra/         docker-compose (PostgreSQL 등)
```

모듈 간 직접 참조 금지 — Spring 이벤트로만 통신하며 `ModularityTests`가 경계를 강제한다.
모든 주문은 risk 모듈을 통과하고, 모든 이벤트는 event_store에 영속화된다.

## 실행

```bash
# 1. DB
docker compose -f infra/docker-compose.yml up -d postgres

# 2. 환경변수 (커밋 금지)
export KIWOOM_APP_KEY=...
export KIWOOM_APP_SECRET=...

# 3. 실행 (기본 프로필 = paper 모의투자)
./gradlew :app:bootRun
```

실전(`live`) 프로필은 PLAN.md 1절 게이트 ② 통과 전 사용 금지.

## 검증

```bash
./gradlew test   # 단위 테스트 + Modulith 모듈 경계 검증
```
