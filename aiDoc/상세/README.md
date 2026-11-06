# autoStock 상세 문서 지도

역할별로 기존 문서를 찾는 색인이다(공통 표준 문서 구조, VALUNIVO 4-2 S2). 문서는 옮기지 않고 제자리에서 연결한다([D-22](../01-decision-log.md)). 한 문서가 여러 역할에 걸리면 주된 역할 하나에만 둔다. 새 문서를 `aiDoc/`에 만들면 이 표의 해당 역할에도 한 줄을 더한다.

먼저 읽을 것: [계획목록](../00-계획목록.md) → [진행목록](../00-진행목록.md) → [결정 로그](../01-decision-log.md) → 맡은 역할 표.

## 공통

PG18 후보 DB 검증: [PG18 리허설](../pg18-rehearsal.md)(VALUNIVO 4-5 U6, 운영 전환 전).

| 문서 | 내용 |
|---|---|
| [aiDoc/README.md](../README.md) | 문서 목록(작성 순)과 인계 메모 |
| [upgrade-2026-10/00-README.md](../upgrade-2026-10/00-README.md) | 2026-10 고도화 조사 묶음 색인 |
| [upgrade-2026-10/01-current-state.md](../upgrade-2026-10/01-current-state.md) | 2026-10 현재 상태 |
| [upgrade-2026-10/11-execution-plan.md](../upgrade-2026-10/11-execution-plan.md) | 고도화 실행 계획 |
| [upgrade-2026-10/12-decisions.md](../upgrade-2026-10/12-decisions.md) | 사용자 결정 D-01~D-21 |
| [claude-rules/](../claude-rules/)·[claude-memory/](../claude-memory/) | 리포 밖 규칙·메모리 사본 |

## 기획 (전략·제품)

| 문서 | 내용 |
|---|---|
| [PLAN.md](../../PLAN.md) | 개발 계획·ADR·로드맵 |
| [BACKTEST_REPORT.md](../../BACKTEST_REPORT.md) | 백테스트 보고 |
| [docs/research/](../../docs/research/) | 게이트 진단·X0 진단 |
| [upgrade-2026-10/05-research-planning.md](../upgrade-2026-10/05-research-planning.md) | 기획 관점 조사 |
| [upgrade-2026-10/07-research-trading-market.md](../upgrade-2026-10/07-research-trading-market.md) | 시장 구조 조사 |
| [upgrade-2026-10/08-research-trading-strategy.md](../upgrade-2026-10/08-research-trading-strategy.md) | 전략 조사 |
| [upgrade-2026-10/17-extended-hours-overnight-strategy.md](../upgrade-2026-10/17-extended-hours-overnight-strategy.md) | 시간외·익일 전략 분석 |
| [c3-trading-day-cycle.md](../c3-trading-day-cycle.md) | C3 판단 주기 21거래일 |
| [multi-strategy-paper-2026-10-16.md](../multi-strategy-paper-2026-10-16.md) | 모의투자 다전략 |
| [split-buy-swing-carry-2026-10-30.md](../split-buy-swing-carry-2026-10-30.md) | 운용 자금·분할 매수·스윙 이월 |
| [ipo-subscription-strategy.md](../ipo-subscription-strategy.md) | 공모주 청약 추천 전략 |
| [ipo-demand-forecast.md](../ipo-demand-forecast.md) | 공모주 수요예측 자동 입력 |
| [ipo-automation.md](../ipo-automation.md) | 공모주 자동화·알림 강화 |
| [strategy-change-performance.md](../strategy-change-performance.md) | 전략 변경 전후 성과 비교(구간 설정·비교 지표) |

## 디자인

| 문서 | 내용 |
|---|---|
| [upgrade-2026-10/04-research-design.md](../upgrade-2026-10/04-research-design.md) | 디자인 관점 조사 |
| [ui-status-colors.md](../ui-status-colors.md) | 화면 상태 색 대응표(UI-M1)·위험 표시(UI-M5)·테마 |
| [stock-names.md](../stock-names.md) | 종목명(코드) 표기 규칙 |

## FE

