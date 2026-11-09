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

# 2. 환경변수 (커밋 금지, 전체 카탈로그는 .env.example 참고)
export KIWOOM_MOCK_G_APP_KEY=...     # 키움 모의투자(일반계좌) 앱키 — paper 프로필. 구명 KIWOOM_APP_KEY도 폴백 인식
export KIWOOM_MOCK_G_APP_SECRET=...
# export KIWOOM_LIVE_APP_KEY=...     # 실전 전용 (live 프로필) — 게이트 ② 통과 + 키 재발급 전 비워둘 것, 폴백 없음
# export KIWOOM_LIVE_APP_SECRET=...
export TELEGRAM_BOT_TOKEN=...        # (선택) 텔레그램 알림·원격 킬스위치
export TELEGRAM_CHAT_ID=...
export DATA_GO_KR_SERVICE_KEY=...    # (선택) 특일 API — 휴장일 DB 동기화

# 3. 실행 (기본 프로필 = paper 모의투자)
./gradlew :app:bootRun
# 대시보드: http://localhost:8080 — 운영 상태(시작/정지)·킬스위치·포지션·실현손익
```

기본값은 전부 안전측: `execution.mode=SIM`, `strategy.c3.enabled=false`,
`autostock.ws.enabled=false`, 텔레그램·특일 API 비활성. 실전(`live`) 프로필은
PLAN.md 1절 게이트 ② 통과 전 사용 금지.

## 전략 현황

C3(시계열 모멘텀 + KODEX200 SMA200 국면필터 + 변동성 타게팅) — 게이트 ① 후보 동결 상태.
백테스트와 라이브가 동일 순수 계산(`MomentumMath`/`RegimeMath`/`VolTargetMath`)을 공유한다.
검증 이력과 판정은 [PROGRESS.md](PROGRESS.md) 3절 참조.

## 검증

```bash
./gradlew test   # 단위 테스트 + Modulith 모듈 경계 검증
```

품질 관문은 `py -3.13 scripts/quality_gate.py`입니다(VALUNIVO 0-7 S11, D336). `:common:test`·`:app:test`와 프런트 타입 검사(`tsc --noEmit`, 공용 Node로 `npm ci` 뒤)를 실행하고, VALUNIVO·writingApp 관문과 같은 형식(`[gate] 항목: ok|FAIL`, 모듈별 시험 건수 줄, 마지막 `[gate] PASS|FAIL`, 종료 0·1)으로 출력합니다. DB 통합 시험은 `AUTOSTOCK_TEST_DB_REQUIRED=true`로 돌려 DB가 없으면 건너뛰지 않고 실패합니다(D-18: Docker가 있으면 Testcontainers로 운영 이미지). `--no-db`일 때만 `AUTOSTOCK_TEST_DB_DISABLED=true`로 일부러 빼고 그 사실을 출력하며, 운영 포트를 가리키는 `AUTOSTOCK_TEST_DB_URL`은 거절합니다.

커밋 전 훅(`.githooks/pre-commit`)은 같은 관문을 `--staged`로 부릅니다. 클론마다 `git config core.hooksPath .githooks`로 한 번 켭니다. 스테이징한 파일이 닿는 영역만 빠르게 검사합니다(`common/`·`app/`·Gradle 파일이면 컴파일과 계층 규칙 `ArchitectureRulesTest`, `frontend/`이면 타입 검사, 관문 스크립트이면 그 단위 시험). 시험 전체는 병합 전에 `--staged` 없이 실행합니다. 형식 검사·새 린트는 두지 않고 지금 규칙을 유지합니다.
