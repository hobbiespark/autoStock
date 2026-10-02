# aiDoc 목록

리팩토링 근거·결정 기록이다. 원래 로컬 전용(`.git/info/exclude`로 제외)이었으나, 2026-09-30 PC 교체 후 작업을 이어가기 위해 사용자 결정으로 리포에 커밋한다(규칙 §13 "리포 md는 README만"의 예외).

- [refactoring-plan.md](refactoring-plan.md) — 코딩 규칙(`C:\claude\coding-rules.md`) 대비 격차와 리팩토링 계획 (2026-09-29)
- [log-secret-masking.md](log-secret-masking.md) — S1: ECOS 경로형 키 로그 마스킹, 응답 본문 로그 축소
- [http-timeouts.md](http-timeouts.md) — R1: 외부 HTTP·WS 호출 타임아웃(Boot 속성 공통 적용, DART 커넥터 포함)
- [ipo-command-transaction.md](ipo-command-transaction.md) — R3: IPO 수동 입력을 트랜잭션 경계 가진 IpoDealCommandService로 이동
- [order-concurrency.md](order-concurrency.md) — R2 조사: 주문·IPO 딜 동시 갱신(lost update)과 체결 통보 순서 경합(R4)
- [clock-injection.md](clock-injection.md) — A4: 현재 시각을 주입 Clock으로 일원화(엔티티는 보류)
- [architecture-rules.md](architecture-rules.md) — A3: ArchUnit 의존 규칙 7개(설계 규칙 고정)
- [market-data-port.md](market-data-port.md) — A2: 시세 조회 포트(MarketDataPort)와 키움 어댑터 분리
- [request-validation.md](request-validation.md) — B2: 요청 본문 @Valid 검증, 킬스위치 fail-open 수정
- [error-handling.md](error-handling.md) — B1: 전역 예외 처리, RFC 9457 ProblemDetail + ErrorCode
- [value-objects.md](value-objects.md) — A1: 값 객체 도입(조각별 기록, 1: trading BrokerOrderId·Quantity)
- [loopback-binding.md](loopback-binding.md) — S2: 루프백 전용 바인딩(무인증 관리·주문 경로 외부 차단)

## 인계 메모 (2026-09-30, PC 교체)

