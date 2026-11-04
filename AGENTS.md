# autoStock 작업 지침

autoStock은 VALUNIVO 플랫폼의 첫 번째 연동 앱이다. 플랫폼 쪽 상위 지침은 [`C:\ai\AGENTS.md`](../AGENTS.md)와 `C:\ai\VALUNIVO\AGENTS.md`이며, 이 문서는 autoStock 저장소에서만 지킬 것을 적는다(VALUNIVO 계획 4-0, D154·D213·D239).

## 0. 먼저 읽을 것 (공통 표준 문서 구조, VALUNIVO 4-2 S2)

1. [계획목록](aiDoc/00-계획목록.md): 할 일이 어디에 있는지(플랫폼 연동은 VALUNIVO 계획목록, 제품은 `PLAN.md`)
2. [진행목록](aiDoc/00-진행목록.md): 끝난 일의 기록 위치
3. [결정 로그](aiDoc/01-decision-log.md): ADR·사용자 결정 D-01~·autoStock에 걸리는 VALUNIVO 결정. 새 결정은 여기 3장에 이어 쓴다
4. [상세 문서 지도](aiDoc/상세/README.md): 역할별(공통·기획·디자인·FE·BE·인프라·QA) 문서 색인

기존 문서는 옮기지 않고 색인한다(D-22). `aiDoc/`에 새 문서를 만들면 상세 문서 지도에도 한 줄을 더한다.

## 1. 브랜치 (git flow, D154)

- `main`은 **운영 기준**이다. 지금 운영 중인 앱은 `main`에서 빌드해 돈다. 운영 반영(릴리스) 때만 `develop` → `main`으로 병합한다.
- `develop`은 **개발 통합** 브랜치다. 작업 브랜치(`<모델>/<계획번호>-<짧은 이름>`, 예: `claude/4-2-outer-surface`)는 `develop`에서 만들고 끝나면 `develop`에 병합한다.
- 운영 긴급 수정(hotfix)은 `main`에서 만들어 `main`과 `develop` 양쪽에 병합한다.
- `main` 병합·운영 재기동은 VALUNIVO D260 조건(시험 → 백업·되돌리기 준비 → 반영 → 확인, 평일 16:00~다음 날 08:00·주말)으로 AI가 하고 사후 보고한다. `main`에 직접 개발 커밋을 푸시하지 않는다.
- 원본 체크아웃(`C:\ai\autoStock`)은 운영 앱이 쓰는 곳이다. 작업은 별도 작업트리에서 한다.

## 2. 거래 안전

- **거래 로직은 바깥 면 작업(VALUNIVO 4-2)에서 바꾸지 않는다.** 입출력·알림·문서·화면만 공통 표준에 맞춘다.
- 운영 체크아웃(`C:\ai\autoStock`) 변경·운영 재기동·`main` 반영은 **평일 16:00~다음 날 08:00·주말**에만 한다(D260, 옛 D132). 별도 작업트리의 개발·시험은 시간 제한이 없다.
- **운영도 모의 서버로 먼저 운영한다(D239).** 기본 프로필은 모의 서버(`mockapi.kiwoom.com`)다. `live` 프로필·실전 키(`KIWOOM_LIVE_*`)는 데브가 모의 운영 기간·기준을 정하고 지시한 뒤에만 쓴다. 모의 키로의 폴백은 없다(실전 키 없이 `live`로 띄우면 기동 실패가 의도된 동작).
- 실전 키·봇 토큰·계좌 정보는 Git·대화·로그에 남기지 않는다. 파일 이름에 계좌번호가 들어간 키 파일도 이름을 출력하지 않는다.

## 3. 설정과 비밀값 (D213)

- id·pw·키의 원본은 Git 밖 `C:\ai\VALUNIVO-runtime\env-input\{development,production}.input.env`다.
- **DB 접속(4-0/env, VALUNIVO 491·D256):** `develop`·`main` 모두 `application.yml`에 DB 계정·암호·포트·DB 이름 기본값이 없다. 값이 없으면 앱이 Spring 전에 키 이름을 알리고 멈춘다(`DatabaseEnvironment`). 값은 VALUNIVO 생성기가 만든 `VALUNIVO-runtime\app-config\<환경>\autostock.env`를 `.env`의 `AUTOSTOCK_APP_CONFIG=<경로>`로 읽는다(`load_env.ps1`, `.env`의 `DB_*`보다 우선). 이 파일은 손으로 고치지 않는다.
- 운영은 D6 전환(VALUNIVO 531, 11-02) 뒤 `C:\ai\autoStock-runtime\database.json`(D268)이 가리키는 `valunivo-autostock-production-postgres-1`(56434)와 VALUNIVO 생성 env를 쓴다. 백업(`backup_db.ps1`)·DB 기동(`start_database.ps1`)·`load_env.ps1`은 `scripts\Get-AutoStockDatabase.ps1`로 대상을 정한다(VALUNIVO 514). 이때 `.env`의 다른 `AUTOSTOCK_APP_CONFIG`는 기동을 멈춘다. 옛 `autostock-db`(15432)는 11-03 삭제됐다(VALUNIVO 568). 현재 운영은 PG17이며 PG18 격리 리허설 결과는 VALUNIVO 583에 있다.
- `.env`·키 파일·DB 덤프는 커밋하지 않는다(`.gitignore`).

## 4. 그 밖

- 진행 설명·보고·문서는 한국어로 쓴다(코드 식별자·명령·커밋 메시지 예외).
- 리포 밖 규칙(`C:\claude\CLAUDE.md`, `coding-rules.md`)과 메모리 사본은 `aiDoc/claude-rules`·`aiDoc/claude-memory`에 있다(aiDoc/README 인계 메모).
- 개인 사이드 프로젝트 전용 PC다. 회사 업무 도구·내용·계정 식별자를 쓰지 않는다(D122).