| 문서 | 내용 |
|---|---|
| [upgrade-2026-10/03-research-fe.md](../upgrade-2026-10/03-research-fe.md) | FE 조사 |
| [upgrade-2026-10/10-audit-fe.md](../upgrade-2026-10/10-audit-fe.md) | FE 코드 감사 |
| [manual-order-guard.md](../manual-order-guard.md) | 수동 주문 확인 창·상한·LIVE 띠 |

## BE

| 문서 | 내용 |
|---|---|
| [docs/ARCHITECTURE.md](../../docs/ARCHITECTURE.md) | 아키텍처 기준서 |
| [upgrade-2026-10/02-research-be.md](../upgrade-2026-10/02-research-be.md) | BE 조사 |
| [upgrade-2026-10/09-audit-be.md](../upgrade-2026-10/09-audit-be.md) | BE 코드 감사 |
| [refactoring-plan.md](../refactoring-plan.md) | 리팩토링 계획 |
| [architecture-rules.md](../architecture-rules.md) | ArchUnit 의존 규칙 |
| [large-classes.md](../large-classes.md) | 큰 클래스 조사 |
| [value-objects.md](../value-objects.md) | 값 객체 |
| [clock-injection.md](../clock-injection.md) | 주입 Clock |
| [time.md](../time.md) | 시간대 명시 |
| [error-handling.md](../error-handling.md) | 전역 예외 처리(ProblemDetail + ErrorCode) |
| [request-validation.md](../request-validation.md) | 요청 검증 |
| [market-data-port.md](../market-data-port.md) | 시세 조회 포트 |
| [kiwoom-error-codes.md](../kiwoom-error-codes.md) | 키움 오류코드 분류 |
| [order-concurrency.md](../order-concurrency.md) | 주문 동시 갱신 |
| [stale-cancel.md](../stale-cancel.md) | 미체결 타임아웃 취소 |
| [open-order-aware-risk.md](../open-order-aware-risk.md) | 미체결을 아는 RiskGate |
| [risk-state-persistence.md](../risk-state-persistence.md) | 킬스위치·일 손실 영속화 |
| [position-reconcile.md](../position-reconcile.md) | 잔고↔장부 대사 |
| [macro-staleness.md](../macro-staleness.md) | 거시 지표 낡음 처리 |
| [disclosure-blacklist-tx.md](../disclosure-blacklist-tx.md) | 공시 블랙리스트 트랜잭션 |
| [ipo-command-transaction.md](../ipo-command-transaction.md) | IPO 수동 입력 트랜잭션 |
| [audit-reliability.md](../audit-reliability.md) | 감사 기록 신뢰성 |
| [async-notification.md](../async-notification.md) | 비동기 알림 대기열 |
| [alert-digest.md](../alert-digest.md) | 알림 요약·429 재시도 |
| [run-summary-logs.md](../run-summary-logs.md) | 실행 요약 로그 |
| [heartbeat-telegram.md](../heartbeat-telegram.md) | 외부 heartbeat·텔레그램 가동 |
| [market-hours-automation.md](../market-hours-automation.md) | 장중 할 일 자동 실행 |
| [small-fixes-2026-10-02.md](../small-fixes-2026-10-02.md) | Phase 1.9 소규모 정리 |
| [valunivo-telegram-bridge.md](../valunivo-telegram-bridge.md) | VALUNIVO 텔레그램 연결 |
| [platform-stock-control-contract.md](../platform-stock-control-contract.md) | 플랫폼 주식 제어 계약 |
| [platform-stock-control-attempt.md](../platform-stock-control-attempt.md) | 제어 시도 |
| [platform-stock-control-receipt.md](../platform-stock-control-receipt.md) | 제어 접수 |
| [platform-stock-control-result.md](../platform-stock-control-result.md) | 제어 결과 |
| [platform-stock-control-result-ack.md](../platform-stock-control-result-ack.md) | 결과 확인 |
| [platform-stock-control-result-proof.md](../platform-stock-control-result-proof.md) | 결과 증명 |
| [platform-stock-control-verifier.md](../platform-stock-control-verifier.md) | 검증기 |
| [telegram-operator-coordinator.md](../telegram-operator-coordinator.md) | 운영자 명령 조정 |
| [telegram-operator-request-gate.md](../telegram-operator-request-gate.md) | 요청 관문 |
| [telegram-operator-request-store.md](../telegram-operator-request-store.md) | 요청 저장 |
| [telegram-operator-single-attempt.md](../telegram-operator-single-attempt.md) | 단일 시도 |
| [telegram-operator-durable-dispatch.md](../telegram-operator-durable-dispatch.md) | 지속 발송 |
| [telegram-operator-post-lock-guard.md](../telegram-operator-post-lock-guard.md) | 잠금 뒤 보호 |
| [telegram-operator-atomic-stop-link.md](../telegram-operator-atomic-stop-link.md) | 원자적 정지 연결 |
| [telegram-receiver-handover-operator-and-key-design.md](../telegram-receiver-handover-operator-and-key-design.md) | 수신 인계·키 설계 |
| [telegram-receiver-stop-signal-design.md](../telegram-receiver-stop-signal-design.md) | 수신 정지 신호 설계 |
| [telegram-operator-listener.md](../telegram-operator-listener.md) | 운영자 정지 신호 수신기(R16 P2-1, 기본 꺼짐) |
| [platform-stock-control-entry.md](../platform-stock-control-entry.md) | 플랫폼 주식 제어 입구·로컬 재개(R16 P2-2d, 기본 꺼짐, 원장 V32) |

