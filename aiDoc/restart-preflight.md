# 재기동 전 점검 (2026-10-26)

## 목적

`scripts\start_autostock.bat`은 `gradlew :app:bootRun`으로 **작업 폴더의 소스를 그대로** 컴파일해 기동하고, Flyway(`spring.flyway.enabled=true`)는 `app/src/main/resources/db/migration`의 새 파일을 **운영 DB에 바로 적용**한다. 그래서 재기동 직전 작업 폴더에 다른 작업(다른 AI 세션·예약 작업 포함)의 미커밋 변경이 있으면 그 코드가 운영 앱에 들어가고 미커밋 마이그레이션이 운영 DB 스키마를 바꾼다. `scripts\preflight_restart.ps1`은 재기동 전에 이것을 보여 주고, 이상이 있으면 종료 코드 1을 돌려준다.

## 사고 경위 (2026-10-26)

- 사용자 지시로 운용 자금을 500만 원으로 바꾸고 21:31 `stop_autostock.bat` → `start_autostock.bat`으로 재기동했다(`multi-strategy-paper-2026-10-16.md`).
- 재기동 전에 작업 폴더 상태를 점검하지 않았다. 기동 시 Flyway가 운영 DB에 **V21~V29**를 적용했다(직전 V20). V21~V25는 R16 source 단계, V26~V28은 Codex 커밋분, **V29(`telegram_operator_handover_stop`)는 그 시점에 다른 AI 작업(Codex 예약 'valunivo')의 미커밋 파일**이었다. 실행 중인 클래스에도 당시 미커밋 소스가 컴파일돼 들어갔다.
- 새 테이블은 0행이고 관련 기능은 기본 비활성이라 매매 동작 영향은 없었다. V29는 이후 같은 내용으로 커밋됐다(`39226c2`). 아래 시험에서 작업 폴더 V29의 Flyway 체크섬이 운영 DB 기록(463003331)과 같음을 확인했다.
- 같은 날 다른 프로세스에서 `stop_autostock.bat`을 호출하니 정지 요청은 성공했지만 마지막 `timeout /t 3 /nobreak >nul`이 "Input redirection is not supported"로 실패해 종료 코드가 1이었다(아래 stop 스크립트 수정).

## 사용법

```
powershell -NoProfile -ExecutionPolicy Bypass -File C:\ai\autoStock\scripts\preflight_restart.ps1
```

| 종료 코드 | 뜻 |
|---|---|
| 0 | 이상 없음 |
| 1 | 확인 필요 — `결과` 절 목록을 보고 판단(미커밋 변경 주인 확인·커밋, 새 마이그레이션 내용 확인 등) |
| 2 | 점검 자체 실패 — git 실패, Docker·`autostock-db` 접근 불가 |

- 새 마이그레이션은 커밋돼 있어도 운영 DB 스키마를 바꾸므로 기본으로 1이다. 목록을 보고 적용할 생각이면 `-AcceptPending`으로 다시 실행해 0을 확인한다. 미커밋 마이그레이션·순서 어긋남은 `-AcceptPending`이어도 1이다.
- 옵션: `-Container`(기본 `autostock-db`), `-DbUser`·`-DbName`(기본 `autostock`).

## 점검 항목

1. **미커밋·미추적 변경**: `git status --porcelain --untracked-files=all` 대상 `app/src/main`, `common/src/main`(app이 의존), `build.gradle`·`settings.gradle`·`app/build.gradle`·`common/build.gradle`·`gradle/`. 마이그레이션 파일이면 따로 표시한다.
2. **재기동 시 새로 적용될 마이그레이션**: 운영 DB `flyway_schema_history`(SELECT 1건, `docker exec autostock-db psql`)에 없는 작업 폴더 `V*__*.sql` 목록과 커밋 여부. 최신 적용 버전보다 낮은 미적용 파일은 순서 어긋남으로 표시(Flyway 기본 `outOfOrder=false`라 기동 검증이 실패한다).
3. **적용된 버전의 파일**: 작업 폴더에서 사라짐, 파일 이름 다름, **체크섬 불일치**. 체크섬은 Flyway `ChecksumCalculator` 규칙(줄 단위 — `\r\n`·`\r`·`\n` 구분·줄바꿈 제외, 첫 줄 BOM 제거, 각 줄 UTF-8 바이트를 CRC32에 누적, Java int)으로 직접 계산한다. 줄바꿈 문자를 빼므로 CRLF/LF 차이는 체크섬에 영향이 없다.
4. **실패 기록**: `success=false` 행(기동 거부 원인).
5. 같은 버전 파일 2개.

