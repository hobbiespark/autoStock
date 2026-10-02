# DB 전환 — TimescaleDB HA 이미지(PostgreSQL 17) (S1 1단계, 2026-10-02)

- 날짜: 2026-10-02
- 근거: `data-platform-postgres.md` 8~10절(S1 계획, 결정 D-15~D-19), 6절 실측(이미지·복원·정렬 순서)
- 진행: 10/2 18:04 사용자가 장 마감 뒤 앱을 끄고 "오늘 장 종료. 다음 작업 가능" → D-15~D-19 권고안대로 진행
  - 같은 날 낮에 사용자가 고른 "공모주 지표 자동 입력"이 끝나, 남아 있던 권장 작업("DB 전환 준비")을 이어서 했다.
- 상태: **2026-10-02 18:54 운영 전환 완료**(PC 실행 — 테이블 12개 행 수 일치, 백업·복원 리허설 통과, 18:55 앱 기동·V11 적용). 분봉 하이퍼테이블(V12)은 전환 확인 뒤 `minute-bars-db.md`로 반영(5절).

## 1. 무엇이 바뀌나

| 항목 | 전 | 후 |
|---|---|---|
| DB 이미지 | `postgres:16-alpine` | `timescale/timescaledb-ha:pg17.11-ts2.30.2`, 다이제스트 `sha256:2fcc39a5…b519c2`로 고정 |
| 엔진 | PostgreSQL 16.15(musl) | PostgreSQL 17.11(glibc) + TimescaleDB 2.30.2 + 툴킷 1.26.0. pgvector 0.8.6 등 확장 96개를 쓸 수 있다 |
| 데이터 볼륨 | `infra_pgdata` → `/var/lib/postgresql/data` | `infra_pgdata17` → `/home/postgres/pgdata`. 옛 볼륨은 지우지 않는다(롤백용) |
| 기동 설정 | 이미지 기본값 | 데이터 체크섬 켬, 텔레메트리 끔, 튜닝 기준 2GB·2CPU·연결 50, 사전 적재 `timescaledb,pg_textsearch,pg_stat_statements,pg_prewarm`, 공유 메모리 256MB, healthcheck는 TCP |
| 새 DB 로캘·시간대 | en_US.utf8 · UTC | C.UTF-8 · Etc/UTC. 정렬은 둘 다 코드포인트 순이라 결과가 같다(6.2절 실측) |
| 접속 | localhost:5432, autostock/autostock | 같다. 앱 설정은 바꾸지 않는다 |

- 튜닝 결과(실측): shared_buffers 512MB, work_mem 16MB, maintenance_work_mem 256MB, effective_cache_size 1.5GB, max_worker_processes 21, timescaledb.max_background_workers 16.
- DB 테스트도 같은 이미지로 돈다(D-18, 4절).

## 2. 결정 — D-15~D-19 권고안 적용

| ID | 결정 | 반영 |
|---|---|---|
| D-15 | 이미지 `timescale/timescaledb-ha`, 다이제스트 고정 | `infra/docker-compose.timescale.yml`, `PostgresTestDatabase.IMAGE` |
| D-16 | PostgreSQL 17 | 덤프·복원으로 옮긴다(pg_upgrade 안 씀) |
| D-17 | 10/3~10/5 연휴(휴장)에 전환 | 사용자가 `switch_db_timescale.ps1` 실행. 앱은 10/2 18:04에 꺼졌다 |
| D-18 | DB 테스트는 운영 이미지로만 — Testcontainers 또는 외부 DB 주소, 내장 PG(zonky) 제거 | `support/PostgresTestDatabase`, `PostgresAvailableCondition`, `build.gradle`, CI |
| D-19 | 학습 실험은 랩 컨테이너(복원 리허설 겸용) | `restore_check.ps1 -Lab` → `autostock-lab`(127.0.0.1:5433) |

- 되돌릴 수 있는 결정이다. 옛 볼륨과 전환 전 덤프가 남고, `rollback_db_pg16.ps1`로 되돌린다(6절).

## 3. 전환 절차 (PC, 앱이 꺼진 장외)

1. (선택) 사전 점검만: `powershell -NoProfile -ExecutionPolicy Bypass -File D:\myApp\autoStock\scripts\switch_db_timescale.ps1 -CheckOnly`
   - Docker·앱 꺼짐·운영 컨테이너·볼륨을 확인하고 새 이미지(약 870MB)를 미리 받는다. 아무것도 바꾸지 않는다.
2. 전환: 같은 명령을 `-CheckOnly` 없이 실행한다. 이미지를 받은 뒤라면 1분 안팎이다(리허설 14초).
3. 결과 확인: 마지막 줄 `[O] 전환 완료`, 행 수 대조 "모두 일치", `[O] 복원 리허설 통과`.
   - 로그: `app\logs\db-switch-yyyyMMdd_HHmm.log`. 같은 파일이 `D:\backup\autostock`에도 남는다.
