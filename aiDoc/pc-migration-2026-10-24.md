# 운영 PC 이전 — 백업과 새 PC 복원 순서 (2026-10-24)

- 요청: 사용자 10/24 10:25 "pc 변경 예정입니다. git 커밋 푸시 점검 후 db등을 백업해 주세요"
- 스크립트: `scripts\pc_migration_backup.ps1`(옛 PC — 한 폴더에 모으기), `scripts\restore_db.ps1`(새 PC — DB 복원)
- 기준: 옛 PC의 저장소 `D:\myApp\autoStock`, DB 컨테이너 `autostock-db`(TimescaleDB HA pg17.11-ts2.30.2, 다이제스트 고정), 백업 폴더 `D:\backup\autostock`

## 0. 꼭 지킬 것

1. **두 PC에서 앱을 동시에 켜지 않는다.** 같은 키움 모의 계좌로 두 앱이 주문하면 주문이 겹친다. 새 PC 앱을 켜기 전에 옛 PC 앱을 끄고 자동 시작을 해제한다(`scripts\stop_autostock.bat` → `scripts\uninstall_autostart.bat`).
2. **보유가 있다**: 10/23 15:26 종가→익일(ON-CLOSE) 매수 파인엠텍(441270) 85주 @11,150이 주말을 넘긴다. 10/26(월) 09:00:30에 팔려면 그 전에 새 PC에 **DB가 복원돼 있어야** 한다 — 전략별 보유(`strategy_positions`)가 DB에 있다. DB 없이 켜면 계좌 잔고는 복원되지만 어느 전략 보유인지 몰라 전략이 팔지 못한다.
3. 백업 폴더에는 비밀 값(`.env`의 키움 모의 키·DART·텔레그램·FRED·ECOS 키, 키움 실전 키 파일)이 들어 있다 — 외장 디스크로 옮기고 클라우드 공유·메신저로 보내지 않는다. 옮긴 뒤에는 외장 디스크 사본도 지운다.
4. 옛 PC는 새 PC가 거래일 하루를 문제없이 돈 뒤까지 지우지 않는다(되돌릴 곳).

## 1. 옛 PC — 백업

1. git 커밋(이 문서와 함께 커밋됨) → `git push origin main`
2. `powershell -NoProfile -ExecutionPolicy Bypass -File D:\myApp\autoStock\scripts\pc_migration_backup.ps1 -Push`
   - 결과 `D:\backup\autostock\migration_yyyyMMdd_HHmm\`:

     | 경로 | 내용 |
     |---|---|
     | `autoStock.bundle` | 저장소 전체(모든 브랜치) — 푸시가 안 돼도 이것으로 clone |
     | `db\autostock_*.dump` · `*.versions.txt` · `rowcounts.txt` | DB 덤프(pg_dump -Fc), 이미지·PostgreSQL·확장 버전, 테이블별 행 수 |
     | `project-local\` | git 밖 파일: `.env`, 키움 키 파일 2개, `STRATEGY.md`, `BACKTEST_REPORT.md`, `data\`(약 675MB), `app\logs\`, `docs\measured\*.log`, `.vscode\` |
     | `keys\` | `D:\keys`가 있으면(실전 키 점검용 — RUNBOOK 8절) |
     | `MANIFEST.csv` · `migration.log` | 파일별 크기·SHA-256, 실행 기록 |
3. 폴더를 외장 디스크로 복사한다. 새 PC에서 `MANIFEST.csv`의 SHA-256으로 복사가 온전한지 볼 수 있다.
4. 옛 PC 앱 정지·자동 시작 해제(0절 1). 작업 스케줄러에 DB 백업(16:30)을 등록해 뒀다면 끈다.

## 2. 새 PC — 설치

경로를 옛 PC와 같게 두면 스크립트를 고치지 않아도 된다 — `D:\myApp\autoStock`(저장소), `D:\jdks\jdk-21.0.12.101-hotspot`(JDK — `scripts\start_autostock.bat`에 고정), `D:\backup\autostock`(백업). D 드라이브가 없으면 `scripts\*.bat`·`*.ps1`의 `D:\` 경로(start_autostock·install_autostart·backup_db·restore_check·setup_docker·load_env 등 8개)를 바꿔야 한다.

| 도구 | 비고 |
|---|---|
| Git for Windows | `core.autocrlf=true`(설치 기본) — 옛 PC 작업 트리가 CRLF였다 |
| JDK 21 | `D:\jdks\jdk-21.0.12.101-hotspot` 또는 start_autostock.bat의 `JAVA_HOME` 수정 |
| Docker Desktop(WSL2) | 설정 → General → "Start when you sign in" |
| Node.js 22 + npm | 화면 빌드할 때만(`frontend\`에서 `npm ci` 한 번) — 빌드 결과는 저장소에 있다 |
| Python 3.10+ | `scripts\*.py`(공모주 이력 등)를 쓸 때만 |

## 3. 새 PC — 복원

```powershell
# 1) 코드 — GitHub에서(푸시가 됐으면)
git clone https://github.com/hobbiespark/autoStock D:\myApp\autoStock
#    또는 번들에서(푸시가 안 됐으면) — 뒤에 원격 주소를 GitHub로 바꾼다
git clone E:\migration_yyyyMMdd_HHmm\autoStock.bundle D:\myApp\autoStock
git -C D:\myApp\autoStock remote set-url origin https://github.com/hobbiespark/autoStock

