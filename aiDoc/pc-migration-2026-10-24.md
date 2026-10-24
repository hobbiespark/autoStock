# PC 이전 정책 변경 — 2026-10-24

PC 복구를 위해 공개 Git 저장소에 올렸던 API 수집 자료를 제거한다는 사용자 요청으로 이전 방식을 변경했다.

- 코드와 전략 문서는 Git으로 이전한다.
- data/, backup/, docs/measured/는 현재 파일과 전체 Git 기록에서 제거하고 .gitignore로 제외한다. 로컬 파일은 보존한다.
- DB·수집 자료·API 실측·운영 로그·키 파일은 로컬 백업 폴더에 모아 안전한 외장 디스크로 옮긴다. pc_migration_backup.ps1은 Git 명령을 실행하지 않는다.
- 기존 clone의 옛 기록을 push하지 않는다. 다른 PC는 자료와 코드 변경을 별도 보존한 후 새로 clone한다.

실행 절차는 [restore-from-backup.md](restore-from-backup.md)를 참고한다. 이 PC의 저장소는 C:\ai\autoStock, DB 포트는 15432다. 새 PC 복구 기록은 aiDoc/pc-recovery-2026-10-24-new-pc.md에 로컬로 남아 있다.

이전 시점 보유 기록은 ON-CLOSE / 파인엠텍(441270) 85주 @11,150원이었다. DB 복원 전에 앱을 켜지 않는다. 새 PC 기동 전 옛 PC 앱 종료와 자동 시작 해제를 확인한다. 실전 키로 주문하지 않는다.