4. 앱 기동: `scripts\start_autostock.bat`. Flyway가 V11(공모주 공모 종류·지표 출처)을 적용한다.
   - 확인: 로그 `Migrating schema "public" to version "11 …"`, `공모주 수집 완료 — …`.

### 스크립트가 하는 일 (`scripts/switch_db_timescale.ps1`)

1. **사전 점검:** Docker 응답, 앱 꺼짐(8080 대기 없음), 운영 컨테이너 이미지(`postgres:16-alpine`)·compose 프로젝트(`infra`)·볼륨(`infra_pgdata`), 새 볼륨 없음. 이어서 새 이미지를 받는다.
2. **덤프:** 옛 DB를 `pg_dump -Fc`로 덤프하고 목차를 확인한 뒤 `D:\backup\autostock\autostock_preswitch_yyyyMMdd_HHmm.dump`로 꺼낸다.
   - 이 이름은 `backup_db.ps1`의 보존 정리 대상이 아니라 지워지지 않는다.
3. **행 수 기록:** public 스키마의 모든 테이블을 센다.
4. **새 DB 기동:** 옛 컨테이너를 중지·삭제한다(볼륨은 그대로). 새 정의로 띄운 뒤 healthy를 기다리고 버전·확장·설정을 확인한다(PostgreSQL 17, timescaledb 2.30.2).
5. **복원:** `timescaledb_pre_restore()` → `pg_restore --no-owner --exit-on-error` → `timescaledb_post_restore()` → `ANALYZE` 순서. 이어서 행 수를 대조한다(한 테이블이라도 다르면 실패).
6. **마무리:** `pg_stat_statements` 확장을 만들고 compose 파일을 바꾼다.
   - `docker-compose.yml` ← 새 정의.
   - 옛 정의는 `docker-compose.pg16.yml`(롤백용).
   - `docker-compose.timescale.yml`은 없어진다.
7. **새 형식 백업·복원 리허설:** `backup_db.ps1`와 `restore_check.ps1`(같은 이미지 임시 컨테이너)을 돌린다. `-SkipRehearsal`로 생략할 수 있다.

- **자동 되돌림:** 4~5단계에서 실패하면 새 컨테이너·새 볼륨을 지우고 옛 정의로 다시 띄운다. 옛 볼륨은 그대로라 앱은 지금 코드로 기동할 수 있다.
- **중간에 끊긴 경우(창 닫힘·PC 꺼짐):** 다시 실행하면 상태를 보고 판단한다.
  - 옛 컨테이너가 지워진 채면 옛 정의로 다시 띄운 뒤 처음부터 한다.
  - 새 컨테이너가 떠 있는데 compose가 옛 정의면 멈춘다. `-ReplaceNewVolume`을 붙여야 새 컨테이너·새 볼륨을 지우고 처음부터 한다.
- **이미 전환됐으면** 아무것도 하지 않는다(`[O] 이미 전환됨`). 전환 완료의 기준은 compose 파일 교체다.

## 4. 함께 바뀌는 것

- **백업 `scripts/backup_db.ps1`:**
  - 덤프마다 `autostock_yyyyMMdd_HHmm.versions.txt`를 남긴다(이미지·이미지 ID·PostgreSQL·확장 버전). TimescaleDB 덤프는 같은 확장 버전으로 복원해야 하기 때문이다(공식 문서).
  - 보존 정리 때 이 파일도 덤프와 함께 지운다.
  - TimescaleDB DB 덤프가 늘 내는 경고("circular foreign-key constraints … continuous_agg")는 정상이라 걸러 낸다.
- **복원 리허설 `scripts/restore_check.ps1`:**
  - 이미지를 운영 컨테이너에서 읽는다(`-Image`로 지정 가능). 전환 전에는 16-alpine, 전환 뒤에는 HA 이미지가 된다.
  - 임시 DB에 timescaledb가 있으면 pre/post restore로 복원한다.
  - 대조 테이블은 운영 DB의 public 테이블 전부다(목록을 손으로 고치지 않는다).
  - 준비 확인은 TCP로 한다(초기화 중 임시 서버를 건너뛴다). 컨테이너가 초기화 중 죽으면 로그를 보이고 바로 실패한다.
  - "최신 덤프"는 수정 시각으로 고른다. 이름순이면 `autostock_preswitch_…`가 늘 최신으로 잡힌다.
  - `-Lab`: 복원한 컨테이너를 `autostock-lab`(127.0.0.1:5433)으로 남긴다(D-19). 매번 최신 백업으로 새로 만든다. 끄기는 `docker rm -f autostock-lab`.