읽기 전용이다: 파일·DB·앱을 바꾸지 않고, 비밀값(.env)을 읽거나 출력하지 않는다.

## stop 스크립트 수정 (`scripts\stop_autostock.bat`)

- 3초 대기를 `timeout` 대신 `ping -n 4 127.0.0.1 >nul`로 바꿨다(비대화형에서도 동작).
- `curl -f`로 HTTP 4xx/5xx도 실패로 잡고, 정지 요청 결과를 종료 코드로 돌려준다(0 = 요청 성공, 1 = 실패 — curl 7 접속 불가 / 22 HTTP 오류).

## 시험 (2026-10-26, 이번 실행)

운영 앱(127.0.0.1:8080, PID 3316)은 정지·재기동하지 않았다.

- 점검 스크립트, 실제 작업 폴더·운영 DB: 운영 DB V1~V29 29건 / 작업 폴더 29개, 새로 적용될 것 없음, **체크섬 29/29 일치**(직접 계산 규칙을 운영 DB 값으로 실측 확인). 미커밋 소스 3건(다른 작업의 `TelegramCommandPoller.java`·`HandoverOperatorAttemptStore.java` 수정, `OperatorHandoverCoordinator.java` 미추적) 경고로 종료 코드 1.
- 점검 스크립트, 스크래치 복제 저장소(마이그레이션만 복사, 운영 DB는 같은 것을 SELECT): 미추적 V30·순서 어긋난 V2_5, 내용 바꾼 V5(체크섬 불일치), 지운 V3(파일 없음)를 모두 검출하고 1. V30 커밋 후 기본 1 → `-AcceptPending`이면 대기 항목 제외. 없는 컨테이너 지정 시 2.
- stop 스크립트, 스크래치 복사본(URL만 127.0.0.1:18099로 바꿈), `cmd /c ... < NUL` 비대화형: 시험 서버 200 → 종료 코드 0(POST `/actuator/shutdown` 수신, 약 3.3초), 500 → 1(curl 22), 서버 없음 → 1(curl 7). 원래 `timeout /t 3 /nobreak >nul`은 같은 조건에서 "Input redirection is not supported"·종료 코드 1을 재현했다.

## 한계

- 운영 앱이 실제로 실행 중인 클래스와 작업 폴더의 차이는 알 수 없다(점검은 "지금 재기동하면 무엇이 들어가는가"만 본다).
- 반복(`R__`) 마이그레이션, Java 마이그레이션, `spring.flyway.locations` 추가 위치는 비교하지 않는다(현재 없음, 기본 위치만 사용).
- Flyway 설정(`outOfOrder`·`ignoreMigrationPatterns` 등)을 바꾸면 판정 문구가 실제와 다를 수 있다. 체크섬 규칙은 Flyway 11.7·12.4 기준이며 Flyway 주 버전을 올리면 운영 DB와 29/29 일치하는지 다시 확인한다.
- 스크립트는 경고만 한다. `start_autostock.bat`에서 자동으로 부르지 않는다(그 파일은 사용자의 미커밋 변경이 있어 이번에 건드리지 않았다). 연결은 사용자 결정 후.
- stop 스크립트의 실제 8080 앱 정지 경로는 이번에 실행하지 않았다(같은 명령을 다른 포트의 시험 서버로 확인).
