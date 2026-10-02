# DB 백업·복원 리허설 (Phase 0.8)

- 날짜: 2026-10-01
- 계획: `upgrade-2026-10/11-execution-plan.md` 0.8, 조사 `06-research-infra.md` 백업·보존
- 사용자 확정: 2026-10-01 Phase 0 착수("다음")

## 1. 목적

운영 DB(도커 볼륨 `pgdata`)에 백업이 없었다. 이 DB에는 다시 만들 수 없는 기록이 있다.

- 주문 이력(`orders`), 이벤트 스토어(`event_store`), 판단 근거(`signal_decisions`)
- 일별 성과(`daily_performance`) — 게이트 ② 무인 운영 증빙
- 공모주 기록(`ipo_deals`), 공시 블랙리스트
- 킬스위치·일 손실 상태(`risk_state`·`risk_daily_pnl`, Phase 0.2)

RUNBOOK의 "Flyway 실패 시 `docker compose down -v`"는 이것을 통째로 지운다.

## 2. 결정과 근거

- **`scripts/backup_db.ps1`:**
  - 컨테이너 안에서 `pg_dump -Fc`로 파일을 만들고 `docker cp`로 꺼낸다. 덤프를 PowerShell 파이프로 받지 않는다 — Windows PowerShell 5.1은 파이프 출력을 텍스트로 다뤄 바이너리가 깨진다.
  - `pg_restore --list`로 목차를 읽어 덤프가 온전한지 확인한 뒤 호스트로 옮긴다.
  - 보존: 최근 14개 + 월별 마지막 백업 12개월. 이 스크립트가 만든 이름(`autostock_yyyyMMdd_HHmm.dump`)만 지운다.
  - `-OffsiteDir`로 OneDrive 등 다른 곳에 복사할 수 있다(선택).
  - (2026-10-02) 덤프마다 같은 이름의 `.versions.txt`에 이미지·이미지 ID·PostgreSQL·확장 버전을 남긴다. TimescaleDB 덤프는 같은 확장 버전으로 복원해야 한다. 보존 정리 때 함께 지운다.
  - (2026-10-02) TimescaleDB DB 덤프가 늘 내는 경고("circular foreign-key constraints … continuous_agg")는 정상이라 걸러 낸다.
  - `backup.log`에 한 줄씩 남기고, 실패하면 종료 코드 1(작업 스케줄러에서 실패로 보인다).
- **`scripts/restore_check.ps1`:**
  - 운영과 같은 이미지로 임시 컨테이너를 띄워 최신(또는 지정) 덤프를 `pg_restore --exit-on-error`로 복원한다.
    - (2026-10-02) 이미지는 운영 컨테이너에서 읽는다(`-Image`로 지정 가능). TimescaleDB 전환 전에는 `postgres:16-alpine`, 뒤에는 HA 이미지다.
    - (2026-10-02) 임시 DB에 timescaledb가 있으면 `timescaledb_pre_restore()` → `pg_restore` → `timescaledb_post_restore()` 순서로 복원한다.
    - 준비 확인은 TCP(`pg_isready -h 127.0.0.1`)로 한다. 초기화 중 임시 서버는 소켓만 열어서, TCP로 봐야 초기화가 끝난 뒤에 복원을 시작한다. 컨테이너가 초기화 중 죽으면 로그를 보이고 바로 실패한다.
  - 운영 DB의 public 테이블 전부(2026-10-02부터 — 전에는 주요 10개)의 행 수를 나란히 보여준다(운영 DB에는 `count`만 한다). 복원 실패·테이블 누락이면 1, 행 수 차이는 "백업 뒤 변경분"이라 경고만 한다.
  - "최신 덤프"는 수정 시각으로 고른다(2026-10-02). 이름순이면 `autostock_preswitch_…`가 늘 최신으로 잡힌다.
  - `-Lab`(2026-10-02, D-19): 복원본을 지우지 않고 랩 `autostock-lab`(127.0.0.1:5433)으로 남긴다. 학습·실험은 운영 DB가 아니라 여기서 한다. 매번 최신 백업으로 새로 만든다.
  - 복원본의 최신 마이그레이션 번호도 찍는다. 임시 컨테이너는 끝나면 지운다.
