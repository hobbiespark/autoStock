# 11. 실행 계획 — 기존 서비스를 충돌 없이 고도화하는 순서 (2026-10-01)

> 문서 묶음: [00-README](00-README.md) · [01 현황](01-current-state.md) · 조사 [02 BE](02-research-be.md) · [03 FE](03-research-fe.md) · [04 디자인](04-research-design.md) · [05 기획](05-research-planning.md) · [06 인프라](06-research-infra.md) · [07 주식거래①시장·제도](07-research-trading-market.md) · [08 주식거래②전략·LLM](08-research-trading-strategy.md) · 감사 [09 BE](09-audit-be.md) · [10 FE](10-audit-fe.md) · **[11 실행 계획](11-execution-plan.md)** · [12 결정 목록](12-decisions.md)

- 성격: **유지보수 계획**(코딩 규칙 §0) — 우선순위는 ① 사용자 요청 ② 리포의 기존 결정(PLAN ADR-1~14, ARCHITECTURE 설계 규칙 20, RUNBOOK, aiDoc, 결정을 담은 주석·테스트) ③ 코딩 규칙. 기존 결정과 다른 권고는 2절 충돌 해소표에서 처리했다.
- 표기: **확인**(코드·1차 자료) / **추론** / **결정 필요**(→ [12 결정 목록](12-decisions.md)의 D-번호). 이 문서 작성 시점에 빌드·테스트는 실행하지 않았다(**미검증** — 각 작업의 검증 절차로 확인).
- 작업 ID는 `Phase.번호`(예: 0.3), 공통 규칙은 `규약-n`, 결정은 `D-nn`. 결함 근거는 감사 문서 번호(예: BE-P0-3 = [09](09-audit-be.md) P0-3, FE-#1 = [10](10-audit-fe.md) 6절 1번).

---

## 0. 한 장 요약

| Phase | 기간(권고) | 목적 | 핵심 작업 | 사용자 결정 |
|---|---|---|---|---|
| **0 안전 기반** | 10/2~10/16 | 무인 운영의 P0 구멍을 막는다 | 미체결 취소 정상화, 킬스위치·일손실 복원, 블랙리스트 삭제 트랜잭션, 수동 주문 안전장치(BE·FE), 키움 인증·IP·유량 오류 분류, 외부 heartbeat·텔레그램, 백업, 전원 하드닝, 키움 서비스 연속성 | D-02, D-05, D-06, D-09 |
| **1 운영 정확성·관측** | 10/19~11/13 | 백테스트=라이브 정합, 중복 주문·유실 차단, 보이게 만들기 | C3 판단 주기 거래일 기준·영속화, 미체결 인식 RiskGate, 알림 비동기화, 매크로 신선도, 포지션 대사, UNKNOWN 해소 TR, 메트릭·헬스, 감사 신뢰성, 복구 훈련 | D-03, D-07 |
| **2 FE 품질·접근성·디자인** | 10/19~11/20(병행) | 오조작·무음 실패·접근성 불합격 해소, 지원 종료 툴체인 탈출 | Vite 8·TS 6, 린트·테스트·CI 게이트, 접근성 일괄, 토큰·테마·포매터, React 19·recharts 3, 계약 정합, 해시 탭, FE-4 | D-11 |
| **3 전략·LLM** | ADR-15 결정 후 11/2~12/11 | 실계좌 구조 확정, 가설은 분기 1개, LLM은 비매매부터 | ADR-15 확정·코어 운용 규칙, 위성 가설 1개 사전 선언, IPO 파이프라인 개선, LLM 어드바이저 L1(해설)·L2(섀도 기록) | **D-01**, D-04, D-10, D-14 |
| **4 인프라 하드닝** | 11/16~12/11 | 로그인·Gradle 데몬 의존 제거, 보안·보존 | jar 실행·서비스화, graceful 종료, 시크릿·Actions 하드닝, 로그·원장 보존 | D-08, D-13 |
| **5 플랫폼 이관** | 11/16~2027-01-22(브랜치) | OSS 지원 공백 해소 | 3.5에서 선행 정리 → **Boot 4.1 + Modulith 2.1(Gradle 8.14, JDK 21)** → Gradle 9.1+ → JDK 25 | D-12 |
| **6 실전 전환 준비** | 게이트 ② 판정 후 | 자금 안전 | 체크리스트·키 재발급·live 분리 | 게이트 판정 |

**진행 순서 원칙**: Phase 0은 순서대로(안전), Phase 1·2는 병행(서로 다른 파일군), Phase 3은 D-01 결정이 문, Phase 4·5는 장외·브랜치에서. 연말 **변경 동결 12/24~2027-01-04**(12/25·12/31·1/1 휴장, 모의 서버 점검 가능성).

---

## 1. 충돌 방지 규약 (모든 작업 공통 — 번호는 "규약-n"으로 적어 Phase 1 작업 번호와 구분)

### 규약-1. 건드리지 않는 곳
[01 현황 7절](01-current-state.md) 목록을 그대로 따른다 — 백테스트 러너·`*Math`·`KrxTickSize`(trial 재현성), 사전 선언 실험 테스트, 실측 확정 파서·어댑터 필드, `OrderStatus` 전이표(추가만 허용), `common.event.*` 필드 이름·JSON 형식, 환경변수 3단 체계. 이 계획의 어떤 작업도 이들을 **수정하지 않고 옆에 추가**한다.

### 규약-2. 변경 방식
1. **작은 커밋 하나에 목적 하나**, 한국어 한 줄 메시지, AI 작성 표기 없음(CLAUDE.md §2, 코딩 규칙 §14). 커밋·push는 사용자 요청 시(push는 묶음 — aiDoc/claude-memory).
2. **테스트 먼저**(코딩 규칙 §18.1): 지킬 동작을 테스트로 적고 → 구현 → `.\gradlew.bat test` 전체 통과(`ModularityTests`·`ArchitectureRulesTest` 포함).
3. **Expand-Contract**: 스키마·API·설정은 추가 → 사용처 이동 → 옛것 제거(별도 커밋·별도 배포). 이벤트 JSON 형식 변경 금지.
4. **Flyway 번호는 착수 순서대로 부여**(사전 예약 금지 — 번호가 역전되면 기본 `outOfOrder=false`에서 validate 실패). 현재 최종 V16(2026-10-02 — V9 Phase 0.2, V10 종목명, V11 공모 종류, V12 분봉, V13~V16 Phase 1 장외 묶음).
5. **새 동작은 설정 키로 켜고 끈다**: 위험한 새 동작은 기본값을 "현행 유지"로 두고 장외에 켜서 다음 거래일에 확인한다. 새 키 목록은 규약-6.
6. FE 소스를 바꾸면 `npm run build` 산출물(`app/src/main/resources/static`)을 같은 커밋에 넣는다(aiDoc/README 인계 메모).
7. 작업마다 `aiDoc/<주제>.md` 근거 문서(코딩 규칙 §15 9항목)와 `aiDoc/README.md` 목록 한 줄, PROGRESS.md 해당 트랙 한 줄.

### 규약-3. 배포 창과 검증 3단계
| 단계 | 언제 | 무엇 |
|---|---|---|
| ① 빌드 검증 | 언제든 | `.\gradlew.bat test`(기준선: 2026-09-30 573건 통과·16 skip), FE는 `npm run build` |
| ② 장외 기동 스모크 | **평일 16:00 이후 ~ 다음 거래일 08:00**, 또는 주말 | `scripts\stop_autostock.bat` → 재기동 → 기동 로그 체크(토큰 발급·Reconciliation·RUNNING·세션 STANDBY). 주말엔 모의 서버 미운영(실측 2026-09-12)이라 브로커 검증은 ③으로 |
| ③ 장중 실측 | 다음 거래일 | 09:05 판단·주문·체결통보·15:50 리포트 로그 확인 → aiDoc "검증 상태" 기록 |
- **장중 재기동 금지**. 예외: 긴급 수정은 킬스위치 ON 상태에서만 재기동하고 해제는 사람이 확인 후(0.2 이후엔 킬스위치가 재기동에도 유지됨).
- 휴장일: 10/5(월, 개천절 대체)·10/9(금, 한글날)·12/25·12/31(규정 기반 — [07 §7](07-research-trading-market.md)). 휴장일 전날 배포는 ③ 검증이 하루 밀린다.

### 규약-4. 게이트 ② 카운트 보호
C3 동작을 바꾸는 변경(1.1 판단 주기, 0.1 취소 정상화)은 PROGRESS 트랙 C에 "운영 변경 이벤트"로 날짜와 함께 기록한다. 게이트 ②의 연속 무인 운영 카운트를 리셋할지는 **D-03**.

### 규약-5. PC에서의 git 사용 주의 (2026-10-01 교훈)
AI 작업 환경(리눅스 VM)에서 연결 폴더의 git을 조회할 때는 반드시 `git --no-optional-locks ...`만 쓴다. 일반 `git status`는 `.git/index.lock`을 만들고 VM 권한상 지우지 못해 **Windows git을 막는다**(오늘 1회 발생 → 즉시 삭제함). VM에서 보이는 "299개 파일 수정"은 줄바꿈(CRLF) 차이뿐이다(`--ignore-cr-at-eol` 기준 실변경 0 — 확인). 커밋은 Windows 쪽 git으로 한다.

### 규약-6. 새 설정 키 (기본값 = 현행 동작 유지)
| 키 | 기본값 | 작업 | 비고 |
|---|---|---|---|
| `risk.manual-order-max-krw` | 결정값(D-06, 권고 1,000,000) | 0.4 | 수동 주문 1건 금액 상한 |
| `monitor.heartbeat.url` | 빈 값(끔) | 0.7 | Healthchecks.io ping URL — 비밀 취급(.env) |
| `monitor.telegram.resume-confirm` | `true` | 0.7 | `/resume` 2단계 확인 |
| `macrointel.max-staleness-days` | `7` | 1.4 | 지표가 이보다 오래되면 보수 모드 |
| `trading.position-reconcile.enabled` | `false` → 검증 후 `true` | 1.5 | 잔고↔장부 주기 대사(알림만) |
| `analysis.llm.enabled` / `.model` / `.monthly-budget-usd` | `false` / 결정값 / 결정값 | 3.6 | D-10 |

---

## 2. 충돌 해소표 — 조사 권고 vs 기존 결정

| # | 조사 권고 | 기존 결정·현행 | 처리 | 근거 |
|---|---|---|---|---|
| 1 | JNA 절전 차단 제거, powercfg로 대체([06](06-research-infra.md) 1.3) | 2026-09-30 사용자 결정·구현(`SleepGuard`, `keep-awake`) | **유지** + powercfg 병행(심층 방어). 제거는 미니PC 이전(D-08) 때 재검토 | 코딩 규칙 §0, PROGRESS C2-5 |
| 2 | CI에서 키움 시크릿 제거([06](06-research-infra.md) 5.1) | 모의 키(`KIWOOM_MOCK_G_*`)로 CI 스모크, LIVE는 CI 금지 | **유지**(모의 키는 실자금 불가) + Actions 하드닝(4.3). 단 허용 IP 필수 규칙 때문에 러너 IP에서 `8040/8050`이 나면 스모크를 수동 트리거로 이동 | ci.yml 주석, [07 §1-8](07-research-trading-market.md) |
| 3 | 초안 "JDK 25 먼저" | — | **정정**: Boot 4.1 → Gradle 9.1+ → JDK 25 | [02](02-research-be.md) 보강 |
| 4 | `WebClient.block()` → RestClient 즉시 전환([02](02-research-be.md) 5절) | `KiwoomRestClient`의 429/1700/8005 규칙은 실측 확정 | **보류** — 실측 경로 재작성 위험 대비 이득이 작다. Boot 4.1에선 `spring-boot-starter-webclient`로 WebClient 유지. 5.4에서 재평가 | 01 §7, 코딩 규칙 §0 |
| 5 | 라우터 도입(react-router 8) | ADR-10 "라우터 도입 시 react-router 표준", 코드 주석은 번들 이유로 보류 | **해시 기반 탭(라이브러리 없음)**을 권고안으로 D-11 | [03](03-research-fe.md) 1.5, [10](10-audit-fe.md) #16 |
| 6 | 차트 라이브러리 교체 | ADR-10 "차트는 recharts" | **recharts 3 승급만**(교체 안 함) | ADR-10 |
| 7 | 서킷브레이커 도입 | P3 표 "유지(미도입)" | **유지**. 1.7 메트릭으로 반복 장애가 실측되면 재검토 | refactoring-plan P3 |
| 8 | 구조화 JSON 로그 | P3 표 "평문 유지" | **유지** | refactoring-plan P3 |
| 9 | 감사 실패 알림(B4) | 사용자 결정 2026-09-30 "나중에" | 카운터만 추가(1.8), **알림은 보류 유지** | aiDoc/README 인계 메모 |
| 10 | `risk.stopLossPct/takeProfitPct` 미사용 키 삭제([09](09-audit-be.md) P2-13) | ADR-7(E4 익절/손절 paper A/B)이 사용 예정 | **삭제 안 함** — Javadoc에 "E4에서 사용 예정" 표기만 | PLAN ADR-7 |
| 11 | 볼타겟을 "MDD 장치로만" 재선언([08](08-research-trading-strategy.md) 2-1) | trial 규율(사전 선언·분기 1개) | 새 trial 후보로만 등록(3.3) — 기존 C3 수정 아님 | PROGRESS 3절 |
| 12 | Prometheus+Grafana | ADR-5 "Actuator 메트릭", 크리프 방지 | **보류**(D-13) — 1.7 메트릭·헬스 먼저 | [06](06-research-infra.md) 9절 |
| 13 | `@ApplicationModuleListener`로 감사 전환 | 발행자가 트랜잭션 밖에서 이벤트를 발행(확인) | **전환 금지** — `@TransactionalEventListener` 기반이라 트랜잭션 없는 발행은 **조용히 누락**된다(추론, 프레임워크 동작). Javadoc 정정·카운터로 대체(1.8) | [09](09-audit-be.md) P1-7 |

---

## 3. Phase 0 — 안전 기반 (10/2~10/16)

실행 순서: **0.0 → 0.1 → 0.2 → 0.3 → 0.4+0.5(같은 배포) → 0.6 → 0.7 → 0.8 → 0.9 → 0.10 → 0.11**. 0.8·0.9·0.10은 사용자 작업 위주라 코드 작업과 병행 가능.

> **진행(2026-10-01)**: 0.0(휴장일 SQL)·0.1~0.8·0.10·0.11의 코드·스크립트·문서를 PC 작업 트리에 반영했다(커밋 전, 컨테이너 전체 테스트 643건 통과). 남은 것은 사용자 작업(0.7 봇·Healthchecks 발급, 0.8 스케줄 등록·복원 리허설, 0.9 전원 설정, 0.10 포털 확인·고객센터 문의)과 다음 거래일 실측이다 — PROGRESS 트랙 C C2-7. 계획 대비 조정은 각 절의 "구현 조정"에 적었다.

### 0.0 사전 점검 (착수 전, 0.5일)
| 확인 | 방법 | 통과 기준 |
|---|---|---|
| 작업 트리 | Windows에서 `git status`(CRLF 차이만 보이면 `git config core.autocrlf true` 확인) | 실변경 없음, 미푸시 2커밋 처리 방침 결정 |
| 테스트 기준선 | `.\gradlew.bat test` | 573건 통과(±신규), 실패 0 |
| 휴장일 DB | `SELECT * FROM market_holidays WHERE holiday_date BETWEEN '2026-09-24' AND '2026-12-31' ORDER BY 1;` | 10/5·10/9·12/25 존재, 12/31은 MANUAL 등록(없으면 `scripts/sql`로 추가 — RUNBOOK 관례), 9/28 개장 여부를 9/28 로그로 확인 |
| 키움 포털 | openapi.kiwoom.com → 계좌 App Key 관리 | 서비스 상태(해지 여부), **허용 IP 목록에 현재 공인 IP 포함**, 등록 수(≤10) |
| 절전 차단 | 장중 관리자 PowerShell `powercfg /requests` | SYSTEM에 java.exe (PROGRESS C2-5 남은 체크) |
| 운영 로그 | 9/30~10/2 `app/logs` | 09:05 C3 판단·15:50 리포트 정상, `8005`·`1700` 빈도 |

### 0.1 미체결 타임아웃 취소 정상화 — BE-P0-3 (1일)
- **문제(확인)**: `StaleOrderCanceller`가 `findByStatusIn(List.of(SUBMITTED))`만 본다(`trading/StaleOrderCanceller.java:66`). LIVE에선 WS "접수" 통보로 곧 ACCEPTED가 되므로 취소가 사실상 동작하지 않고, 취소 성공 뒤 CANCELLED 확정이 없어 CANCEL_REQUESTED에 남는다. Reconciliation도 UNKNOWN·SUBMITTED만 본다(`ReconciliationService.java:117-118`).
- **변경**(같은 모듈 안, 기존 코드 재사용 — SSOT):
  1. `TradingService`의 취소 흐름(`onCancelRequest` → `isCancellable` → CANCEL_REQUESTED → `cancelOrder(…, 0)` → `applyCancelOutcome`)을 `requestCancel(String clientOrderId, String requestedBy)` 메서드로 추출하고 `onCancelRequest`는 이를 호출(동작 동일).
  2. `StaleOrderCanceller`: 대상 상태를 `SUBMITTED, ACCEPTED, PARTIALLY_FILLED`로 넓히고, 브로커를 직접 부르던 `cancelOne`을 `tradingService.requestCancel(id, "stale-timeout")` 호출로 교체(기준 시각은 기존대로 `updatedAt` — 부분체결은 "마지막 체결 후 5분" 정체 기준, aiDoc에 명시).
  3. `ReconciliationService.scheduledReconcile` 대상에 `ACCEPTED`, `CANCEL_REQUESTED` 추가 — 미체결 목록(ka10075)에 없고 브로커 기록이 없으면 **UNKNOWN 유지 + 경고**(단정 금지 — 기존 원칙).
- **구현 조정(2026-10-01, `aiDoc/stale-cancel.md`)**: ① `requestCancel`에 세 번째 인자(최신 주문에 대한 "아직 정체 중인가" 조건)를 더했다 — 목록 조회 뒤 체결이 먼저 반영된 주문을 취소하지 않기 위해서다. ② 대사에는 **`CANCEL_REQUESTED`만** 추가했다 — 정체된 ACCEPTED는 타임아웃 취소가 처리하고, 취소 실패는 UNKNOWN으로 대사에 들어온다. ③ `CANCEL_REQUESTED`인데 1분이 지나도 미체결 목록에 남아 있으면 SUBMITTED(체결분 있으면 PARTIALLY_FILLED)로 되돌려 다시 취소하게 했다.
- **테스트**: `StaleOrderCancellerTest` — ACCEPTED·PARTIALLY_FILLED 5분 경과 시 취소 요청, 경과 전 무시, 장외 무시, SIM 무시, 취소 성공 시 CANCELLED 확정(TradingService 경유). `TradingServiceTest` — `requestCancel` 추출 후 기존 취소 테스트 그대로 통과. `ReconciliationServiceTest` — ACCEPTED·CANCEL_REQUESTED 포함.
- **위험**: 취소가 실제로 동작하기 시작한다(의도). C3 지정가(최유리 호가) 주문이 5분 안에 안 잡히면 취소된다 → 다음 판단(21거래일 뒤)까지 미진입 가능. **`execution.stale-order-timeout`(현행 5m)을 유지할지는 D-05에 함께 묻는다.**
- **배포**: 장외. **실측**: 다음 거래일 09:05 주문의 상태 전이 로그, 미체결 없음 확인. **롤백**: 커밋 되돌림(스키마 변경 없음).

### 0.2 킬스위치·일 손실·원가 장부 영속화와 재기동 복원 — BE-P0-2 (2일) · D-05
- **문제(확인)**: `KillSwitch`는 `AtomicBoolean`(`risk/KillSwitch.java:38`), `DailyPnlTracker`는 메모리 맵·누계 → 재기동(auto-start 포함) 시 비상 정지 해제·일 손실 0. `PositionRestored` 구독자는 `PositionBook`뿐이라 복원 포지션 매도의 실현손익이 계산되지 않는다.
- **변경**(risk 모듈 소유 테이블 — risk가 이미 `DisclosureBlacklistEntity`를 소유하는 선례):
  1. 마이그레이션(다음 번호, 예: V9): `risk_state`(단일 행: `kill_switch_engaged BOOLEAN`, `kill_switch_reason TEXT`, `changed_at TIMESTAMPTZ`, `changed_by TEXT`, `version BIGINT`), `risk_daily_pnl`(`trade_date DATE PK`, `realized_pnl NUMERIC(19,4)`, `updated_at TIMESTAMPTZ`).
  2. `KillSwitch.engage/release`: 상태 변경 후 저장(동기, 1행 upsert). 기동 시 `ApplicationReadyEvent`에서 복원 — engaged였으면 **engaged로 시작**하고 `KillSwitchChanged(engaged, "재기동 복원: <원 사유>")` 발행(텔레그램·대시보드 반영). 해제는 기존대로 사람(대시보드·`/resume`)만.
  3. `DailyPnlTracker`: 실현손익이 바뀔 때마다 오늘 행 upsert, 기동 시 오늘(KST) 행으로 누계 복원. `PositionRestored`를 구독해 원가 장부(`lots`)를 평단으로 시드(평단 미상 `null`이면 시드 안 함 — 기존 경고 유지).
  4. `TradingAutoStarter` Javadoc 정정("킬스위치가 켜진 채 재시작해도 안전"이 이제 사실이 됨).
- **테스트**: 단위 — engage 후 재생성 시 engaged 복원·이벤트 발행, release 저장, 날짜가 다른 PnL 행은 무시, PositionRestored 시드. DB(`PostgresDataJpaTest`) — 저장소 upsert·`@Version`.
- **위험**: 저장 실패 시 동작 — 킬스위치는 **메모리 상태 우선(fail-safe: engage는 저장 실패해도 유지)** + ERROR 로그. **배포**: 장외. **실측**: 장외에 킬스위치 ON → 재기동 → ON 유지 확인 → 해제. **롤백**: 코드 되돌림(테이블은 남겨도 무해 — 전진 전용).

### 0.3 공시 블랙리스트 삭제 트랜잭션 — BE-P0-1 (0.5일)
- **변경**: `DisclosureBlacklist.remove/releaseExpired`에 `@Transactional`(또는 리포지토리 메서드에 `@Transactional @Modifying` JPQL). 캐시 갱신은 커밋 후.
- **테스트 먼저**: `PostgresDataJpaTest` 기반 `DisclosureBlacklistRepositoryDbTest` — 삭제가 실제 반영되는지(현행 코드로 **실패하는 테스트를 먼저** 확인 → 추론 검증). **롤백**: 되돌림.

### 0.4 수동 주문 안전장치(BE) — BE-P1-6 (1일) · D-06
- **변경**:
  1. `TestSignalRequest.quantity`를 필수로(`@NotBlank` + 기존 정규식) — **FE 0.5와 같은 커밋·같은 배포**(FE는 같은 jar로 서빙되므로 버전 불일치 없음).
  2. `RiskGate` 수동 분기(`strategyId=dashboard-manual`)에 **1건 금액 상한** `risk.manual-order-max-krw` 검사 추가 — 위치는 수량 확정 직후, 호가 보정 전(기존 순서 "사이징 → 지정가 → 슬롯" 유지, 거부 시 슬롯 미사용 규칙 보존). 거부 사유는 기존 `SignalDecision(REJECTED)`로 기록.
  3. 응답 `TestSignalResponse`에 `executionMode` 추가(추가 필드 — 계약 확장).
- **테스트**: `RequestValidationTest`(수량 공란 400), `RiskGateTest`(상한 초과 거부·슬롯 미사용, 상한 이내 통과). **롤백**: 되돌림(설정 키는 남아도 무해).
- **구현 조정(2026-10-01, `aiDoc/manual-order-guard.md`)**: 금액 상한은 **수동 매수에만** 적용했다 — 매도는 노출을 줄이는 방향이고 보유분 수동 청산을 막지 않기 위해서다(매도는 기존 보유량 캡). 화면이 같은 상한으로 미리 막도록 `SystemStatusView.manualOrderMaxKrw`를 더했다.

### 0.5 수동 주문·위험 동작 UX(FE) — FE-#1~#5 (1.5일)
- **변경**(현 툴체인 그대로 — 테스트 도구는 2.2에서 도입, 이번엔 수동 검증):
  1. `TestSignalCard`: 기본 종목 `''`, 수량 필수(`inputMode="numeric"`, BE와 같은 정규식), 발행 전 네이티브 `<dialog>` 확인(종목명·방향·기준가·수량·**예상 금액**·실행 모드), LIVE면 빨간 배너 + 종목코드 재입력.
  2. 킬스위치 **해제**·매매 **시작**에 확인 단계(비상 정지는 1클릭 유지 — 안전 방향).
  3. `api.ts postJson`: `application/problem+json` 본문(`title/detail/code/errors/currentStatus`) 파싱해 `ApiError`로 throw, 명령 카드 3곳(`OperationCard`·`KillSwitchCard`·`TestSignalCard`)에 오류 표시(`role="alert"`).
  4. `executionMode`를 헤더·폼에 전달, 헤더 부제 "SIM 모드 검증용" 하드코딩 제거, LIVE면 상단 띠.
- **검증**: `npm run build` → 장외 기동 → 브라우저에서 확인 흐름·오류 표시(400 유도: 수량 공란)·LIVE 표시. **롤백**: 되돌림(정적 산출물 포함).

### 0.6 키움 유량·인증·IP 오류 분류 — BE-P1-9·P1-10 (1일)
- **근거(확인)**: 공식 오류코드 `1700/1701/1702`(유량), `8005`(토큰 무효), `8010`(발급 IP ≠ 요청 IP), `8001/8002`(키 검증 실패), `8030/8031`(실전/모의 구분), `8040/8050/8103`(단말기 인증 실패). 현행은 `[1700`·`[8005`만 처리(`kiwoom/KiwoomRestClient.java:152-159`).
- **변경**(기존 규칙은 그대로 두고 **추가만** — 01 §7 준수):
  1. `isRateLimitLogicError`: `[1701`·`[1702` 추가(같은 1.1초·4회).
  2. `isTokenRejected`: `[8010` 추가 — 인증 단계 거절이라 요청이 처리되지 않았으므로 8005와 같은 근거로 1회 재발급·재시도(주문 중복 없음).
  3. 새 이벤트 `common.event.BrokerAuthFailure(code, message, host, at)`(추가만) — `TokenManager` 발급 실패와 `KiwoomRestClient`에서 `8001/8002/8030/8031/8040/8050/8103` 감지 시 발행. `monitor`의 리스너가 **P1 텔레그램 알림**(같은 코드 10분 억제) + 안내 문구: "① 허용 IP 목록에 현재 공인 IP 포함? ② App Key 상태 ③ 3개월 미접속 자동 해지 여부(openapi.kiwoom.com)".
- **테스트**: `KiwoomRestClientTest` 1701/1702 재시도·8010 1회 재시도·8040 즉시 실패+이벤트, `TokenManagerTest` 8001 이벤트. **롤백**: 되돌림.
- **구현 중 추가 발견·수정(2026-10-01, `aiDoc/kiwoom-error-codes.md`)**: ① 주문 응답 타임아웃(15초)이 `KiwoomApiException`이라 `KiwoomBrokerAdapter`가 "명시 거부"로 번역 → REJECTED 종결·대사 누락(잠복 결함) → `KiwoomTimeoutException`으로 구분해 UNKNOWN + 대사. ② `TokenManager`가 token 부재를 return_code보다 먼저 봐 발급 실패 원인 코드를 잃음(PROGRESS C2-3 ⑫에 완료로 적혀 있었으나 리포에 없었음) → 순서 교정.

### 0.7 외부 heartbeat + 텔레그램 가동 (1일 + 사용자 작업) · D-09
- **사용자 작업**: BotFather로 봇 생성 → `.env`에 `TELEGRAM_BOT_TOKEN/CHAT_ID`(PROGRESS A3), Healthchecks.io 무료 계정 → 체크 1개(스케줄: Cron `* 9-15 * * 1-5`, 시간대 Asia/Seoul, grace 3분 — 휴장일은 전날 Pause, 다음 핑에 자동 재개. 2026-10-01 Healthchecks 문법으로 정정) + Telegram 통합([공식](https://healthchecks.io/integrations/telegram/)) → ping URL을 `.env`의 `MONITOR_HEARTBEAT_URL`로.
- **변경**:
  1. `monitor.HeartbeatPinger`: `@Scheduled(fixedDelay = 60s)`, `MarketSession`이 ACTIVE이고 URL이 있을 때만 GET(본문 없음 — 외부로 나가는 정보는 "살아 있음"뿐). 실패는 WARN 1줄(재시도 없음 — 다음 주기).
  2. `TelegramCommandPoller`: `/resume`에 2단계 확인(무작위 4자리 코드 응답 → 60초 안에 `/resume 1234`) — PLAN A3 "위험 명령 2단계 확인". `/stop`은 1단계 유지. `fixedRate` → `fixedDelay`(BE-P2-7, 절전 복귀 폭주 방지 — 9/23 결정과 같은 이유).
  3. `start_autostock.bat`에 `--monitor.telegram.enabled=true` 추가(키가 있을 때).
- **테스트**: `HeartbeatPingerTest`(ACTIVE만, URL 없으면 무동작), `TelegramCommandPollerTest`(코드 불일치·만료 시 해제 안 됨). **실측**: 장중 앱을 일부러 3분 정지 → 텔레그램 수신. **롤백**: URL 비움 / enabled=false.

### 0.8 DB 백업·복원 리허설 (0.5일 + 사용자 작업)
- **변경**: `scripts/backup_db.ps1` — `docker exec autostock-db pg_dump -U autostock -Fc autostock` → `D:\backup\autostock\autostock_yyyyMMdd_HHmm.dump`(경로는 설정), 보존: 일 14개 + 월말 12개. `scripts/restore_check.ps1` — 임시 컨테이너(`postgres:16-alpine`)에 `pg_restore` 후 주요 테이블(`orders`, `event_store`, `signal_decisions`, `daily_performance`, `ipo_deals`) 행 수 대조.
- **사용자 작업**: 작업 스케줄러에 평일 16:30 등록(15:45 분봉·15:50 리포트 뒤), 오프사이트 복사 위치 결정(선택 — OneDrive 등). 월 1회 `restore_check`.
- **완료 기준**: 복원 리허설 1회 성공 기록(RUNBOOK). **롤백**: 스케줄 해제.

### 0.9 Windows 전원·업데이트 하드닝 (사용자 작업, 0.5일)
- `powercfg /change standby-timeout-ac 0`, `powercfg /change hibernate-timeout-ac 0`, 네트워크 어댑터 "전원 절약을 위해 끄기" 해제, Windows Update **사용 시간 06:00~24:00**, 업데이트는 주말 수동 설치(Home 에디션은 그룹 정책 부재 — 에디션 확인). JNA `SleepGuard`는 **유지**(2절 #1).
- RUNBOOK 5절 "장중 절전 방지"에 위 명령과 확인 절차 추가.

### 0.10 키움 서비스 연속성 (0.5일) · D-02
- **근거(확인)**: 허용 IP 필수(10개), 공인 IP 변경 시 `8010`; **3개월 미접속 시 매월 첫 영업일 자동 해지, 접속 기준은 실서버 — 모의만 쓰면 해지될 수 있음**([07 §1-8](07-research-trading-market.md)).
- **변경**: RUNBOOK 6절에 증상 행 추가 — `8010`(IP 변경 → 현재 IP 등록), `8040/8050/8103`(미등록 단말), `8001` 반복(키 상태·해지 확인·재신청). 달력 알림: 매월 1영업일 전 "실서버 접속 기록 확인".
- **D-02 결정에 따라**: (권고안) 월 1회 실서버 토큰 발급·즉시 폐기(`au10001`→`au10002`, 주문 없음)를 수동 또는 스크립트로 — LIVE 키 사용 범위를 "토큰 발급 점검"으로 한정한다는 예외를 PROGRESS 5절(보안 메모)에 명시. 접속 인정 기준(토큰 발급만으로 충분한지)은 **미확인 → 키움 고객센터 확인** 후 확정.

### 0.11 문서 반영 (0.5일)
PROGRESS(트랙 C에 Phase 0 항목), RUNBOOK(0.7~0.10), aiDoc(작업별 근거 문서 6개 이상 + README 목록), 사고 플레이북 3종(절전·재부팅 / 인증·IP·해지 / 백업·복원) — 템플릿은 [06 §8](06-research-infra.md).

**Phase 0 완료 기준**: BE-P0 3건·BE-P1-6·9·10 해소, 외부 heartbeat 실측 1회(의도적 중단 → 알림), 백업 복원 리허설 1회, 키움 포털 상태 확인 기록.

---

## 4. Phase 1 — 운영 정확성·관측 (10/19~11/13)

실행 순서: **1.1 → 1.2 → 1.3 → 1.4 → 1.5 → 1.6(장중 실측 필요) → 1.7 → 1.8 → 1.9 → 1.10**. 1.6은 장중 실측 일정에 맞춰 앞당길 수 있다.

### 1.1 C3 판단 주기를 거래일 기준으로 + 마지막 판단일 영속화 — BE-P1-1 (1.5일) · D-03
- **문제(확인)**: Javadoc은 "21영업일"(`C3LiveStrategy.java:38`)인데 구현은 `last.plusDays(21)` 역일(`:370`). 백테스트는 21봉(`TimeSeriesMomentumStrategy.java:63`). 게다가 `lastDecisionDate`가 메모리 맵이라(`:109`) **재기동할 때마다 전 종목이 "첫 판단"으로 처리**되어 다음 09:05에 재판단한다(추론 — `last == null → true`, `:369-370`). 9월 재기동이 여러 번이었으므로 라이브 C3 시계열은 설계보다 판단이 잦았다.
- **변경**(판단식 `*Math`는 손대지 않음):
  1. `market.MarketCalendarService`에 `int tradingDaysBetween(LocalDate fromExclusive, LocalDate toInclusive)` 추가(공개 API 추가만, `isTradingDay` 재사용).
  2. `C3LiveStrategy.isDecisionDay`: `tradingDaysBetween(last, today) >= decisionIntervalDays`.
  3. 마이그레이션(다음 번호): `strategy_state(strategy_id, symbol, last_decision_date, updated_at, PK(strategy_id, symbol))` — strategy 모듈 소유. 판단 성공 시 저장, 기동 시 로드.
- **테스트**: 휴장일을 낀 21거래일 경계(예: 9/24~26 추석·10/5·10/9 포함), 재기동 후 로드 시 재판단 안 함, 데이터 부족 스킵 시 미갱신(기존 규칙).
- **운영 영향**: 판단 빈도가 백테스트와 같아진다. PROGRESS에 "운영 변경 이벤트" 기록, 게이트 ② 카운트 처리 D-03. **롤백**: 되돌림(테이블 무해).

### 1.2 RiskGate가 미체결 주문을 안다 + 일련번호 원자화 — BE-P1-2·P1-3 (1.5일)
- **변경**:
  1. risk에 포트 `risk.OpenOrderQuery`(`Set<StockCode> symbolsWithOpenBuy()`, `int openBuyCount()`) 정의 → trading의 `OpenOrderQueryAdapter`가 `OrderRepository`로 구현(상태: SUBMITTING·SUBMITTED·ACCEPTED·PARTIALLY_FILLED·UNKNOWN). 의존 방향 trading→risk(순환 없음 — `EquitySource` 선례와 같은 모양).
  2. `RiskGate` 매수 분기: "보유 중이면 추가 매수 금지" 검사에 **미체결 매수 종목**을 포함, 동시 보유 한도에 미체결 매수 수 포함. 단계 순서는 유지.
  3. `DailyLimitTracker.tryAcquireOrderSlot()`이 `OptionalInt`(획득한 일련번호)를 반환 → `RiskGate`가 그 번호로 `ClientOrderId` 생성(읽기-쓰기 분리 제거).
- **테스트**: 미체결 매수 존재 시 같은 종목 매수 거부, 2스레드 동시 슬롯 획득 시 번호 중복 없음, 일 한도 경계. **롤백**: 되돌림.

### 1.3 부수효과 리스너 분리 — BE-P1-5 (0.5일)
- `TradeNotificationListener`의 알림 메서드를 `@Async`(가상 스레드 기본 실행기 — `AsyncConfig`)로. **순서가 중요한 리스너(`PositionBook`, `DailyPnlTracker`, `SlippageTracker`, `OrderNoticeHandler`)는 동기 유지**. 알림 순서가 바뀔 수 있음을 aiDoc에 명시.
- **테스트**: 리스너가 발행 스레드를 막지 않는지(느린 Notifier 목으로). **실측**: 체결 시 WS PING 에코 지연 로그 없음.

### 1.4 매크로 지표 신선도 + 지표 ID 단일화 — BE-P1-8·P2-14 (0.5일)
- `MacroGuard`가 지표별 마지막 `timestamp`를 보관, `macrointel.max-staleness-days`(기본 7) 초과 시 "알 수 없음" → **보수 모드(매수만 금지, 기존 의미)** + 하루 1회 WARN 알림. indicatorId 상수는 macrointel의 공개 상수를 참조하도록 일원화(risk→macrointel 기존 의존 범위 안).
- **테스트**: 경계(7일/8일), 추석 연휴 같은 휴장 기간의 ECOS 미갱신 시나리오.

### 1.5 잔고 타입 정리 → 포지션 주기 대사 — BE-P2-1 → P1-4 (1.5일)
1. `execution.BrokerHolding(StockCode, long quantity, Price avgPrice)` record 도입, `BrokerBalance.holdings`를 타입으로(어댑터에서 실측 필드 `rmnd_qty`·`pur_pric` 변환 — 필드 규칙은 불변). `PositionRestorer` 수정.
2. `ReconciliationService.scheduledReconcile`(5분, ACTIVE만)에 **잔고↔`PositionBook` 비교** 추가 — 불일치 시 **알림·메트릭만**(자동 교정은 하지 않음 — 결정 전까지). 설정 `trading.position-reconcile.enabled`(기본 false → 장외 검증 후 true).
- **테스트**: 일치·수량 불일치·장부에만 있음·브로커에만 있음. **호출량**: kt00018 5분 1회(모의 TR당 1회/초 한도 내).

### 1.6 UNKNOWN 자동 해소용 TR 실측·구현 — BE-P2-3·P2-2, R4(b) (장중 1회 + 1.5일)
- **장중 실측**(모의): ① `ka10076`(체결 — `stex_tp` 0/1/2) ② `kt00007`(계좌별주문체결내역상세 — `dmst_stex_tp` %/KRX/NXT/SOR) ③ `ka10075` 미체결 원소 필드(매도수 구분 필드 확정 — 현재 `trde_tp="보통"` 오해석). 방법: 현재가보다 크게 낮은 지정가 1주 매수로 미체결 생성 → 조회 → 취소. 모의 미지원이면 `8104`(확인 후 기록).
- **구현**: `ReconciliationService.probeFillStatus` 실구현(주문번호로 체결 여부·수량 확정), brokerOrderId 없는 UNKNOWN은 당일 주문내역에서 종목·수량·시각으로 매칭 **후보만 제시(자동 확정 금지)**. 응답 원문은 `docs/measured/tr_probe_YYYYMMDD_*.json`에 저장(관례).
- **테스트**: 실측 픽스처 기반 파서 테스트.

### 1.7 관측성 — 헬스·메트릭·OTR (1.5일)
- `HealthIndicator` 3개: `kiwoomWs`(ACTIVE인데 미연결·미로그인이면 DOWN, STANDBY면 UP+detail), `kiwoomAuth`(마지막 토큰 발급 결과), `killSwitch`(UP + detail engaged — 킬스위치는 장애가 아님).
- 메트릭: 게이지 `risk.killswitch.engaged`, `orders.unknown.count`, `orders.cancel_requested.count`, `market.ws.last_message_age.seconds`; 카운터 `kiwoom.ratelimit.retry{code}`, `kiwoom.auth.failure{code}`, `reconcile.failure`, `audit.write.failure`.
- **OTR 기록**: `daily_performance`에 `submitted_count`·`cancelled_count` 컬럼 추가(다음 번호 마이그레이션, 기본 0) — 15:50 스냅샷이 당일 주문 테이블에서 계산. 목표치 없이 기록만(금융위 HFT 규율 검토 대비 — [07 §4-1](07-research-trading-market.md)).
- **테스트**: 각 인디케이터 상태 전이, 스냅샷 계산.

### 1.8 감사 신뢰성 — BE-P1-7·P2-12 (0.5일) · D-07
- `EventAuditListener` Javadoc의 "Modulith 발행 로그가 유실을 보완" 문구 정정(현재 발행 로그는 쓰이지 않음 — 2절 #13).
- 감사 저장 실패 카운터 `audit.write.failure`(알림은 B4 결정대로 보류).
- `schema_version`: 이벤트 타입별 버전 표(현재 `Signal`=2, 나머지 1)를 audit에 두고 기록(과거 행은 그대로).
- **D-07**(권고 a): `event_publication`에 널 허용 3컬럼(`status TEXT`, `completion_attempts INT`, `last_resubmission_date TIMESTAMPTZ`) 추가 — Modulith 2 이관 대비 Expand(1.4에서 무해). 이 작업은 5.0과 같은 것이므로 **둘 중 먼저 오는 쪽에서 한 번만**.

### 1.9 소규모 정리 (1일, 커밋 여러 개)
| 근거 | 변경 |
|---|---|
| BE-P2-8 | `DailyReportScheduler`: 휴장일·SIM이면 `balance()` 생략 |
| BE-P2-9 | `quote/{symbol}`·`orders/{id}/cancel` 경로 변수 형식 검증(StockCode 패턴, ClientOrderId 포맷) → 400 |
| BE-P2-11 | `OrderHistoryController` 기간 경계를 KST 날짜로 |
| BE-P2-13 | `stopLossPct/takeProfitPct` Javadoc에 "ADR-7 E4 사용 예정" 표기(삭제 안 함) |
| BE-P2-16 | `TokenManager`의 `ZoneId.of("Asia/Seoul")` → `MarketConstants.KST` |
| BE-P2-17 | `DailyLimitTracker.rollDayIfNeeded` 원자화(1.2와 같은 커밋 가능) |
| BE-P2-20 | `KiwoomWebSocketClient`에 `afterConnectionClosed`/`handleTransportError` 로그(종료 코드·사유) — 동작 불변 |

### 1.10 복구 훈련 1회 (0.5일, 장외) — PROGRESS C3
체크리스트([04 §8.2](04-research-design.md) 와이어프레임 참고): 킬스위치 발동 → 대시보드·텔레그램 확인 → 재기동 → **킬스위치 유지 확인(0.2)** → Reconciliation 로그 → 토큰 재발급 → 킬스위치 해제(`/resume` 2단계) → 소요 시간 기록. 결과를 RUNBOOK·PROGRESS에.

**Phase 1 완료 기준**: BE-P1 전부 해소(P1-6·9·10은 Phase 0), UNKNOWN 자동 해소 경로 실측, 헬스·메트릭 노출, 복구 훈련 1회, 30영업일 SLO 카운트 시작([05 §6](05-research-planning.md)).

---

## 5. Phase 2 — FE 품질·접근성·디자인 (10/19~11/20, Phase 1과 병행)

실행 순서: **2.1 → 2.2 → 2.3 → 2.4 → 2.5 → 2.6 → 2.7 → 2.8 → 2.9**. 모든 FE 작업은 BE 코드와 파일이 겹치지 않는다(2.7·2.9의 BE 부분 제외). 빌드는 작업 환경(Node 22.22 확인)에서 `npm ci && npm run build`, CI는 2.3부터.

| # | 작업 | 근거 | 핵심 변경 | 검증 | 기간 |
|---|---|---|---|---|---|
| 2.1 | 툴체인 승급(React는 유지) | Vite 5 지원 종료 | Vite 8.3 + `@vitejs/plugin-react` 6.1 + **TS 6.0.x**(tsconfig: `types:["vite/client"]` 명시, `baseUrl` 미사용 확인) — typescript-eslint가 TS <6.1만 지원(확인)이라 7.0은 보류 | 빌드 성공, 번들 크기 비교(기록), 화면 수동 확인 | 0.5일 |
| 2.2 | 린트·테스트 기반 | FE-#13·#14 | **ESLint 9 flat** + typescript-eslint 8.71 + react-hooks 7.1 + jsx-a11y 6.10(ESLint 10 미지원 — 확인), Prettier 3; **Vitest 5** + RTL 16 + jsdom. 첫 테스트: TestSignal 확인 흐름·수량 필수(0.5 회귀 방지), `api.ts` ProblemDetail 파싱, `OperationCard` 상태별 disabled, `IpoDdayBadge` | `npm run lint/typecheck/test` 통과 | 1.5일 |
| 2.3 | CI FE 잡 | FE-#12 | `ci.yml`에 job 추가: setup-node 22 → `npm ci` → lint·typecheck·test·build → `git diff --exit-code app/src/main/resources/static`(커밋 산출물 = 소스 보장). 기존 Gradle 잡은 불변 | CI 초록 | 0.5일 |
| 2.4 | 접근성 일괄 | FE-#7~#10, KWCAG 2.2 | 모든 입력 `<label>`, `<header>/<main>/<section aria-labelledby>`, 표 `caption`·`scope`, `span onClick`·`div onClick` → `button`(`aria-selected`·`aria-expanded`·`aria-pressed`), 탭 `role="tablist"`, 오류 `role="alert"`, 로딩 `role="status"`, 이벤트 피드 신규 요약 `aria-live="polite"`(일시정지 연동), 대비(버튼 글자 어둡게·`--line` 3:1), `prefers-reduced-motion` | jsx-a11y 0건, 키보드만으로 전 기능 조작, 대비 재계산 | 1.5일 |
| 2.5 | 디자인 토큰·테마·포매터 | FE-#11·#18, [04 §4](04-research-design.md) | 의미 토큰(`--ok-bg/fg`, `--danger-*`, `--warn-*`, `--info-*`, `--neutral-*`, `--color-up/down`)으로 리터럴 20여 곳 치환, `@layer`, 라이트 테마(`prefers-color-scheme` + `light-dark()` 폴백 유지), 차트 색은 CSS 변수에서 읽기, `format.ts`(`Intl.NumberFormat('ko-KR')`, KST 명시 `Intl.DateTimeFormat`), 상승=빨강·하락=파랑 + 부호·화살표 병행 | 화면 전수 확인(다크·라이트), 스냅샷 없음 | 1.5일 |
| 2.6 | React 19.3 + recharts 3.10 + 코드 분할 | FE-#17·#19·#20 | `types-react-codemod preset-19`, `@types/react` 19, recharts 3(`accessibilityLayer` 기본 on, Tooltip 타입 재작성), 성과·공모주·판단 탭 `React.lazy` | 테스트·빌드, 초기 번들 gzip 감소 기록(현 172KB) | 1일 |
| 2.7 | 계약 정합 | FE-#6 | `IpoMetricsInput.listingDate` 입력 추가, 지표 폼 부분 갱신(BE 규약과 일치) | 공모주 탭에서 상장일 입력 → LISTED 전이 | 0.5일 |
| 2.8 | 해시 기반 탭 URL | D-11 | `useHashTab()`(`#orders?days=7`), 새로고침·북마크 유지, `document.title` 갱신 — 라이브러리 없음(권고안) | 뒤로가기·새로고침 | 0.5일 |
| 2.9 | 신규 화면 | ADR-10 FE-4, [04 §8.2](04-research-design.md) | FE-4 리스크 설정 조회(읽기 전용 — BE `GET /api/risk/settings` View DTO 추가, `RiskProperties`·매크로 임계·수동 상한), 코어-위성 요약 카드(3.2 이후), 복구 훈련 체크리스트(선택) | BE 컨트롤러 테스트 + 화면 | 1.5일 |

선택(보류 가능): Playwright 1.63 스모크 3개 + axe(`page.route`로 API 목) — 2.3 이후 여유 시.

**Phase 2 완료 기준**: FE P0·P1 전부 해소, CI FE 게이트 초록, jsx-a11y 위반 0, KWCAG 불합격 항목(폼 레이블·키보드·라이브 리전·대비) 0.

---

## 6. Phase 3 — 전략·LLM (D-01 결정 후, 11/2~12/11)

**문(gate)**: D-01(ADR-15) 결정 전에는 3.2 이후를 시작하지 않는다. 결정은 **10/16까지** 권고(Phase 0 종료 시점).

### 3.1 ADR-15 확정 기록 (0.5일) · D-01, D-04
PLAN.md 0-2절에 ADR-15(결정·버린 대안·근거: [08 §1-3, §1-4, §1-7](08-research-trading-strategy.md), `x0_diagnostic`), PROGRESS 트랙 X 종결 여부(X3 취소 — D-04). **기존 21계열에 소급 적용 없음**(스누핑 금지) 명시.

### 3.2 코어 슬리브 운용 규칙 구현 (2일) — D-01 결과에 따라
- 전략 `strategy.CoreAllocationStrategy`: 사전 선언된 목표 비중(D-01)·리밸런싱 규칙(예: 분기 1회 또는 ±5%p 밴드)으로 **Signal만** 발행(설계 규칙 1·2). 대상 ETF·비중은 `@ConfigurationProperties` record, 기본 비활성.
- `RiskGate.horizonFor`의 "C3" 접두 하드코딩을 전략이 지평을 싣는 방식으로 일반화(`SignalDecision.horizon` 활용), 코어 지평 추가(`CORE`).
- 게이트 적용: 코어는 벤치마크 자체라 게이트 ① 비대상, **게이트 ②(운영)만** — ADR-15에 명시.
- **검증**: 모의 계좌에서 1회 리밸런싱 실측, 판단 근거 탭에 CORE 지평 표시.

### 3.3 위성 가설 1개/분기 사전 선언 (0.5일 선언 + 구현은 가설별)
- 후보(권고 순서): ① 공모주 상장일 **분할 매도 규칙 기록**(시초 50% + 종가 50% — 실행은 수동, 기록·평가 자동) ② 자사주 **소각** 조건부(코스피·시총 상위 200 제외·ROE>0 — G2 재설계, **모의 전용**) ③ X3 F-Score×모멘텀(D-04에서 유지 시).
- 규율: 규칙·파라미터 고정 → PROGRESS 3절 trial 등록 → 게이트 v2 판정(+ [08 §3-3](08-research-trading-strategy.md) "라이브 헤어컷 ~50% 후에도 양(+)" 병기 권고). CPCV 러너는 첫 신규 후보가 백테스트 대상일 때 구현(YAGNI).

### 3.4 IPO 파이프라인 개선 (1.5일) · D-14
- 필터 임계 재정의 trial 선언: 2026 분포(청약 1,316:1·확약 27.2% — [07 §5](07-research-trading-market.md))에서 고정 임계(500·20%)의 변별력 저하 → **최근 12개월 분위수 기준**을 사전 선언(결과 본 뒤 조정 금지).
- 동기화 12:00 1회 추가(4분기 일정 집중), 상장일 입력(2.7), 상장일 매도 결과 기록 필드 활용.

### 3.5 LLM 어드바이저 L1 — 비매매 해설 (2일) · D-10
- **원칙**: LLM은 조언만, 주문 경로와 연결 없음. `analysis` 모듈(현재 비어 있음)에 포트 `LlmAdvisor` + 어댑터(권고: Anthropic Messages API, 모델·키·예산은 설정 — 모델 ID는 코드에 하드코딩하지 않음).
- 기능: ① 15:50 일일 리포트 뒤 **3줄 해설**(체결·슬리피지·거부 사유 요약)과 이상 징후 설명 → 텔레그램 ② 공시(IPO·블랙리스트 대상) 요약 — 사람 확인용.
- **보호장치**: 입력 최소화(계좌번호·키·잔고 절대값 제외, 비율·종목코드만), 월 예산 상한(`analysis.llm.monthly-budget-usd` — 응답 usage 누계로 차단), 타임아웃은 공통 설정, 실패는 해설 생략(리포트는 그대로). ArchUnit 규칙 추가: **`analysis`는 trading·risk·execution·kiwoom에 의존하지 않는다**.
- 비용 추정: Haiku 4.5 $1/$5 per MTok(공식) 기준 하루 수만 토큰 → 월 수 달러([08 §6-3](08-research-trading-strategy.md)).
- **검증**: 모의 운영 2주 해설 품질 점검(사람), 예산 차단 테스트.

### 3.6 LLM L2 — 감성 섀도 기록 (1.5일, 3.5 뒤)
- 뉴스·공시 감성 점수를 `NewsSentiment` 이벤트(현재 발행자 없음)로 **기록만** — `RiskGate`·전략은 읽지 않는다. 근거: LLM 시그널 백테스트는 학습 컷오프 이전 구간이 오염되어 전진(모의) 검증만 유효, 감성 정확도 ≠ 수익 예측력([08 §6-1](08-research-trading-strategy.md)).
- 6개월 기록 후 사전 선언 trial로 "필터 on/off" 효과 검증(ADR-3 원칙 그대로). KR-FinBert 사이드카는 보류 유지.

---

## 7. Phase 4 — 인프라 하드닝 (11/16~12/11)

| # | 작업 | 근거 | 변경 | 선행·결정 | 롤백 |
|---|---|---|---|---|---|
| 4.1 | bootRun → jar 실행 | Gradle 데몬·소스 컴파일 의존 제거([06 §1.3](06-research-infra.md)) | `gradlew :app:bootJar` → `java -jar app.jar --spring.profiles.active=paper ...`(기존 bat 인자 그대로), `start_autostock.bat`만 교체. 이전 jar 1개 보관(롤백용) | — | bat 원복 |
| 4.2 | 서비스화 | 로그인 세션 종속 제거 | (D-08 결과) Task Scheduler "로그온 여부와 관계없이" 또는 WinSW v2.12. **PG 가용성**: Docker Desktop은 로그인 없이 기동 공식 미지원(확인) → 자동 로그인 유지 또는 PG 네이티브 서비스 | D-08 | 작업 해제 |
| 4.3 | graceful 종료 | actuator shutdown 대체 | `server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase=30s` 추가(Expand) → 서비스 stop으로 종료 확인 후 `shutdown` 엔드포인트 노출 제거(Contract, 별도 커밋) | 4.2 | 키 원복 |
| 4.4 | 시크릿·공급망 | [06 §5](06-research-infra.md), tj-actions 사고 교훈 | `.env` ACL(`icacls`), BitLocker 확인, gitleaks pre-commit + CI 잡(SHA 고정), Actions: `uses:` SHA 고정·`permissions: contents: read`·Dependabot(gradle·npm·actions 주간) | — | 워크플로 원복 |
| 4.5 | 원장·로그 보존 | 세무·분쟁 대비 | 주문·체결·이벤트 원장 5년 보존 정책(백업 월말본 보존), 로그 총량 상한 확인(logback), 연간 체결·수수료·세금 CSV 추출 스크립트(`scripts/export_trades.sql`) | 0.8 | — |
| 4.6 | (선택) Prometheus·Grafana | D-13 | 1.7 메트릭을 수집·대시보드 — 사고 재발 시에만 | D-13 | 컨테이너 제거 |
| 4.7 | (중기) 미니PC Linux 이전 검토 | D-08 | 절차: **새 호스트 공인 IP를 키움에 먼저 등록** → 모의로 토큰·조회·WS 실측 → 병행 1주 → 전환. 클라우드는 해외/클라우드 IP 정책 미확인이라 실측 전 금지 | D-08 | 기존 PC 복귀 |

---

## 8. Phase 5 — 플랫폼 이관 (브랜치 `upgrade/boot-4.1`, 11/16~2027-01-22)

**순서(확인된 제약 기반)**: Boot 3.5.16은 Gradle 7.6.4+/8.4+만, Gradle의 JDK 25 지원은 9.1.0+, Boot 4.1은 Gradle 8.14+·9.x 지원 → **5.0 → 5.1(Boot 4.1, Gradle 8.14, JDK 21) → 5.2(Gradle 9.1+) → 5.3(JDK 25)**. 한 단계가 모의 운영 1주를 통과해야 다음 단계.

### 5.0 3.5에서 선행 정리 (main, 11/16~11/20, 1.5일)
1. Gradle wrapper 8.14.2 → **8.14.5**(패치), Modulith BOM 1.4.12 → **1.4.13**(app BOM과 `common`의 `spring-modulith-api` 고정 버전을 **함께** — 두 곳 수동 동기).
2. `./gradlew test --warning-mode all`로 Gradle 9에서 깨질 경고 목록화·해소.
3. **이벤트 JSON 골든 테스트 추가**: `common.event.*` 18종의 직렬화 결과를 고정 파일과 비교(Jackson 3 이관 검증 기준) + `event_store` 과거 행 샘플 역직렬화 테스트.
4. `event_publication` 3컬럼 추가(1.8에서 안 했으면).
5. `spring-boot-properties-migrator` 적용 계획(브랜치에서만).

### 5.1 Spring Boot 4.1.x + Modulith 2.1.x (브랜치, 11/23~12/18, 5~7일)
| 단계 | 변경 | 함정(확인·추론) |
|---|---|---|
| 1 | `build.gradle`: Boot 플러그인 4.1.x, Modulith BOM 2.1.x, springdoc **3.x**, ArchUnit은 Modulith가 끄는 버전에 맞춤 | `common`의 `spring-modulith-api`도 2.1.x로 |
| 2 | starter: `web` → **`webmvc`**, `webflux` → **`webclient`**(WebClient만 쓰므로 — WebFlux 서버 기능 미사용 확인), **`spring-boot-starter-flyway` 명시**(없으면 마이그레이션이 돌지 않음), `websocket` starter 이름 확인(미확인), 테스트 starter 분리 | Flyway starter 누락은 기동은 되는데 스키마가 안 맞아 `validate` 실패로 드러남 |
| 3 | Jackson 3: `config/JacksonConfig` 재작성(`tools.jackson` 패키지, `ValueSerializer`), `spring.jackson.*` 키 확인 | 5.0-3 골든 테스트로 JSON 동일성 확인 — **이벤트 JSON이 한 글자라도 바뀌면 중단** |
| 4 | HTTP 클라이언트: yml `spring.http.reactiveclient.*` → `spring.http.clients.*`, `ipo/DartClient`·`macrointel/MajorDisclosureDartClient`의 `ClientHttpConnectorBuilder/Settings` 새 위치 | 타임아웃 적용 확인 테스트(`HttpClientTimeoutTest`) |
| 5 | Hibernate 7 + `ddl-auto: validate`, Modulith 2.1 레지스트리(`spring.modulith.events.jdbc.schema-initialization.enabled=false` — Flyway 관리) | `event_publication` 컬럼(5.0-4) 없으면 validate 실패(추론) |
| 6 | 테스트: JUnit 6, Testcontainers 2(`org.testcontainers:testcontainers-postgresql`), zonky embedded-postgres 호환 버전, `@SpringBootTest`에서 MockMvc 쓰는 곳에 `@AutoConfigureMockMvc` | `@MockBean` 사용 0건(확인) |
| 7 | 검증: 전체 테스트·Modularity·ArchUnit·DB 테스트 → 장외 기동 스모크 → **금요일 16:00 이후 배포 → 다음 주 모의 운영 1주** | 연말 동결(12/24~1/4) 전에 끝내지 못하면 1월로 이월(Boot 4.1 OSS는 2027-07-31까지) |
- **롤백**: 이전 jar로 기동(DB 변경은 컬럼 추가뿐이라 호환 — 5.0에서 이미 적용).

### 5.2 Gradle 9.1+ (2027-01-05~01-08, 0.5일)
wrapper만 교체, Groovy DSL 유지(계속 지원). 5.0-2에서 경고를 없앴으므로 변경 최소. **롤백**: wrapper 원복.

### 5.3 JDK 25 (Temurin) (2027-01-11~01-22, 1일 + 소크)
- 툴체인 `languageVersion = 25`, `start_autostock.bat`의 `JAVA_HOME`(Temurin 25), JVM 옵션 `--enable-native-access=ALL-UNNAMED`(JNA — JEP 472 경고), 테스트 JVM에 Mockito 에이전트(`-javaagent` 또는 `-XX:+EnableDynamicAgentLoading`), CI `setup-java` 25.
- 검증: 24시간 소크(장외 포함) + JFR `jdk.VirtualThreadPinned` 0건. JEP 491로 `synchronized` 핀닝이 해소되지만 **기존 `ReentrantLock` 코드는 바꾸지 않는다**(동작 동일, 불필요한 변경).
- **롤백**: 툴체인 21 재지정.

### 5.4 이관 후 재평가 (선택)
RestClient 전환(2절 #4), Boot 4.2(2026-11 GA 예상) 추종 시점, Resilience4j 2.4(코어 모듈이라 Boot 무관).

### 하지 않을 것
Boot 4.0.x 경유(OSS 2026-12-31 종료), PostgreSQL 18 즉시 승급(16은 2028-11-09까지 지원), GraalVM 네이티브 이미지, Kafka 외부화(ADR-1·2 트리거 미충족), Windows에서 앱 컨테이너화(JNA 절전 차단 불가).

---

## 9. Phase 6 — 실전 전환 준비 (게이트 ② 판정 후)

게이트 ② 통과는 사용자 판정. 통과 후 아래를 **전부** 충족해야 live 프로필을 쓴다([06 §10](06-research-infra.md) + RUNBOOK 7절 + PROGRESS 5절 통합).

| 영역 | 항목 |
|---|---|
| 키·계정 | 실전 키 **전량 재발급**(채팅 노출 이력 — PROGRESS 5절), 실전 허용 IP 등록, 실서버 서비스 상태 정상, `.env`의 LIVE 키는 live 프로필에서만 로드 |
| 자금 | 초기 금액 = 전액 손실 감수 가능 금액(사용자 결정), 수동 주문 상한·일 손실 한도 재설정, 코어·위성 비중(D-01) 반영 |
| 안전 | 킬스위치 복원(0.2)·`/resume` 2단계(0.7)·긴급 정지 경로 2개 이상 + HTS 수동 취소 절차 숙지, 복구 훈련 2회 이상 |
| 운영 | 30영업일 SLO 충족, 외부 heartbeat·P1 알림 60초 내 실측, 백업 복원 리허설 성공 |
| 변경 관리 | live 배포는 태그된 커밋 + 장외 창만, 롤백 연습 1회 |
| 기록·세무 | 연간 체결·수수료·세금 추출 확인, 필요 시 세무사 상담 |

---

## 10. 주 단위 일정 (권고, 1인 기준)

| 주(월요일) | 작업 | 비고 |
|---|---|---|
| 10/1(목)~10/2 | 0.0 사전 점검, D-02·D-05·D-06·D-09 결정 요청 | |
| 10/5 주 | 0.1, 0.2, 0.3 | **10/5(월)·10/9(금) 휴장** — 장중 실측은 10/6~10/8 |
| 10/12 주 | 0.4+0.5(동시 배포), 0.6, 0.7, 0.8, 0.9, 0.10, 0.11 | 금 16:00 이후 배포, **D-01 결정 목표 10/16** |
| 10/19 주 | 1.1, 1.2 / 2.1, 2.2 | FE는 BE와 파일 불충돌 |
| 10/26 주 | 1.3, 1.4, 1.5 / 2.3, 2.4 | |
| 11/2 주 | 1.6(장중 실측), 1.7 / 2.5 / 3.1 | |
| 11/9 주 | 1.8, 1.9, 1.10(복구 훈련) / 2.6, 2.7 / 3.2 | Phase 1 종료 |
| 11/16 주 | 5.0 / 2.8, 2.9 / 3.3, 3.4 / 4.1 | Phase 2 종료 |
| 11/23 주 | 5.1 착수(브랜치) / 3.5 / 4.2, 4.3 | |
| 11/30 주 | 5.1 / 3.6 / 4.4, 4.5 | |
| 12/7 주 | 5.1 마무리·검증 | Phase 3·4 종료 |
| 12/14 주 | 5.1 금 16:00 이후 배포 → 모의 운영 | |
| 12/21 주 | 모의 운영 관찰(배포 없음) | **12/24~1/4 변경 동결**(12/25·12/31·1/1 휴장) |
| 2027-01-04 주 | 5.2 Gradle 9.1+ | |
| 01/11~01/22 | 5.3 JDK 25 + 소크 | Phase 5 종료 |

일정이 밀리면 **Phase 0 → Phase 1(1.1·1.2) → Phase 2(2.1~2.4) → Phase 5** 순으로 지키고, Phase 3.5·3.6·4.6·4.7은 뒤로 미룬다.

---

## 11. 의존 관계 (선행 → 후행)

- 0.0 → 모든 작업
- 0.1 → 1.6(UNKNOWN·CANCEL_REQUESTED 대사 확장의 전제)
- 0.2 → 1.10(복구 훈련에서 킬스위치 유지 확인), 6
- 0.4 ↔ 0.5(같은 배포 — 계약 변경), 0.5 → 2.2(회귀 테스트로 고정)
- 0.6 → 0.7(인증 실패 알림 채널), 0.10(RUNBOOK 증상표)
- 0.7 → 1.7(헬스·알림 연결), 3.5(해설 발송 채널)
- 0.8 → 4.5, 6
- 1.1 → 3.2(지평 일반화와 같은 파일 — 순서대로)
- 1.2 → 3.2(코어 매수도 미체결 인식 필요)
- 1.5-1 → 1.5-2, 1.6
- 1.8(D-07 a) ↔ 5.0-4(같은 마이그레이션 — 한 번만)
- 2.1 → 2.2(Vitest 5는 Vite ≥6.4) → 2.3 → 2.4~2.9
- 2.6 → 2.9(코어-위성 카드)
- D-01 → 3.1 → 3.2 → 2.9 코어-위성 카드
- 3.5 → 3.6
- 4.1 → 4.2 → 4.3
- 5.0 → 5.1 → 5.2 → 5.3 (각 단계 모의 운영 1주 통과 후)

---

## 12. 위험과 대응 요약

| 위험 | 발생 지점 | 대응 |
|---|---|---|
| 취소가 실제로 동작하며 C3 미진입 증가 | 0.1 | D-05에서 타임아웃 재확인, PROGRESS에 운영 변경 기록, 판단 근거 탭으로 추적 |
| 영속화 도입으로 킬스위치가 "안 풀리는" 오해 | 0.2 | 텔레그램·대시보드에 "재기동 복원" 사유 표시, RUNBOOK 해제 절차 |
| 계약 변경(수량 필수)으로 수동 주문 400 | 0.4 | FE와 같은 배포, 오류 표시(0.5) |
| C3 판단 빈도 변화로 게이트 ② 비교 단절 | 1.1 | D-03, 변경일 기준 전후 분리 집계 |
| Jackson 3로 이벤트 JSON 변형 | 5.1 | 5.0 골든 테스트, 불일치 시 중단 |
| Flyway starter 누락(Boot 4) | 5.1 | 체크리스트 2단계, 기동 스모크 |
| CI 스모크가 허용 IP로 실패 | 0.6·4.4 | 수동 트리거로 이동(2절 #2) |
| 키움 서비스 해지 | 상시 | 0.10, D-02, 인증 실패 알림(0.6) |
| 연말 휴장·점검과 배포 충돌 | 5.1 | 변경 동결 12/24~1/4 |

---

## 13. 추적 방법

- 작업 시작·완료는 PROGRESS.md 해당 트랙(C: 운영, E: FE, 신규 "U: 고도화 2026-10")에 한 줄씩, 결정은 PLAN.md ADR로.
- 작업별 근거 문서 이름(권고): `aiDoc/stale-cancel.md`(0.1), `aiDoc/risk-state-persistence.md`(0.2), `aiDoc/manual-order-guard.md`(0.4·0.5), `aiDoc/kiwoom-error-codes.md`(0.6), `aiDoc/heartbeat-telegram.md`(0.7), `aiDoc/backup.md`(0.8), `aiDoc/c3-decision-cadence.md`(1.1), `aiDoc/open-order-awareness.md`(1.2), `aiDoc/position-reconcile.md`(1.5), `aiDoc/observability.md`(1.7), `aiDoc/fe-toolchain-2026.md`(2.1~2.3), `aiDoc/fe-a11y.md`(2.4), `aiDoc/llm-advisor.md`(3.5), `aiDoc/boot41-migration.md`(5.x).
- 이 계획 자체의 변경은 이 파일의 "변경 이력"에 날짜와 함께.

## 변경 이력
- 2026-10-01: 최초 작성(조사 02~08, 감사 09·10, 보강 확인 반영).
- 2026-10-02: Phase 1 중 장외에 할 수 있는 1.1~1.5·1.7~1.9를 앞당겨 구현(사용자 지시, D-03·D-07 권고안) — `aiDoc/phase1-offhours-2026-10-02.md`. 근거 문서 이름은 13절 권고와 다르다: 1.1 `c3-trading-day-cycle.md`, 1.2 `open-order-aware-risk.md`, 1.3 `async-notification.md`, 1.4 `macro-staleness.md`, 1.8 `audit-reliability.md`, 1.9 `small-fixes-2026-10-02.md`. 남은 Phase 1: 1.6(장중 실측), 1.10(복구 훈련).
