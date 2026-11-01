# autoStock 결정 로그

autoStock에 걸린 결정을 찾는 곳이다(공통 표준 문서 구조, VALUNIVO 4-2 S2). 기존 결정은 원래 문서에 두고 여기서 연결한다. autoStock 저장소의 새 결정은 3장에 `D-` 번호를 이어서 쓴다(지금까지 D-01~D-22).

## 1. 설계 결정 (ADR, `PLAN.md` 0절)

[PLAN.md](../PLAN.md) 0절의 ADR-1~ADR-14다. 논리적 MSA·물리적 모놀리스(ADR-1), PostgreSQL 이벤트 스토어(ADR-2), 매매 코어 검증 우선(ADR-3), 유지 사항(ADR-4), 엔지니어링 고도화(ADR-5), 아키텍처 원칙(ADR-6, 상세 [docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md)), 단기 익절·손절 규칙(ADR-7), 인버스 ETF 숏(ADR-8), 공모주 반자동(ADR-9), FE React+TypeScript(ADR-10), 보유기간 지평별 전략(ADR-11), 게이트 v2(ADR-12), 검증 인프라 확장(ADR-13), DART 재무·공시 확장(ADR-14).

## 2. 사용자 결정 (D-01~D-21)

[upgrade-2026-10/12-decisions.md](upgrade-2026-10/12-decisions.md)에 선택지·권고·기한·결정 기록이 있다. 데이터 플랫폼 결정 D-15~D-19의 근거는 [data-platform-postgres.md](data-platform-postgres.md)다.

## 3. autoStock 저장소 결정 (D-22~)

| 번호 | 날짜 | 결정 | 이유 | 근거 |
|---|---|---|---|---|
| D-22 | 2026-11-01 | 표준 문서 구조는 기존 문서를 **옮기지 않고 색인**한다. `aiDoc/` 평면 문서·`PLAN.md`·`PROGRESS.md`는 제자리에 두고 계획목록·진행목록·이 로그·[상세 README](상세/README.md)에서 연결한다 | 코드 주석·스크립트·다른 브랜치가 경로로 가리켜 옮기면 링크가 깨지고 되돌리기 어렵다. 이동이 필요하면 링크 검사와 함께 별도 단계로 한다 | VALUNIVO 501 4장 권장안(D88로 진행, 데브가 바꿀 수 있음) |

## 4. autoStock에 걸리는 VALUNIVO 결정

원문은 `C:\ai\VALUNIVO\aiDoc\01-decision-log.md`다. 여기에는 무엇을 정했는지만 한 줄로 적는다.

| VALUNIVO 결정 | autoStock에 미치는 것 |
|---|---|
| D96 | 앱 알림은 자체 발송 + 플랫폼 사본 |
| D107 | 공통 표준 하나에 단계적으로 수렴, 바깥 면 먼저(4-2) |
| D132 | 원본 변경은 장 마감 후(평일 16:00 이후)·주말, 별도 브랜치, main 병합·운영 반영은 데브에게 다시 묻기 |
| D154 | git flow: `develop` 개발 통합, `main` 운영 |
| D182 | 화면 토큰은 CSS 변수(`valunivo-tokens.css` 복사본), 부품은 직접 CSS |
| D213 | id·pw·키의 원본은 Git 밖 env-input, 앱 설정은 생성기로 만든다 |
| D239 | 운영도 모의 서버로 먼저 운영, 실전 키는 데브 지시 뒤 |
| D256 | `application.yml`에 DB 계정 기본값을 두지 않는다 |
| D259 | 외부 키는 개발·운영이 같은 값을 쓴다 |
