# 백업으로 autoStock 복구하기 — AI 작업자용 안내

- 작성: 2026-10-24(운영 PC 이전, 사용자 요청 "다른 pc에서도 ai로 작업 할꺼 백업을 이용해서 복구하는 관련 안내 md도 작성"). 같은 날 "외장으로 옮기지 않고 git으로, 비밀값등만 외장으로"에 맞춰 고침.
- 대상: 새 PC에서 사용자와 함께 일하는 AI(Claude 등)와 사용자. 이 파일은 저장소(`aiDoc/restore-from-backup.md`), 비밀 값 폴더 맨 위(`RESTORE_GUIDE.md`), claude.ai "프로그램매매" 프로젝트 문서에 같은 내용으로 있다.
- 이번 이전의 구체 사정(주말 보유 종목 등): [pc-migration-2026-10-24.md](pc-migration-2026-10-24.md)

## 0. 이 문서를 읽는 AI에게 — 먼저 지킬 것

- 사용자는 **데브**다. **한국어**로 답한다. 코드·식별자·로그 원문은 그대로 둔다.
- 이 시스템은 키움증권 REST API로 한국 주식을 자동매매한다. 지금은 **키움 모의 서버(mockapi.kiwoom.com) 모의투자** 기간이다. 앱 화면의 "LIVE"는 "브로커로 주문을 보낸다"는 뜻일 뿐 실계좌가 아니다. 실전 키는 월 1회 접속 점검용이다 — **실전 키로 주문 TR을 부르지 않는다**.
- 저장소 `hobbiespark/autoStock`은 **공개(Public)** 저장소다. 2026-10-24부터 수집 데이터(`data/`)·전략 문서(`STRATEGY.md`·`BACKTEST_REPORT.md`)·DB 덤프(`backup/db/`)·로그 스냅숏(`backup/app-logs/`)도 커밋한다(사용자가 공개를 알고 결정). **비밀 값은 절대 커밋하지 않는다.**
- 안전 규칙(복구 중에도 그대로):
  1. **옛 PC 앱이 꺼진 것을 사용자에게 확인받기 전에는 새 PC 앱을 켜지 않는다.** 같은 계좌로 두 앱이 주문하면 주문이 겹친다.
  2. **DB를 복원하기 전에 앱을 켜지 않는다.** 앱이 먼저 뜨면 Flyway가 빈 스키마를 만들고, 전략별 보유(`strategy_positions`)가 없어 보유 종목을 팔지 못한다.
  3. 비밀 값 — `.env`, `*_appkey*.txt`, `*_secretkey*.txt`, `keys\` — 의 **내용을 출력하지 않는다**(cat·type·Get-Content 금지, 존재 여부는 `Test-Path`로). `git add -f`로 강제로 넣지 않는다(`.gitignore`가 막는다). 채팅으로 키를 받지 않는다 — 없으면 사용자가 직접 파일에 넣는다.
  4. 처음 기동은 장 시간(거래일 09:00~15:30 KST)을 피한다 — 장 마감 뒤나 08:30 전.
  5. 비밀 값 폴더·옛 PC의 파일을 지우지 않는다. 새 PC가 거래일 하루를 문제없이 돈 뒤 사용자가 정한다.
  6. 킬스위치 해제·실계좌 전환·전략 숫자 변경은 사용자 결정이다.

## 1. 무엇이 어디로 옮겨지나

옛 PC에서 `scripts\pc_migration_backup.bat`(→ `pc_migration_backup.ps1`)이 만든다.

| 무엇 | 어디로 | 새 PC에서 |
|---|---|---|
| 코드·문서·화면 빌드 | GitHub `hobbiespark/autoStock` (main) | `git clone` |
| 수집 데이터 `data/`(약 675MB), `STRATEGY.md`, `BACKTEST_REPORT.md`, `docs/measured/*.log` | GitHub(같은 저장소, 바이트 그대로 — `.gitattributes`의 `-text`) | clone하면 제자리에 있다 |
| DB 덤프 `backup/db/autostock_yyyyMMdd_HHmm.dump` · `.versions.txt` · `rowcounts.txt` | GitHub(가장 최근 것 하나만 작업 트리에, 예전 것은 git 기록에) | `scripts\restore_db.ps1` |
| 운영 로그 스냅숏 `backup/app-logs/` | GitHub | (선택) `app\logs\`로 복사 — 로그 점검 이력 |
| `.env`, 키움 키 파일 2개(`*_appkey*.txt`·`*_secretkey*.txt`), `.vscode\`, (있으면) `D:\keys` | **외장 디스크** — `D:\backup\autostock\secrets_yyyyMMdd_HHmm\` | 저장소 루트·`D:\keys`로 복사 |
| `RESTORE_GUIDE.md`(이 문서)·`MANIFEST.csv`(SHA-256)·`migration.log` | 비밀 값 폴더 안 | 먼저 읽기·복사 대조·실행 기록 |

비밀 값 폴더의 `migration.log`에 `[O] 끝`과 `git push origin main 완료`, 커밋 해시(`HEAD …`)가 있어야 정상이다. 복사가 온전한지(외장 디스크 → 새 PC 뒤):

```powershell
$root = "E:\secrets_yyyyMMdd_HHmm"   # 실제 경로로
$bad = Import-Csv "$root\MANIFEST.csv" | Where-Object {
    (Get-FileHash -LiteralPath (Join-Path $root $_.path) -Algorithm SHA256).Hash -ne $_.sha256 }
if ($bad) { $bad | Select-Object path } else { "[O] MANIFEST 전부 일치" }
Select-String -Path "$root\migration.log" -Pattern "\[X\]|\[O\] 끝|HEAD"
```

## 2. 새 PC 준비 확인

경로는 옛 PC와 같게 둔다 — 스크립트에 `D:\` 경로가 박혀 있다(`start_autostock.bat`의 `JAVA_HOME`, `install_autostart.bat`, `backup_db.ps1`·`restore_check.ps1`·`pc_migration_backup.ps1`의 백업 폴더 등). D 드라이브가 없으면 그 경로부터 바꾼다.

| 확인 | 명령 | 기대 |
|---|---|---|
| Git | `git --version` | 설치됨, `git config --global core.autocrlf` → `true`, `git config --global user.name`·`user.email` 설정 |
| JDK 21 | `D:\jdks\jdk-21.0.12.101-hotspot\bin\java -version` | 21.x (시스템 기본 Java가 11이어도 된다 — 스크립트가 JAVA_HOME을 정한다) |
| Docker Desktop(WSL2) | `docker version` | Server 응답. 설정 "Start when you sign in" 켜기 |
| Node.js 22 | `node -v` | 화면 빌드 때만 필요 |
| Python 3.10+ | `python --version` | `scripts\*.py`를 쓸 때만 |

## 3. 복구 순서 — 한 단계씩, 확인하고 다음으로

```powershell
# 1) 코드·데이터·DB 덤프 — GitHub에서(처음엔 data 때문에 몇 분)
git clone https://github.com/hobbiespark/autoStock D:\myApp\autoStock
git -C D:\myApp\autoStock log -1 --format="%h %s"     # migration.log의 HEAD와 같거나 그 뒤여야 한다
Test-Path D:\myApp\autoStock\data\universe            # True
Get-ChildItem D:\myApp\autoStock\backup\db            # autostock_*.dump · .versions.txt · rowcounts.txt

# 2) 비밀 값 — 외장 디스크에서(내용은 열지 않는다)
$s = "E:\secrets_yyyyMMdd_HHmm"
Copy-Item "$s\.env" D:\myApp\autoStock\
Get-ChildItem $s -File | Where-Object { $_.Name -like "*_appkey*.txt" -or $_.Name -like "*_secretkey*.txt" } |
    Copy-Item -Destination D:\myApp\autoStock\
if (Test-Path "$s\.vscode") { robocopy "$s\.vscode" D:\myApp\autoStock\.vscode /E }
if (Test-Path "$s\keys") { robocopy "$s\keys" D:\keys /E }
git -C D:\myApp\autoStock status --short                # .env·키 파일이 보이면 안 된다(무시 대상)

# 3) (선택) 로그 이력 — 앱 로그 폴더로
robocopy D:\myApp\autoStock\backup\app-logs D:\myApp\autoStock\app\logs /E

# 4) DB 컨테이너(빈 DB) — 앱은 아직 켜지 않는다
cd D:\myApp\autoStock
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\setup_docker.ps1        # [O] … healthy

# 5) DB 복원 — backup\db의 덤프(파일 이름은 Get-ChildItem으로 본 것)
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\restore_db.ps1 -DumpFile .\backup\db\autostock_yyyyMMdd_HHmm.dump
#   기대: 테이블별 "일치", "[.] 복원본 최신 마이그레이션: V20"(이후 마이그레이션이 있으면 그 번호), "[O] 결과: 복원 완료"

# 6) 시험(앱 꺼진 상태, Docker 필요 — DB 시험은 운영과 같은 이미지)
.\scripts\run_tests.bat                                # build\test-run.log 마지막 줄 "exit code 0"

# 7) 사용자에게 "옛 PC 앱이 꺼졌는가" 확인받은 뒤 기동
.\scripts\start_autostock.bat
```

기동 뒤 `app\logs\autostock.log`에서 볼 것:

| 로그 | 뜻 |
|---|---|
| `Successfully validated N migrations` · `Schema "public" is up to date` | DB가 복원본 그대로(새 마이그레이션 없음) |
| `포지션 복원(기동) — 잔고 조회 성공, 보유 N종목` | 키움 모의 서버 연결·잔고 정상 |
| `전략별 장부 복원 — …` | DB의 전략별 보유가 살아 있다(보유가 있을 때) |
| `운용 자금 20,000,000원 — …` · `다전략 슬리브: …` | 위험 설정 정상 |
| `WS 로그인 성공 … (REG 2건)` 뒤에 `105110` 거절 없음 | 실시간 시세·체결통보 정상 |
| ERROR 0 | — |

대시보드 `http://127.0.0.1:8080`: 주황 띠 "LIVE · 키움 모의 서버", 보유 포지션 카드의 계좌 총자산.

마지막으로 다시 등록할 것: `scripts\install_autostart.bat`(로그인 때 자동 시작), 작업 스케줄러 평일 16:30 `scripts\backup_db.ps1`(RUNBOOK 8절), 전원(장 시간 절전 끄기, 노트북이면 충전 중 덮개 닫기 '아무것도 안 함'), 키움 허용 IP(아래).

## 4. 막힐 때

| 증상 | 원인·조치 |
|---|---|
| 토큰 발급 `8010` | 공인 IP가 키움 허용 IP에 없다 — 사용자가 openapi.kiwoom.com → App Key 관리 → 허용 IP(최대 10개)에 새 IP 추가 |
| `restore_db.ps1`: "이미 테이블 N개가 있다" | 앱이 먼저 떠서 빈 스키마가 생겼다 — 앱을 끄고 `-Force`(DB를 지우고 새로 만든다)로 다시. 사용자에게 먼저 알린다 |
| `pg_restore` 실패·hypertable 오류 | 덤프와 컨테이너의 TimescaleDB 버전이 다르다 — `backup\db\*.versions.txt`의 image와 `infra\docker-compose.yml`의 다이제스트 비교 |
| `backup\db`가 옛 PC 마지막 상태보다 오래됐다 | 옛 PC에서 이전 직전에 `pc_migration_backup.bat`을 다시 돌리지 않았다 — 옛 PC가 살아 있으면 다시 돌리고 `git pull` |
| `start_autostock.bat`에서 Java 오류 | `D:\jdks\jdk-21.0.12.101-hotspot`가 없다 — JDK 설치 경로를 맞추거나 bat의 `JAVA_HOME` 수정 |
| `setup_docker.ps1` 실패 | Docker Desktop 미기동·WSL2 미설치 — 스크립트 안내대로 |
| PowerShell 스크립트 한글 깨짐·구문 오류 | `scripts\*.ps1`은 UTF-8 **BOM** + CRLF여야 한다(Windows PowerShell 5.1). BOM 없이 다시 저장하면 깨진다 |
| `.env` 없음·키 누락 | 추측해서 채우지 않는다 — 사용자가 키움·DART·텔레그램·FRED·ECOS 키를 넣는다(`.env.example` 참고) |
| `git push` 인증 창 | 사용자가 Git Credential Manager로 GitHub 로그인 |
| 텔레그램 알림이 안 옴 | `.env`의 `TELEGRAM_BOT_TOKEN`·`TELEGRAM_CHAT_ID`가 비었는지(값은 보지 않고 변수 존재만) |

## 5. 복구 뒤 이 프로젝트에서 계속 일할 때

- **상태의 기준 문서**(계획 전에 읽는다): `PROGRESS.md`(트랙·게이트·trial 기록), `docs/RUNBOOK.md`(운영·점검), `aiDoc/upgrade-2026-10/00-README.md`(작업 기록 — 맨 아래 날짜별 줄), 가장 최근 로그 점검 문서 `aiDoc/upgrade-2026-10/NN-log-review-YYYY-MM-DD.md`, 전략 사전 선언 `aiDoc/multi-strategy-paper-2026-10-16.md`·`aiDoc/ipo-subscription-strategy.md`, 결정 목록 `aiDoc/upgrade-2026-10/12-decisions.md`.
- **claude.ai 프로젝트 "프로그램매매"**의 지식 문서와 메모리는 사용자 계정에 붙어 있어 새 PC에서도 그대로 보인다(로컬 파일이 아니다). 새 PC의 Claude 데스크톱 앱에서 `D:\myApp\autoStock` 폴더를 연결해야 AI가 파일을 읽는다.
- 앞으로의 백업: `scripts\pc_migration_backup.bat`을 다시 돌리면 그때의 DB 덤프·data·로그를 커밋해 push하고 비밀 값 폴더를 새로 만든다. 앱이 `data\measured\`에 매일 파일을 쓰므로 `git status`에 data가 바뀐 것으로 보이는 것은 정상이다.
- 일하는 방식(지금까지의 관례):
  - 사용자 요청은 시각과 함께 원문을 문서에 남긴다. 로그 점검 문서는 `NN-log-review-YYYY-MM-DD.md` 형식(요청 → 로그 요약 → 고친 것 → 시험 → 사용자 할 일 → 남은 것).
  - 전략 규칙은 결과를 보기 전에 사전 선언하고, 결과를 본 뒤 숫자를 바꾸지 않는다 — 바꾸려면 새 trial을 선언한다(PROGRESS 3절).
  - 예약·반복 작업은 **앱 안에서만** 한다. AI의 외부 예약 확인 작업을 만들지 않는다(10/12 사용자 지시).
  - 분석·설계·요약 산출물은 시각화 없이 MD 파일로(사용자 선호).
- 코드 작업 규칙:
  - 줄바꿈: Java 파일은 대부분 CRLF, 일부 LF — 고칠 때 원래 줄바꿈을 유지한다. 화면(`frontend/src`)은 LF. git은 `core.autocrlf=true`로 다룬다(작업 트리 CRLF가 "변경"으로 보이면 이 설정이 빠진 것). `data/`·`backup/`은 줄바꿈 변환 없음(`.gitattributes`).
  - `scripts\*.ps1`은 UTF-8 BOM + CRLF, `*.bat`은 ASCII + CRLF.
  - 시험: `scripts\run_tests.bat`(앱을 끄고), 화면 번들: `scripts\build_frontend.bat` — 빌드 결과(`app/src/main/resources/static`)도 커밋한다.
  - AI 작업 환경에 JDK 21·인터넷이 없으면(예: 데스크톱 앱의 격리 Linux 환경) 컴파일·시험·push는 사용자가 위 bat으로 돌리고 결과 로그(`build\test-run.log`)를 AI가 읽는다.
  - 커밋은 작업 단위로 나누고, 메시지는 `feat|fix|docs|build(범위): 한국어 요약 (MM/DD)` + 본문(근거 문서) + `검증:` 줄. 작성자 `hobbiespark`. 공개 저장소이므로 커밋 전에 비밀 값·계좌번호가 들어가지 않았는지 확인한다(키움 체결통보 원문의 계좌번호 FID 9201은 `OrderNoticeRecorder`가 가린다).
- 운영 안전 설정(모의 프로필 `paper`): 일 손실 한도 −5%, 운용 자금 2,000만 원(`risk.capital.limit-krw`), 전략별 슬리브(C3 50%·단타 15%·종가→익일 15%·차트 기법 5종 각 4%). 실전 프로필 값은 따로다 — 바꾸지 않는다.
