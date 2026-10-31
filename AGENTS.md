# autoStock 작업 지침

autoStock은 VALUNIVO 플랫폼의 첫 번째 연동 앱이다. 플랫폼 쪽 상위 지침은 [`C:\ai\AGENTS.md`](../AGENTS.md)와 `C:\ai\VALUNIVO\AGENTS.md`이며, 이 문서는 autoStock 저장소에서만 지킬 것을 적는다(VALUNIVO 계획 4-0, D154·D213·D239). 리팩토링 근거·결정 기록은 [aiDoc](aiDoc/README.md)에 있다.

## 1. 브랜치 (git flow, D154)

- `main`은 **운영 기준**이다. 지금 운영 중인 앱은 `main`에서 빌드해 돈다. 운영 반영(릴리스) 때만 `develop` → `main`으로 병합한다.
- `develop`은 **개발 통합** 브랜치다. 작업 브랜치(`<모델>/<계획번호>-<짧은 이름>`, 예: `claude/4-2-outer-surface`)는 `develop`에서 만들고 끝나면 `develop`에 병합한다.
- 운영 긴급 수정(hotfix)은 `main`에서 만들어 `main`과 `develop` 양쪽에 병합한다.
- `main` 병합·운영 재기동은 데브 지시 뒤에 한다. `main`에 직접 개발 커밋을 푸시하지 않는다.
- 원본 체크아웃(`C:\ai\autoStock`)은 운영 앱이 쓰는 곳이다. 작업은 별도 작업트리에서 한다.

## 2. 거래 안전

- **거래 로직은 바깥 면 작업(VALUNIVO 4-2)에서 바꾸지 않는다.** 입출력·알림·문서·화면만 공통 표준에 맞춘다.
- 코드 변경·재기동은 **장 마감 후·주말**에 한다(D132).
- **운영도 모의 서버로 먼저 운영한다(D239).** 기본 프로필은 모의 서버(`mockapi.kiwoom.com`)다. `live` 프로필·실전 키(`KIWOOM_LIVE_*`)는 데브가 모의 운영 기간·기준을 정하고 지시한 뒤에만 쓴다. 모의 키로의 폴백은 없다(실전 키 없이 `live`로 띄우면 기동 실패가 의도된 동작).
- 실전 키·봇 토큰·계좌 정보는 Git·대화·로그에 남기지 않는다. 파일 이름에 계좌번호가 들어간 키 파일도 이름을 출력하지 않는다.

## 3. 설정과 비밀값 (D213)

- id·pw·키의 원본은 Git 밖 `C:\ai\VALUNIVO-runtime\env-input\{development,production}.input.env`다. 지금 autoStock은 아직 `.env`(`load_env.ps1`)와 하드코딩된 DB 계정(`autostock`)을 쓴다. 입력 파일에서 생성한 설정으로 옮기는 일은 VALUNIVO 1-1(autoStock D6)에서 한다.
- `.env`·키 파일·DB 덤프는 커밋하지 않는다(`.gitignore`).

## 4. 그 밖

- 진행 설명·보고·문서는 한국어로 쓴다(코드 식별자·명령·커밋 메시지 예외).
- 리포 밖 규칙(`C:\claude\CLAUDE.md`, `coding-rules.md`)과 메모리 사본은 `aiDoc/claude-rules`·`aiDoc/claude-memory`에 있다(aiDoc/README 인계 메모).
- 개인 사이드 프로젝트 전용 PC다. 회사 업무 도구·내용·계정 식별자를 쓰지 않는다(D122).