- **되돌리기 `scripts/rollback_db_pg16.ps1`:** 6절.
- **DB 테스트(D-18):**
  - `support/PostgresTestDatabase`:
    - 외부 DB 주소(`AUTOSTOCK_TEST_DB_URL`)가 있으면 그 DB를 쓴다.
    - 없고 Docker가 있으면 Testcontainers로 HA 이미지를 운영과 같은 사전 적재 설정으로 띄운다(`TS_TUNE_MEMORY=1GB`, 연결 100).
    - Testcontainers는 "이름:태그@다이제스트"를 받지 않아 다이제스트만 쓴다.
  - `support/PostgresAvailableCondition`: DB가 없으면 컨텍스트를 띄우기 전에 DB 테스트를 건너뛴다(경고 로그 1줄). `AUTOSTOCK_TEST_DB_REQUIRED=true`면 실패시킨다.
  - CI는 이 값을 켰다. 러너 Docker로 이미지를 받는 만큼 1분 안팎 느려진다(추정).
  - zonky 의존성 2개를 뺐다. 그 바람에 Testcontainers가 요구하는 `commons-compress 1.24.0`을 새로 받는다(오프라인 빌드에서 처음 한 번 실패 — 실측).

## 5. 함정과 주의

- **분봉 V12는 전환 뒤에 넣는다.** V12는 TimescaleDB 하이퍼테이블을 만든다. 옛 DB에서 앱을 띄우면 Flyway가 실패해 앱이 뜨지 않는다. (10/2 전환 확인 뒤 반영 — `minute-bars-db.md`)
  - 그래서 이번 반영에는 V12가 없다. 전환 확인 뒤 V12와 적재 잡 DB화를 반영한다(S1 2단계).
- **`docker-compose.timescale.yml`로 직접 `up` 하지 않는다.** 빈 DB가 새로 만들어진다. 전환 스크립트만 이 파일을 쓴다.
- **`docker compose down -v`를 쓰지 않는다.** 볼륨째 지워진다. 분봉은 1년이 지나면 키움에서 다시 받을 수 없다(RUNBOOK 6절도 고쳤다).
- **옛 볼륨 `infra_pgdata`:** 롤백용으로 남는다. 전환 뒤 2주 이상 문제가 없으면 지울지 사용자가 정한다(약 수십 MB).
- **Windows PowerShell 5.1:**
  - 네이티브 명령 인자 안의 큰따옴표를 망가뜨린다. 그래서 SQL은 파일로 넘기고(`docker cp` 후 `psql -f`), 컨테이너 라벨은 `{{json …}}`으로 받는다.
  - 한글 문자열 때문에 스크립트는 UTF-8 BOM으로 저장한다.
- **PowerShell 변수 이름은 대소문자를 가리지 않는다.** `$Project`와 `$script:project`가 같은 변수라 값이 지워졌다(리허설에서 발견, `$ComposeProject`로 고침).
- **timescaledb-tune은 연결 수 25 미만을 거부한다.** 초기화가 그 자리에서 죽는다(`maxConns must be 0 OR >= 25`). 복원 리허설 임시 컨테이너에서 실측했고, 그 값을 뺐다.
- **`docker cp`는 root 소유로 넣는다.** HA 이미지는 postgres 사용자로 실행하므로, 복사한 덤프·SQL을 `chmod 644`한 뒤 읽는다.
- **healthcheck는 TCP(`-h 127.0.0.1`)로 한다.** 초기화 중 임시 서버는 소켓만 연다. 소켓으로 보면 초기화가 끝나기 전에 healthy가 되고, 재시작 순간에 복원이 끊길 수 있다.
- **TimescaleDB 덤프 복원에는 pre/post restore가 필수다.** 빠뜨리면 "could not find hypertable" 오류가 난다(6.4절 실측). 병렬 복원(`-j`)은 쓰지 않는다.

## 6. 롤백 — `scripts/rollback_db_pg16.ps1`

- 전환이 끝난 뒤 문제가 생겼을 때 쓴다. 전환 도중 실패는 전환 스크립트가 스스로 되돌린다.
- 하는 일:
  1. 점검: 앱 꺼짐, 새 컨테이너, 옛 정의 파일, 옛 볼륨.
  2. 지금(새) DB를 `autostock_prerollback_yyyyMMdd_HHmm.dump`로 덤프한다. 전환 뒤 쌓인 데이터를 보존한다.
  3. 새 컨테이너를 삭제한다(새 볼륨은 남는다).
  4. compose 파일을 전환 전으로 되돌리고 옛 정의로 기동한다. 옛 볼륨이라 전환 직전 상태다.
  5. 버전·최신 마이그레이션을 출력한다.
