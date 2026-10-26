# 새 PC 복구 기록 — 2026-10-24

## 환경 및 복구

- 사용자 요청으로 `git pull --ff-only`: `3448afc` → `3f798a5`(main). 기존 `.env.example` 변경 보존. 커밋·push는 하지 않았다.
- 저장소: `C:\ai\autoStock`. JDK 21 설치: `C:\ai\dev-tools\jdk-21.0.12.1+1`, 사용자 `JAVA_HOME` 등록. Gradle wrapper 8.14.2 다운로드 완료.
- `aiDoc/restore-from-backup.md`, `aiDoc/pc-migration-2026-10-24.md`에 따라 복원. 사용자가 옛 PC 앱 종료 및 자동 시작 해제 완료를 확인했다.
- 백업: `backup/db/autostock_20261024_1721.dump`. 동일 다이제스트의 PostgreSQL 17.11 / TimescaleDB 2.30.2에 복원. public 테이블 18개 행 수 전부 일치, Flyway V20.
- 분봉 510,010행, `strategy_positions` ON-CLOSE / 441270 / 85주 / 평균 11,150원 복원. 앱 기동 후 브로커 잔고 및 전략 장부 복원도 확인했다. 기동 따라잡기로 분봉 913행 추가, 실패 0.
- 기존 VALUNIVO DB의 5432 포트와 다른 프로세스의 55432 포트를 피했다. autoStock DB는 `127.0.0.1:15432`. 비밀 파일 `.env`에 `DB_PORT=15432` 추가. compose는 `DB_PORT`를 참조하고 로컬 인터페이스에 바인딩한다.
- 모의 키·DART 키 등 기존 `.env` 로드 확인(비밀 값 미출력). 실전 키 파일 `63748327_appkey.txt`, `63748327_secretkey.txt`는 없다. 실전 접속 점검을 위해서는 별도 복사가 필요하며 모의 운영에는 필요하지 않다.

## 스크립트 및 운영 등록

- 시작 스크립트의 저장소·로그 경로를 현재 위치 기준으로 변경하고 JDK 21 기본 경로를 설정했다. Docker 엔진이 꺼져 있으면 Docker Desktop을 숨김 실행하고 준비를 기다린다.
- `setup_docker.ps1`은 `.env`를 먼저 로드해 포트 설정을 적용한다.
- 자동 시작 등록 스크립트가 현재 저장소 경로를 사용하도록 수정했다. 사용자 Startup 폴더의 `autostock_start.bat` 등록 완료.
- 백업·복원 점검·DB 전환 스크립트의 백업 기본 위치: `C:\ai\backups\autostock`. 이전 백업 스크립트의 키 디렉터리 기본값: `C:\ai\keys`.
- Windows 작업 스케줄러 `autoStock DB Backup`: 월~금 16:30, 현재 사용자 로그인 세션에서 `scripts/backup_db.ps1` 실행. 새 PC 백업 1회 실행 성공(목차 258개). 실제 스케줄 시각 실행은 미검증.
- AC 전원 절전 및 최대절전 시간 제한을 0으로 설정했다. 배터리 전원 설정은 유지.
- Docker Desktop 자체 AutoStart 설정은 false다. autoStock 시작 스크립트에서 필요시 실행한다. 재부팅·로그인 후 전체 자동 시작 흐름은 아직 미검증.

## 검증 결과

- 전체 `:common:test :app:test --continue --console=plain`: BUILD SUCCESSFUL, 1,096건 / 실패 0 / 오류 0 / 건너뜀 5. 로그: `build/test-run.log`. 운영과 같은 이미지의 Testcontainers DB를 사용했다.
- 키를 주입한 `:app:test --tests 'com.autostock.smoke.*'`: 키움 1건·DART 2건 모두 통과, 건너뜀 0. 로그: `build/smoke-test.log`.
- DB 설정·복원·백업 실행 로그: `build/db-setup.log`, `build/db-restore.log`, `build/backup-check.log`.
- `http://127.0.0.1:8080/actuator/health`: UP. 대시보드 HTTP 200, `brokerServer=MOCK`, `executionMode=LIVE`, `trading.status=RUNNING`. 이 LIVE는 모의 브로커 주문 모드이며 실전 프로필이 아니다.
- 앱 로그에서 스키마 최신 상태, 잔고 1종목 및 ON-CLOSE 장부 복원 확인. 복구 후 점검 시 ERROR 0. 위험 수치·전략 설정·킬스위치 상태를 변경하지 않았다.
- 주말 세션 STANDBY. 다음 활성화 예정 2026-10-26 08:30 KST. 장외이므로 WS 로그인과 월요일 실제 매도 실행은 아직 미검증. 월요일 장중 운영 확인이 남아 있다.
- 옛 PC 및 외장 디스크의 비밀 파일은 삭제하지 않았다. 첫 거래일 정상 운영 확인 전까지 보존한다.

## 사용

- 대시보드: http://127.0.0.1:8080/
- 시작: `scripts\start_autostock.bat`, 종료: `scripts\stop_autostock.bat`.
- `.env`·실전 키 파일은 커밋하지 않는다. 새 PC 작업 내용은 로컬 변경으로 남겨 두었다.

## 후속 Git 자료 제거

사용자 요청으로 data/, backup/, docs/measured/를 전체 Git 기록에서 제거하고 원격 main을 4979210으로 갱신했다. GitHub에서 새로 clone한 기록에서도 세 경로가 0건임을 확인했다. 로컬 파일과 미커밋 복구 변경은 해시 대조로 보존 확인했다. 현재 앱 health UP.

기존 Git 메타데이터는 C:\ai\backups\autostock\git-cleanup-20261024\original-git에 로컬로 보존했다. 이 기록을 원격에 다시 push하지 않는다. PC 이전 백업은 Git 업로드 없이 migration 폴더 전체를 외장 매체로 옮기는 방식으로 변경했다. 실제 백업 검증 결과는 같은 git-cleanup 폴더의 backup-validation.log에 있다. 과거 공개 자료의 사본·캐시까지 회수된 것은 아니다.