- **오류 처리:** 네이티브 명령은 `$LASTEXITCODE`로 판정하고, `$ErrorActionPreference`는 `Continue`로 둔다. Windows PowerShell 5.1은 네이티브 stderr를 리다이렉트하면 오류 레코드로 바꿔 `Stop`에서 스크립트를 멈춘다(예: 없는 컨테이너 `docker rm -f`). cmdlet에는 `-ErrorAction Stop`을 개별로 준다.
- **인코딩:** UTF-8 BOM + CRLF. Windows PowerShell 5.1이 한글 문자열을 바르게 읽도록 BOM을 붙였다(`setup_docker.ps1`과 같다).
- **일정(사용자 작업):** 작업 스케줄러에 평일 16:30 등록(15:45 분봉 적재·15:50 리포트 뒤). Docker Desktop이 로그인 세션에서 돌기 때문에 "로그온한 경우에만 실행"으로 둔다. 월 1회 복원 리허설(RUNBOOK 8절).

## 3. 버린 대안과 보류

- **`docker exec … pg_dump > 파일`(파이프 리다이렉트):** PowerShell 5.1에서 덤프가 깨진다.
- **볼륨 디렉터리 통째 복사:** DB가 실행 중이면 일관성이 보장되지 않는다.
- **WAL 아카이빙·PITR:** 개인 운영 규모에 과하다. 하루 1회 논리 백업으로 충분하다(장 마감 뒤 변경이 거의 없다).
- **앱 안에서 백업 스케줄:** 앱이 죽으면 백업도 멈춘다. OS 작업 스케줄러가 앱과 독립적이다.

## 4. 변경 파일

- 신규: `scripts/backup_db.ps1`, `scripts/restore_check.ps1`
- 문서: `docs/RUNBOOK.md` 5절(백업 등록)·6절(Flyway 초기화 전 백업)·8절(정기 점검, 점검 기록 표)

## 5. 함정과 주의

- 백업 경로 기본값은 `D:\backup\autostock`이다. D 드라이브가 고장 나면 같이 잃는다. 오프사이트 복사(`-OffsiteDir`)를 권한다(D-08과 함께 결정).
- 덤프에는 비밀값이 없다(키는 `.env`에만 있다). 다만 주문·계좌 기록이 들어 있으니 공개 위치에 두지 않는다.
- 복원 리허설은 포트를 열지 않고 `docker exec`로만 접근한다. 운영 DB(5432)와 충돌하지 않는다.

## 6. 롤백

- 작업 스케줄러 등록 해제. 스크립트는 남아도 무해하다.

## 7. 검증 상태

- 2026-10-02 TimescaleDB 전환 반영분(`db-switch-timescale.md` 7절): 가짜 `docker`가 실제 PostgreSQL(16.15 alpine 대역·HA 17.11)로 실행하는 리허설에서 백업·버전 기록, HA 이미지 복원 리허설(pre/post restore), `-Lab` 남김·재생성 통과. tune의 연결 수 하한(25) 때문에 임시 컨테이너 초기화가 죽던 결함을 찾아 고쳤다.

- 컨테이너 검증(pwsh 7.4, 가짜 `docker` 명령으로 흐름 모의):
  - 두 스크립트 구문 파싱 통과
  - 백업 성공 흐름: 목차 확인 → 복사 → 로그
  - 보존 규칙: 6~9월 평일 88개 + 신규 1개 → 최근 14 + 6·7·8월 말일 3 = 17개 유지, 72개 삭제, 다른 파일은 유지
  - `pg_dump` 실패 시 종료 코드 1이고 보존 정리를 하지 않는다
  - 복원 리허설 성공(행 수 대조표, V9)과 `pg_restore` 실패 시 종료 코드 1
- 미검증(운영 PC): 실제 Docker·Windows PowerShell 5.1에서 첫 백업과 복원 리허설 1회. Phase 0 완료 기준이므로 RUNBOOK 8절 점검 기록에 남긴다.