- 잃는 것: 전환 뒤 새 DB에만 쌓인 데이터. 2단계 덤프에는 남는다. 휴장 중 전환이면 주문 데이터는 없다.
- 코드: TimescaleDB가 필요한 마이그레이션(V12 이후)이 들어간 뒤라면 옛 DB에서 앱이 뜨지 않는다. 그 커밋 이전 코드로 되돌린 뒤 기동한다.
- 다시 전환하려면 `switch_db_timescale.ps1 -ReplaceNewVolume`을 쓴다. 새 볼륨이 남아 있어서, 지워도 된다는 확인이 필요하다.

## 7. 검증 상태

- **컨테이너 리허설:**
  - 방법:
    - 가짜 `docker` 명령이 스크립트의 docker 호출을 실제 PostgreSQL로 실행했다. 옛 DB 역할은 16.15 alpine 대역, 새 DB 역할은 HA 17.11 이미지 파일시스템(chroot)이다.
    - 옛 DB에는 V10까지 올리고 데이터 22,522행(12테이블, Flyway 이력 포함)을 넣었다.
    - pwsh 7.4로 실행했다.
  - 결과:
    1. 사전 점검(`-CheckOnly`) 통과.
    2. 전체 전환: 덤프 → 새 DB 초기화(3초) → 복원 → 12테이블 행 수 모두 일치 → compose 교체 → 백업·버전 기록 → 복원 리허설 통과. 14초.
    3. 복원 리허설: HA 이미지 임시 DB에 pre/post restore로 복원, 행 수 일치, V10·TimescaleDB 2.30.2 확인. `-Lab` 남김·재생성·삭제.
    4. 앱 실행 중(8080 대기)이면 거부.
    5. 되돌리기: 새 DB 덤프 → 옛 DB(16.15, V10) 기동 → compose 원상 복구.
    6. 되돌린 뒤 재전환은 새 볼륨이 남아 거부, `-ReplaceNewVolume`이면 진행.
    7. 복원 실패를 주입하면 자동 되돌림: 새 컨테이너·볼륨 삭제, 옛 DB 복귀, 데이터 그대로.
    8. 중간에 끊긴 전환(옛 컨테이너 삭제 직후 강제 종료)은 다시 실행하면 감지해 멈추고, `-ReplaceNewVolume`으로 처음부터 성공.
  - 리허설에서 찾아 고친 결함 3건:
    1. 끊긴 전환을 "이미 전환됨"으로 오판했다. 판정 기준을 compose 교체로 바꿨다.
    2. 변수 이름 대소문자 충돌(5절).
    3. 복원 리허설 임시 컨테이너의 연결 수 20이 tune에 거부돼 초기화가 실패했다(5절).
- **앱 마이그레이션:** 복원된 PG17 DB에 앱의 Flyway가 V11을 적용했다. 지표가 있던 딜만 `MANUAL`로 표시되는 것을 확인했다.
- **전체 테스트:** 운영 이미지(HA PG17 외부 주소)로 app 655건, common 63건 통과(건너뜀 16 — 기존 실데이터·스모크).
  - DB가 없으면 DB 테스트 16건이 건너뛰어진다(실패 아님).
  - `AUTOSTOCK_TEST_DB_REQUIRED=true`면 실패한다.
- **구문:** 스크립트 4개 PowerShell 파서 통과.
- **미검증(PC):**
  - Windows PowerShell 5.1과 Docker Desktop에서 실제 실행.
  - 이미지 받기 시간.
  - Testcontainers(Windows Docker Desktop)로 HA 이미지 기동 — `gradlew test`를 돌리면 확인된다.

## 8. 변경 파일

- 신규:
  - `infra/docker-compose.timescale.yml` — 새 정의(전환 대기본)
  - `scripts/switch_db_timescale.ps1`
  - `scripts/rollback_db_pg16.ps1`
  - `app/src/test/java/com/autostock/support/PostgresAvailableCondition.java`
- 수정:
  - `scripts/backup_db.ps1` — 버전 기록, 경고 거르기
  - `scripts/restore_check.ps1` — 운영 이미지 자동, pre/post restore, 동적 테이블, `-Lab`
  - `scripts/setup_docker.ps1` — 초기화 안내 문구
  - `app/src/test/java/com/autostock/support/PostgresTestDatabase.java`, `PostgresDataJpaTest.java`
  - `app/build.gradle` — zonky 제거
  - `.github/workflows/ci.yml` — `AUTOSTOCK_TEST_DB_REQUIRED`
- 문서:
  - `docs/RUNBOOK.md` 0·5·6·8절
  - `aiDoc/backup.md`, `aiDoc/db-integration-test.md`, `aiDoc/data-platform-postgres.md`, `aiDoc/README.md`
- 전환 스크립트가 PC에서 바꾸는 파일: `infra/docker-compose.yml`(새 정의), `infra/docker-compose.pg16.yml`(생김), `infra/docker-compose.timescale.yml`(없어짐). 커밋은 전환 확인 뒤에 한다.