- 진행 상태의 기준은 `refactoring-plan.md` 상단 상태 목록이다. A1 값 객체는 조각 21까지 완료(Candle 보류 외 이벤트 적용 끝)(`value-objects.md`).
- FE: `frontend` 소스를 고치면 `npm run build`(→ `app/src/main/resources/static`) 결과도 함께 커밋한다. Windows `node_modules`는 Linux에서 쓸 수 없어 작업 환경에서는 소스를 옮겨 `npm ci`로 빌드한다(2026-09-30 `da75348`).
- 다음 후보: 착수 가능한 계획 항목 없음 — 남은 것은 사용자 결정 대기·나중 항목(아래). `Candle` 종목코드는 사용자 결정으로 보류.
- 사용자 결정 대기: P3 나머지 항목(Testcontainers는 완료 — `db-integration-test.md`).
- 나중에(사용자 결정 2026-09-30): B4 감사 실패 알림.
- 재기동 후 확인할 미검증 항목은 각 문서의 "검증 상태" 절에 있다(V8 마이그레이션, 루프백 바인딩, 타임아웃, event_store JSON, 체결·포지션 반영 등).
- 리포 밖 파일의 사본(2026-09-30 복사). 새 PC에서는 원래 위치로 복사해 쓴다.
  - `claude-rules/` → `C:\claude\` — `CLAUDE.md`(최상위 규칙, `@C:/claude/coding-rules.md`로 코딩 규칙을 불러온다), `coding-rules.md`
  - `claude-memory/` → `C:\Users\<사용자>\.claude\projects\<프로젝트 경로 인코딩>\memory\` — `MEMORY.md`(색인), `push-batched.md`(커밋마다 push를 묻지 않음). 프로젝트 경로가 바뀌면 폴더 이름도 바뀐다(예: `C--project-autoStock`).
  - `market-data/` → `data/` — 백테스트 시세 CSV 5종목(000660, 005930, 035420, 035720, 069500). 루트 `.gitignore`의 `data/` 규칙에 걸리지 않도록 폴더 이름을 바꿔 두었다.
- 여전히 리포에 없는 것(직접 옮긴다):
  - `.env` — 비밀값, 커밋 금지
  - `.claude/settings.local.json` — 개인 권한 설정
- [large-classes.md](large-classes.md) — B3: 큰 클래스 조사(측정표, 후보별 판단, 결정 요청)
- [db-integration-test.md](db-integration-test.md) — P3: DB 통합 테스트(2026-10-02부터 운영 이미지 TimescaleDB HA로만 — Testcontainers 또는 외부 주소, 없으면 건너뜀. 9/30에는 Docker면 postgres:16-alpine, 없으면 내장 PG 16.15), 첫 검증 4건
- [time.md](time.md) — A5: 시간대 명시(저장 UTC·업무 날짜 KST 변환 층 표, DATE 영향 실측, cron zone 규칙 테스트)
- [sleep-resume.md](sleep-resume.md) — 2026-09-30 로그: 장중 PC 절전 방지·복귀 감지, 토큰 거부(8005) 재발급, 로그 소음 정리
- [upgrade-2026-10/](upgrade-2026-10/00-README.md) — 2026-10-01 고도화 조사(FE·BE·기획·디자인·인프라·주식거래 6관점)·코드 감사(BE·FE)·실행 계획(Phase 0~6, 충돌 방지 규약)·사용자 결정 목록(D-01~D-14)
- [stale-cancel.md](stale-cancel.md) — Phase 0.1: 미체결 타임아웃 취소 정상화(ACCEPTED·부분체결 포함, 취소 경로 TradingService로 일원화, CANCELLED 확정, 대사에 CANCEL_REQUESTED 추가)
- [risk-state-persistence.md](risk-state-persistence.md) — Phase 0.2: 킬스위치·일 손실 누계 영속화(V9 risk_state·risk_daily_pnl)와 재기동 복원, 원가 장부 시드(PositionRestored), 켜진 채 시작하면 DEGRADED
- [disclosure-blacklist-tx.md](disclosure-blacklist-tx.md) — Phase 0.3: 공시 블랙리스트 파생 삭제에 트랜잭션(트랜잭션 밖 호출 시 예외로 한 건도 안 지워지던 결함 — 실패 테스트로 확인 후 수정), remove는 DB 먼저
- [manual-order-guard.md](manual-order-guard.md) — Phase 0.4+0.5: 수동 주문 수량 필수·매수 1건 100만 원 상한(RiskGate), 발행 전 확인 창(LIVE면 종목코드 재입력), 킬스위치 해제·매매 시작 확인, 서버 오류 표시, LIVE 띠
- [kiwoom-error-codes.md](kiwoom-error-codes.md) — Phase 0.6: 키움 오류코드 분류(유량 1700·1701·1702 재시도, 8005·8010 재발급 1회, 인증 실패 8001·8002·8010·8030·8031·8040·8050·8103 텔레그램 긴급 알림), 주문 타임아웃을 거부가 아닌 결과 불명(UNKNOWN)으로
- [heartbeat-telegram.md](heartbeat-telegram.md) — Phase 0.7: 외부 heartbeat(Healthchecks.io, 장중 1분 핑), 텔레그램 가동(bat 조건부), /resume 2단계 확인(4자리·60초), 폴러 fixedDelay, RUNBOOK 5·6·8절
- [backup.md](backup.md) — Phase 0.8: DB 백업(scripts/backup_db.ps1 — 컨테이너 내 pg_dump, 목차 확인, 14일+12개월 보존)과 복원 리허설(scripts/restore_check.ps1)
- [alert-digest.md](alert-digest.md) — 10/1 로그 점검 F-1: 공시 블랙리스트 알림을 매매 대상·보유 종목만 즉시 WARN, 나머지는 1분 요약 INFO 1건으로. 텔레그램 429는 retry_after 대기 후 1회 재시도(상한 10초)
- [run-summary-logs.md](run-summary-logs.md) — 10/1 로그 점검 F-2·F-4·F-5: C3 09:05 실행 요약 1줄, 공모주 수집 완료 요약 1줄, kt00018 실측 키 로그 DEBUG화
- [external-data-files.md](external-data-files.md) — 외부 데이터 파일 현황(data/ 579MB·aiDoc/market-data·docs/measured)과 파일·DB 관리 비교. 분봉은 유일 원천인데 백업 누락·빈 날 많음 → DB 이전 확정(TimescaleDB, 10/1)·1년치 되채우기, aiDoc/market-data 공개 커밋 제거 권고(결정 대기)
- [data-platform-postgres.md](data-platform-postgres.md) — PostgreSQL 최대 활용 설계: TimescaleDB·pgvector·pg_trgm·UNLOGGED·pg_prewarm을 한 DB에(시계열·AI 지식·메모리·학습), 매매 경로 분리·시점 정합성(available_at)·랩 컨테이너, HA 이미지(PG 17)·전환 실측, 결정 D-15~D-19
- [stock-names.md](stock-names.md) — 2026-10-02 종목코드와 종목명 함께 표시: 표기 "삼성전자(005930)"(모르면 "종목명 미확인(코드)"), 이름 출처(키움 ka10001·kt00018 > DART 기업명), 메모리 사전 + V10 `stock_names`, 알림·리포트·API(symbolName)·화면·로그 적용
- [ipo-demand-forecast.md](ipo-demand-forecast.md) — 2026-10-02 공모주 기관경쟁률·의무보유확약비율(수량 기준) 자동 입력: [발행조건확정] 신고서 원본(document.xml)의 수요예측 결과, 실측 14건·언론 대조, 상장사 유상증자를 공모주에서 제외(주요정보 모집방법·공시 본문 판정, V11), 수동 입력 우선
- [db-switch-timescale.md](db-switch-timescale.md) — 2026-10-02 S1 1단계: DB를 TimescaleDB HA 이미지(PostgreSQL 17, 다이제스트 고정)로 전환하는 스크립트(덤프·복원·행 수 대조·자동 되돌림·끊김 복구)와 되돌리기, 백업 버전 기록·복원 리허설 pre/post restore·랩(-Lab), DB 테스트는 운영 이미지로만(zonky 제거), 결정 D-15~D-19 권고안 적용
- [minute-bars-db.md](minute-bars-db.md) — 2026-10-02 S1 2단계: 분봉을 DB로(V12 `minute_bars` 하이퍼테이블·월 청크·18시 열 압축, `daily_bars` KST 일봉 연속 집계), 적재 잡 따라잡기·장외 1년 되채우기·빈 구간 메우기·검증 제외·CSV 이관, 키움 연속 조회(cont-yn·next-key)
- [c3-trading-day-cycle.md](c3-trading-day-cycle.md) — Phase 1.1: C3 판단 주기를 21거래일로(백테스트 21봉과 같게), 마지막 판단일 영속화(V13 `strategy_state`), D-03 권고안(카운트 유지·성과 변경일 기준 분리)