# 2) git 밖 파일
robocopy E:\migration_yyyyMMdd_HHmm\project-local D:\myApp\autoStock /E
robocopy E:\migration_yyyyMMdd_HHmm\keys D:\keys /E          # keys 폴더가 있으면

# 3) DB — 빈 컨테이너를 띄우고(앱은 아직 켜지 않는다) 덤프를 복원
cd D:\myApp\autoStock
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\setup_docker.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\restore_db.ps1 -DumpFile E:\migration_yyyyMMdd_HHmm\db\autostock_yyyyMMdd_HHmm.dump
```

- `restore_db.ps1`은 테이블별 행 수를 백업 때 값과 나란히 보이고, 마지막에 `[O] 결과: 복원 완료`와 최신 마이그레이션(V20)을 찍는다. 덤프 뒤에 앱이 쓴 행이 있으면 "차이"로 보인다(주말이면 보통 일치).
- 앱을 먼저 켜 버렸으면(Flyway가 빈 스키마를 만든 상태) `restore_db.ps1`이 멈춘다 — 앱을 끄고 `-Force`(DB를 지우고 새로 만든다)로 다시.

## 4. 새 PC — 첫 실행과 다시 등록할 것

1. `scripts\run_tests.bat` — 전체 시험(DB 시험은 Docker 필요)
2. 옛 PC 앱이 꺼져 있는지 다시 확인 → `scripts\start_autostock.bat`
3. 로그·화면 확인: `포지션 복원(기동) — 잔고 조회 성공, 보유 1종목: 파인엠텍(441270) 85주`, `전략별 장부 복원 — ON-CLOSE(파인엠텍(441270) 85주)`, `WS 로그인 성공`, 대시보드 주황 띠(모의 서버)·계좌 총자산
4. 다시 등록:
   - 자동 시작: `scripts\install_autostart.bat`
   - DB 백업 작업 스케줄러: 평일 16:30 `scripts\backup_db.ps1`(RUNBOOK 8절)
   - 전원: 장 시간 절전·덮개 닫기 설정(노트북이면 충전 중 덮개 닫기 '아무것도 안 함' — 10/12·10/21 점검)
   - **키움 허용 IP**: 공인 IP가 바뀌면 토큰 발급이 `8010`으로 실패한다 — openapi.kiwoom.com → App Key 관리 → 허용 IP(최대 10개)에 새 IP 추가. 같은 집 공유기면 보통 그대로다.
   - 텔레그램·DART·FRED·ECOS는 `.env`를 옮겼으면 그대로 동작한다.

## 5. 이번 이전 시점의 상태(2026-10-24)

- 코드: 10/23 변경 커밋 5개(대시보드 계좌 잔고 `f2fb927`, 매도 수익률 알림 `52eba2b`, 기준가 재시도 `e69f0ae`, 화면 `bb584fd`·빌드 `18b0320`) + 이 문서·스크립트. 10/23 09:53 옛 PC 전체 시험 통과.
- 앱: 옛 PC PID 17848(10/23 10:27 기동, 10/23 변경 반영) — 오류 0.
- 결정·trial 진행 상태는 그대로 옮겨진다(DB·문서). 성과 측정은 10/23부터(`monitor.performance.since`).