## 인프라

| 문서 | 내용 |
|---|---|
| [docs/RUNBOOK.md](../../docs/RUNBOOK.md) | 운영 절차 |
| [upgrade-2026-10/06-research-infra.md](../upgrade-2026-10/06-research-infra.md) | 인프라 조사 |
| [data-platform-postgres.md](../data-platform-postgres.md) | PostgreSQL 활용 설계(D-15~D-19) |
| [db-switch-timescale.md](../db-switch-timescale.md) | TimescaleDB 전환 |
| [minute-bars-db.md](../minute-bars-db.md) | 분봉 DB |
| [external-data-files.md](../external-data-files.md) | 외부 데이터 파일 |
| [market-data/](../market-data/) | 백테스트 시세 CSV 사본 |
| [backup.md](../backup.md) | DB 백업·복원 리허설 |
| [restore-from-backup.md](../restore-from-backup.md) | 백업에서 복원 |
| [restart-preflight.md](../restart-preflight.md) | 재기동 전 점검 |
| [observability.md](../observability.md) | 헬스·지표 |
| [loopback-binding.md](../loopback-binding.md) | 루프백 바인딩 |
| [http-timeouts.md](../http-timeouts.md) | 외부 호출 타임아웃 |
| [log-secret-masking.md](../log-secret-masking.md) | 로그 비밀 마스킹 |
| [sleep-resume.md](../sleep-resume.md) | 절전·복귀 |
| [pc-migration-2026-10-24.md](../pc-migration-2026-10-24.md) | PC 이전 |
| [pc-recovery-2026-10-24-new-pc.md](../pc-recovery-2026-10-24-new-pc.md) | 새 PC 복구 |

## QA

| 문서 | 내용 |
|---|---|
| [db-integration-test.md](../db-integration-test.md) | DB 통합 시험 |
| [auto-measurement.md](../auto-measurement.md) | 실측 자동화 |
| [phase1-offhours-2026-10-02.md](../phase1-offhours-2026-10-02.md) | Phase 1 장외 묶음·재기동 확인 목록 |
| [upgrade-2026-10/13-log-review-2026-10-01.md](../upgrade-2026-10/13-log-review-2026-10-01.md) | 운영 로그 점검 10/01 |
| [upgrade-2026-10/14-log-review-2026-10-06.md](../upgrade-2026-10/14-log-review-2026-10-06.md) | 10/06 |
| [upgrade-2026-10/15-log-review-2026-10-07.md](../upgrade-2026-10/15-log-review-2026-10-07.md) | 10/07 |
| [upgrade-2026-10/16-log-review-2026-10-13.md](../upgrade-2026-10/16-log-review-2026-10-13.md) | 10/13 |
| [upgrade-2026-10/18-log-review-2026-10-15.md](../upgrade-2026-10/18-log-review-2026-10-15.md) | 10/15 |
| [upgrade-2026-10/19-log-review-2026-10-16.md](../upgrade-2026-10/19-log-review-2026-10-16.md) | 10/16 |
| [upgrade-2026-10/20-log-review-2026-10-21.md](../upgrade-2026-10/20-log-review-2026-10-21.md) | 10/21 |
| [upgrade-2026-10/21-log-review-2026-10-22.md](../upgrade-2026-10/21-log-review-2026-10-22.md) | 10/22 |
| [upgrade-2026-10/22-log-review-2026-10-23.md](../upgrade-2026-10/22-log-review-2026-10-23.md) | 10/23 |

