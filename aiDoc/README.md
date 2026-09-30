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

- 진행 상태의 기준은 `refactoring-plan.md` 상단 상태 목록이다. A1 값 객체는 조각 15까지 완료(`value-objects.md`).
- 다음 후보: `Price` 이벤트 적용(조각 15의 0·null 경로 표부터), A5 시간대 명시(DB 확인 필요), B3 큰 클래스 조사. `Candle` 종목코드는 사용자 결정으로 보류.
- 사용자 결정 대기: B4 감사 실패 알림, 엔티티 시각의 Clock 주입, P3 항목(특히 Testcontainers).
- 재기동 후 확인할 미검증 항목은 각 문서의 "검증 상태" 절에 있다(V8 마이그레이션, 루프백 바인딩, 타임아웃, event_store JSON, 체결·포지션 반영 등).
- 리포 밖 파일의 사본(2026-09-30 복사). 새 PC에서는 원래 위치로 복사해 쓴다.
  - `claude-rules/` → `C:\claude\` — `CLAUDE.md`(최상위 규칙, `@C:/claude/coding-rules.md`로 코딩 규칙을 불러온다), `coding-rules.md`
  - `claude-memory/` → `C:\Users\<사용자>\.claude\projects\<프로젝트 경로 인코딩>\memory\` — `MEMORY.md`(색인), `push-batched.md`(커밋마다 push를 묻지 않음). 프로젝트 경로가 바뀌면 폴더 이름도 바뀐다(예: `C--project-autoStock`).
  - `market-data/` → `data/` — 백테스트 시세 CSV 5종목(000660, 005930, 035420, 035720, 069500). 루트 `.gitignore`의 `data/` 규칙에 걸리지 않도록 폴더 이름을 바꿔 두었다.
- 여전히 리포에 없는 것(직접 옮긴다):
  - `.env` — 비밀값, 커밋 금지
  - `.claude/settings.local.json` — 개인 권한 설정
- [sleep-resume.md](sleep-resume.md) — 2026-09-30 로그: 장중 PC 절전 방지·복귀 감지, 토큰 거부(8005) 재발급, 로그 소음 정리
