# autoStock 새 PC 복구 안내

2026-10-24 사용자 결정: API 키로 수집한 자료는 Git에 올리지 않는다. PC 복구를 위해 공개했던 data/, backup/, docs/measured/는 전체 Git 기록에서 제거한다. 전략 문서와 코드는 유지한다.

## 백업

scripts\pc_migration_backup.bat을 실행한다. Git 커밋·push는 하지 않는다. 기본 출력은 C:\ai\backups\autostock\migration_시각\이며 저장소 안을 백업 위치로 지정하면 중단한다.

- db/: DB 덤프·버전·테이블 행 수.
- data/: 수집 자료.
- docs/measured/: API 실측.
- app-logs/: 운영 로그(잠금 파일 제외).
- secrets/: .env·키 파일·에디터 설정·복구 안내. 비밀 값은 출력하지 않는다.
- MANIFEST.csv: 전체 백업 파일의 SHA-256 목록.

migration 폴더 전체를 안전한 외장 디스크로 옮긴다. 비밀 값이 포함되므로 공개 저장소나 메신저로 공유하지 않는다. 코드 변경은 별도로 검토해 Git으로 옮긴다.

## 복구 순서

1. Git·JDK 21·Docker Desktop 설치 후 clone한다. 기존 PC 앱 종료 및 자동 시작 해제를 사용자에게 확인받기 전에는 새 PC 앱을 켜지 않는다.
2. 백업 MANIFEST.csv 기준 SHA-256을 대조한다. data/와 docs/measured/는 저장소의 같은 위치로 복사한다. secrets/.env와 키 파일도 원래 위치로 복사하고 간접 참조 경로를 확인한다. 비밀 파일 내용은 출력하지 않는다.
3. scripts/start_autostock.bat의 JDK 경로와 백업 기본 위치를 확인한다. DB 포트가 겹치면 .env의 DB_PORT에 빈 포트를 지정한다.
4. powershell -NoProfile -ExecutionPolicy Bypass -File scripts/setup_docker.ps1로 DB만 기동한다. DB 복원 전에 앱을 켜지 않는다.
5. powershell -NoProfile -ExecutionPolicy Bypass -File scripts/restore_db.ps1 -DumpFile E:\migration_시각\db\autostock_시각.dump 실행(실제 경로 사용). PostgreSQL·TimescaleDB 버전과 테이블 행 수를 대조한다. 기존 DB를 지우는 -Force는 사용자 확인 없이 쓰지 않는다.
6. scripts/run_tests.bat으로 검증한다. 옛 PC 종료 확인 후 장외에 scripts/start_autostock.bat으로 기동한다.
7. 스키마·브로커 잔고·전략별 보유 복원과 대시보드를 확인한다. LIVE 실행 모드는 모의 서버에서도 사용한다. 실전 프로필로 바꾸지 않는다.
8. 로그인 자동 시작·평일 백업·전원 정책을 등록한다. 재부팅과 다음 거래일 운영은 별도 확인한다.

킬스위치 해제·실계좌 전환·전략 수치 변경은 사용자 결정이다. 새 PC가 거래일 하루를 정상 운영하기 전에는 옛 PC와 외장 백업을 삭제하지 않는다.

## Git 기록 정리 이후

기존 clone에는 옛 기록이 남아 있다. 로컬 자료와 미커밋 변경을 저장소 밖에 보존하고 새로 clone한 뒤 필요한 코드 변경만 적용한다. 옛 브랜치·태그·bundle을 새 원격에 push하거나 merge하지 않는다. 타인의 사본과 GitHub 캐시·숨겨진 PR 참조까지 Git push로 회수할 수는 없다.
