# 운영 PC 이전 — git으로 옮기고 비밀 값만 외장 디스크로 (2026-10-24)

- 요청(사용자):
  - 10:25 "pc 변경 예정입니다. git 커밋 푸시 점검 후 db등을 백업해 주세요"
  - 13:23 "백업관련된것도 git push합니다", "다른 pc에서도 ai로 작업 할꺼 백업을 이용해서 복구하는 관련 안내 md도 작성합니다"
  - 16:25 "외장으로 옮기지 않고 git으로 합니다. 비밀값등만 외장하들 이동합니다." → 저장소가 공개라는 점을 알린 뒤 **공개 저장소에 올림**, **data도 올림**을 선택
- 스크립트: `scripts\pc_migration_backup.bat`(→ `.ps1`, 옛 PC — DB 덤프·로그를 `backup\`에 넣고 커밋·push, 비밀 값 폴더), `scripts\restore_db.ps1`(새 PC — DB 복원)
- 새 PC에서 AI와 함께 복구할 때의 안내: [restore-from-backup.md](restore-from-backup.md) — 비밀 값 폴더 맨 위 `RESTORE_GUIDE.md`, claude.ai 프로젝트 문서로도 있다
- 기준: 옛 PC의 저장소 `D:\myApp\autoStock`, DB 컨테이너 `autostock-db`(TimescaleDB HA pg17.11-ts2.30.2, 다이제스트 고정), 백업 폴더 `D:\backup\autostock`

## 0. 꼭 지킬 것

1. **두 PC에서 앱을 동시에 켜지 않는다.** 같은 키움 모의 계좌로 두 앱이 주문하면 주문이 겹친다. 새 PC 앱을 켜기 전에 옛 PC 앱을 끄고 자동 시작을 해제한다(`scripts\stop_autostock.bat` → `scripts\uninstall_autostart.bat`).
2. **보유가 있다**: 10/23 15:26 종가→익일(ON-CLOSE) 매수 파인엠텍(441270) 85주 @11,150이 주말을 넘긴다. 10/26(월) 09:00:30에 팔려면 그 전에 새 PC에 **DB가 복원돼 있어야** 한다 — 전략별 보유(`strategy_positions`)가 DB에 있다. DB 없이 켜면 계좌 잔고는 복원되지만 어느 전략 보유인지 몰라 전략이 팔지 못한다.
3. **공개 저장소**에 data·전략 문서·DB 덤프(주문·체결·손익 기록)·로그가 올라간다 — 사용자 결정. 비밀 값(`.env`의 키움 모의 키·DART·텔레그램·FRED·ECOS 키, 키움 실전 키 파일)은 올리지 않는다: `.gitignore`가 빼고, 스크립트가 커밋 직전에 파일 이름을 다시 본다.
4. 비밀 값 폴더는 외장 디스크로만 옮긴다(클라우드 공유·메신저 금지). 옮긴 뒤 외장 디스크 사본도 지운다.
5. 옛 PC는 새 PC가 거래일 하루를 문제없이 돈 뒤까지 지우지 않는다(되돌릴 곳).

## 1. 옛 PC — 백업

1. `scripts\pc_migration_backup.bat` 더블클릭. 하는 일:

   | 단계 | 결과 |
   |---|---|
   | DB 덤프 | `backup\db\autostock_yyyyMMdd_HHmm.dump` · `.versions.txt` · `rowcounts.txt`(예전 덤프는 작업 트리에서 지우고 git 기록에 남김) |
   | 로그 스냅숏 | `backup\app-logs\`(app\logs 미러, 잠금 파일 제외) |
   | 커밋·push | `backup\`, `data\`, `STRATEGY.md`, `BACKTEST_REPORT.md`, `docs\measured\` → 커밋 `backup: PC 이전 스냅숏 — …` → `git push origin main`(처음엔 data 675MB라 몇 분). 비밀 파일 이름이 끼면 커밋하지 않고 멈춘다. 다른 코드 변경은 커밋하지 않고 알리기만 |
   | 비밀 값 폴더 | `D:\backup\autostock\secrets_yyyyMMdd_HHmm\` — `.env`, 키움 키 파일 2개, `.vscode\`, (있으면) `keys\`, `RESTORE_GUIDE.md`, `MANIFEST.csv`, `migration.log` |
2. 창 마지막 줄 `[O] MIGRATION BACKUP OK`, `migration.log`의 `[O] git push origin main 완료` 확인
3. `secrets_…` 폴더만 외장 디스크로 복사한다
4. 옛 PC를 더 쓰다가 옮기면 옮기기 **직전에** 1을 다시 실행한다(그 사이 매매가 DB에 쌓인다)
5. 옛 PC 앱 정지·자동 시작 해제(0절 1). 작업 스케줄러에 DB 백업(16:30)을 등록해 뒀다면 끈다

## 2. 새 PC — 설치

경로를 옛 PC와 같게 두면 스크립트를 고치지 않아도 된다 — `D:\myApp\autoStock`(저장소), `D:\jdks\jdk-21.0.12.101-hotspot`(JDK — `scripts\start_autostock.bat`에 고정), `D:\backup\autostock`(백업). D 드라이브가 없으면 `scripts\*.bat`·`*.ps1`의 `D:\` 경로를 바꿔야 한다.

| 도구 | 비고 |
|---|---|
| Git for Windows | `core.autocrlf=true`(설치 기본) — 옛 PC 작업 트리가 CRLF였다. `user.name`·`user.email` 설정 |
| JDK 21 | `D:\jdks\jdk-21.0.12.101-hotspot` 또는 start_autostock.bat의 `JAVA_HOME` 수정 |
| Docker Desktop(WSL2) | 설정 → General → "Start when you sign in" |
| Node.js 22 + npm | 화면 빌드할 때만(`frontend\`에서 `npm ci` 한 번) — 빌드 결과는 저장소에 있다 |
| Python 3.10+ | `scripts\*.py`(공모주 이력 등)를 쓸 때만 |

## 3. 새 PC — 복원

[restore-from-backup.md](restore-from-backup.md) 3절 그대로: `git clone` → 비밀 값 복사 → (선택) 로그 → `setup_docker.ps1` → `restore_db.ps1 -DumpFile .\backup\db\autostock_….dump` → `run_tests.bat` → 옛 PC 앱이 꺼진 것을 확인하고 `start_autostock.bat`.

## 4. 새 PC — 다시 등록할 것

- 자동 시작: `scripts\install_autostart.bat`
- DB 백업 작업 스케줄러: 평일 16:30 `scripts\backup_db.ps1`(RUNBOOK 8절)
- 전원: 장 시간 절전·덮개 닫기 설정(노트북이면 충전 중 덮개 닫기 '아무것도 안 함' — 10/12·10/21 점검)
- **키움 허용 IP**: 공인 IP가 바뀌면 토큰 발급이 `8010`으로 실패한다 — openapi.kiwoom.com → App Key 관리 → 허용 IP(최대 10개)에 새 IP 추가. 같은 집 공유기면 보통 그대로다
- 텔레그램·DART·FRED·ECOS는 `.env`를 옮겼으면 그대로 동작한다

## 5. 이번 이전 시점의 상태와 기록(2026-10-24)

- 코드: 10/23 변경 커밋 5개(대시보드 계좌 잔고 `f2fb927`, 매도 수익률 알림 `52eba2b`, 기준가 재시도 `e69f0ae`, 화면 `bb584fd`·빌드 `18b0320`) + 이전 스크립트·문서. 10/23 09:53 옛 PC 전체 시험 통과.
- 13:32 1차 백업(외장 디스크 방식, `D:\backup\autostock\migration_20261024_1332\`): push `3448afc..aff9904`, DB 덤프 5,344,693 bytes(목차 258개, 테이블 18개 — `strategy_positions` 1행 = 파인엠텍 85주), 파일 5,005개 673.8MB. 이 폴더에는 비밀 값과 전체 사본이 함께 있다 — git 방식으로 바꾼 뒤에는 외장 디스크로 옮길 필요가 없다(옛 PC에 두거나 사용자가 지운다).
- 16:25 git 방식으로 바꿈: `.gitignore`에서 `data/`·`STRATEGY.md`·`BACKTEST_REPORT.md` 제외를 풀고 `docs/measured/*.log`·`backup/**/*.log`를 허용, `.gitattributes`로 `data/**`·`backup/**`는 줄바꿈 변환 없음.
- 올리기 전 비밀 값 검사(값은 출력하지 않고 위치만): `.env`의 값과 키움 키 파일 내용을 data·로그(gz 풀어서)·전략 문서·실측 로그·DB 덤프(압축 블록을 풀어서)에서 찾았다 — 결과는 아래 6절.

## 6. 비밀 값 검사 결과(2026-10-24 16:40경, 올리기 전)

- 찾은 값: `.env`의 길이 8자 이상 값 8개(DART·공공데이터포털·ECOS·FRED 키, 키움 모의 앱 키·시크릿, 텔레그램 봇 토큰·채팅 ID) + 키움 실전 키 파일 2개 내용 = 10개. 값은 출력하지 않고 일치 위치만 셌다.
- 대상과 결과 — **모두 0건**:

  | 대상 | 파일 | 크기 | 일치 |
  |---|---|---|---|
  | `data/`(universe·events·ipo·minutes·measured·fscore·diagnostics·루트 CSV) | 4,976 | 약 690MB | 0 |
  | `app/logs/`(gz는 풀어서), `docs/measured/`, `STRATEGY.md`, `BACKTEST_REPORT.md`, `.vscode/` | 위 첫 묶음에 포함 | — | 0 |
  | DB 덤프 `autostock_20261024_1332.dump` — 압축 데이터 블록 68개를 청크 구조대로 이어 풀어서(5.21MB → 17.3MB) + 나머지 원문 | 1 | 5.3MB | 0 |
- 계좌번호: `data/measured`의 `acnt_no` 21곳·FID `9201` 158곳 모두 `****`로 가려져 있다(`OrderNoticeRecorder`·TR 실측 작업이 가림). 운영 로그에는 `acnt_no`·계좌번호 언급 0.
- 검사 스크립트는 저장소에 넣지 않았다(일회성). 이후 백업은 `pc_migration_backup.ps1`이 커밋 직전에 비밀 파일 이름(`.env`·`*_appkey*`·`*_secretkey*`·`*.pem`·`keys/`)만 다시 본다.