## VALUNIVO 2단계 연결 (VALUNIVO 18·19, 2026-11-06)

VALUNIVO 2단계의 반복 개발·배포 방식은 [VALUNIVO 18](../../../VALUNIVO/aiDoc/상세/공통/18-2단계-애자일-개발-배포-운영.md)을 참고하되, 이 저장소의 `AGENTS.md`(거래 안전·운영 시간 창·모의 서버 우선)가 우선한다. [VALUNIVO 19 4장](../../../VALUNIVO/aiDoc/상세/공통/19-2단계-AI-작업-역량과-지식-고도화.md)이 낸 autoStock 후보 C-1~C-6을 이 저장소 문서와 대조한 결과다(VALUNIVO 구현 639). 판단과 실행의 기준은 이 저장소의 계획·결정 로그이며, 아래는 문서 대조이고 코드 동작을 새로 시험하지 않았다.

| 19 후보 | 이 저장소에 이미 있는 것 | 남는 것 |
|---|---|---|
| C-1 시도한 전략 수 기록·과적합 지표(PBO·DSR) | [PLAN.md](../../PLAN.md) 공통 규율(walk-forward+CPCV·DSR·MDD·trial 기록), [PROGRESS.md](../../PROGRESS.md) 3단계 완료(walk-forward + DSR/PBO 검증 파이프라인), [BACKTEST_REPORT.md](../../BACKTEST_REPORT.md)(trial 전수 기록, Hansen SPA_c 판정), [전략 변경 전후 비교](../strategy-change-performance.md)(결과를 본 뒤 규칙을 바꾸면 새 trial, 표본이 작으면 판정에 쓰지 않음) | 없음 |
| C-2 학습·검증 분할의 제거(purge)·완충(embargo) | walk-forward와 CPCV 이중 검증(PLAN.md, BACKTEST_REPORT.md) | CPCV의 제거·완충 설정값은 이번에 확인하지 않음 |
| C-3 LLM 신호는 모델 지식 기준일 뒤 구간으로만 평가 | [08 전략 조사](../upgrade-2026-10/08-research-trading-strategy.md) 6-1(LLM 시그널 룩어헤드 오염), [05 기획 조사](../upgrade-2026-10/05-research-planning.md)(LLM 시그널 필터는 섀도 기록만), PLAN.md 신호 시점 규칙(룩어헤드 방지) | LLM 신호를 매매 판단에 쓰기 전에 평가 구간 규칙을 이 저장소 결정으로 남길지 |
| C-4 비용 모델(거래세·호가 단위 등)을 기준일 있는 설정으로 | PROGRESS.md 3단계 비용 모델 | 규정 값의 기준일과 원문(KRX·국세청) 대조 |
| C-5 주문 멱등 키와 재기동 때 체결·잔고 대조 | `ClientOrderId` 값 객체(PLAN.md, 주문 이력 화면), 주문 실행 멱등성(PLAN.md 모듈 표), [잔고↔장부 대사](../position-reconcile.md) | 키움 API가 클라이언트 주문 ID를 받는지는 확인하지 않음 |
| C-6 국면 판정은 단순 구간부터 | BACKTEST_REPORT.md C1~C4(SMA200 국면 필터, 국면 OFF 인버스 기각) | 없음 |
