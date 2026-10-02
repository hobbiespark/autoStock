# autoStock 자택망 검증 RUNBOOK

목적: 회사망에서 완성한 코드를 자택망에서 실측 검증하고 **모의 무인 운영(게이트 ②)**을 개시하는 절차서.
순서대로 진행하고, 각 단계의 ✅ 확인 항목을 통과해야 다음으로 넘어간다.
작성: 2026-08-13 | 관련: [PROGRESS.md](../PROGRESS.md) 4절, [ARCHITECTURE.md](ARCHITECTURE.md)

---

## 0. 사전 준비 (1회)

> **2026-08-28 상태**: 트랙 B 완료 — 2026 세율 반영, T 확장 재검증으로 C3 게이트① FAIL 확정.
> 이 운영은 "검증 연장 + 무인 운영 능력 검증"이다(PROGRESS 4-1 트랙 C). 실계좌 전환 근거 아님.
> 테스트 250건 통과 확인(JDK 21), data/ 2015~2026 일봉 확보, KiwoomSmokeIT는 `com.autostock.smoke` 패키지로 이동됨.

```powershell
# 필수 도구: JDK 21, Docker Desktop, Python 3.10+ (pip install websockets)
cd D:\myApp\autoStock
git push                                  # 미푸시 커밋 반영
```

- [ ] GitHub Actions 첫 CI **초록불** 확인 (gradlew 권한 수정 후 첫 실행 — 스모크 테스트까지 통과해야 함)
- [ ] `.env` 존재 확인 (모의투자 일반 계좌 키). ⚠️ 채팅에 노출됐던 키이므로 **키움 홈페이지에서 재발급 권장**
- [ ] PostgreSQL 기동 — 원클릭: `powershell -ExecutionPolicy Bypass -File .\scripts\setup_docker.ps1` (기본 실행 정책이 Restricted라 Bypass 필요; 영구 허용은 `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`) (Docker 설치/기동 확인 → postgres up → healthy 대기 → 접속 스모크까지 자동. Docker 미설치 시 `winget install -e --id Docker.DockerDesktop` 안내 출력). 수동으로 하려면: `docker compose -f infra/docker-compose.yml up -d postgres`
- DB 이미지(2026-10-02~): TimescaleDB HA(PostgreSQL 17, `timescale/timescaledb-ha` 다이제스트 고정). 옛 `postgres:16-alpine`에서 옮길 때는 앱을 끄고 `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\switch_db_timescale.ps1`(덤프·복원·행 수 대조·자동 되돌림, 먼저 `-CheckOnly`로 점검 가능). 되돌리기는 `scripts\rollback_db_pg16.ps1` — 근거 `aiDoc/db-switch-timescale.md`
- 참고: 이 PC는 JDK 21이 `D:\jdks\jdk-21.0.12.101-hotspot`에 있음(시스템 기본은 Java 11 + `JAVA_TOOL_OPTIONS` 전역 설정). Gradle 실행 전 창마다 `$env:JAVA_HOME="D:\jdks\jdk-21.0.12.101-hotspot"; $env:Path="$env:JAVA_HOME\bin;$env:Path"` 지정, 또는 사용자 환경변수 `JAVA_HOME` 영구 등록
- 참고: Windows에서는 `gradlew.bat` 사용(셸스크립트 gradlew는 CRLF 상태 — WSL/Git Bash에서 실행 시 `bash -c "tr -d '\r' < gradlew | bash -s -- test"` 또는 `git config core.autocrlf` 정리 필요)

## 1. WS 프로토콜 실측 (장중 09:00~15:30 권장)

가장 중요한 미확정 지점 — 실시간 시세·체결통보 필드 매핑 확정.

```powershell
python scripts/ws_probe.py    # .env의 키로 토큰 발급 → WS 접속 → LOGIN/REG → 60초 수신 출력
```

- [ ] LOGIN 응답 정상 (return_code 확인)
- [ ] REG(0B, 005930) 후 REAL 메시지 수신 — `values`의 필드 번호(10=현재가?, 15=거래량?) 실측값 기록
- [ ] REG(00, grp_no 2) 주문체결통보 등록 응답 확인 — item 빈 배열이 유효한지
- [ ] (장중이면) 모의 앱 또는 curl로 1주 주문 넣고 type 00 통보의 FID(9203 주문번호, 911 체결량, 910 체결가 등) 실측
- 출력 전문을 저장해 두면 코드 반영 시 근거가 된다 → `docs/measured/ws_probe_YYYYMMDD.txt`

**실측 후 코드 반영 대상** (`TODO 실측` 검색): `market/RealMessageParser`·`trading/OrderNoticeHandler`는 2026-09-11 장중 WS 실측(docs/measured/ws_probe_20260911_intraday.txt)으로 FID 매핑·"접수"/"체결" 상태 문자열이 확정됐다(단 "취소"/"거부" 상태 문자열은 미관측이라 여전히 TODO 실측) — 나머지 미해소 대상: `market/KiwoomWebSocketClient`(REG 포맷), `execution/KiwoomBrokerAdapter`(취소 body·잔고 필드), `trading/ReconciliationService`(체결 조회 TR)

## 2. 스모크 + 전체 테스트 (키 주입 실행)

```powershell
# .env 전체를 현재 창 환경변수로 로드 — 공용 로더 사용 (KEY_FILE=경로 간접 참조 규약 지원:
# 예: KIWOOM_LIVE_APP_KEY_FILE=63748327_appkey.txt → 파일 내용이 KIWOOM_LIVE_APP_KEY로 설정됨)
& .\scripts\load_env.ps1
if ($env:KIWOOM_MOCK_G_APP_KEY) { "키 로드 OK" } else { "로드 실패 — .env 확인" }
./gradlew test        # KiwoomSmokeIT 포함 — 토큰·잔고·일봉 실서버 검증
```

- [ ] 278+건 전부 통과, KiwoomSmokeIT skipped 아님

## 3. 부가 API 키 발급·활성화 (선택이지만 권장 순서)

| API | 키 발급 | 활성화 | 실측 확인 |
|---|---|---|---|
| 텔레그램 | BotFather → 토큰, 본인 chat_id | `monitor.telegram.enabled=true` + 환경변수 | 앱 기동 후 `/status` 명령 응답, 킬스위치 `/stop`→`/resume` |
| 특일(공공데이터) | data.go.kr 활용신청(자동승인) | `market.holiday-api.enabled=true` + `DATA_GO_KR_SERVICE_KEY` | `HolidaySyncService.syncYear(2026)` 수동 호출 → market_holidays 적재, JSON 응답 포맷 확인 |
| FRED | fred.stlouisfed.org API key | `macrointel.enabled=true` + `FRED_API_KEY` | `MacroSyncScheduler.syncNow()` → 로그에 VIX 값 |
| ECOS | ecos.bok.or.kr 인증키 | 동일 + `ECOS_API_KEY` | 환율·기준금리 값 확인 (통계코드 실측 TODO 해소) |

## 4. SIM 전체 루프 확인 (브로커 없이)

```powershell
./gradlew :app:bootRun        # 기본 paper 프로필, execution.mode=SIM
# http://localhost:8080 대시보드
# 장외 시간에 FILL 왕복까지 검증하려면 (장 시간 가드만 해제, SIM 전용):
./gradlew :app:bootRun --args="--risk.enforce-market-hours=false"
```

- [ ] 대시보드 [시작] → 배지 STARTING→RUNNING
- [ ] 테스트 시그널 BUY 발행 → 피드에 SIGNAL→ORDER→FILL, 포지션 반영, orders 테이블에 FILLED 기록
- [ ] 킬스위치 ON → 시그널 거부 확인 → 해제
- [ ] (텔레그램 활성 시) 체결 알림 수신

## 5. 모의 무인 운영 개시 (게이트 ② + C3 진행형 검증)

1단계 실측 반영이 끝난 뒤에만 진행한다.

```yaml
# application.yml (또는 환경변수 오버라이드)
execution.mode: LIVE            # 모의 서버(paper 프로필)의 실제 주문 API 사용
strategy.c3.enabled: true
autostock.ws.enabled: true
```

- **원클릭/자동 시작 (2026-09-11)**: `scripts\start_autostock.bat` — .env 로드→Docker 대기→postgres→bootRun(LIVE+C3+WS+auto-start)을 한 번에. 종료는 `scripts\stop_autostock.bat`(graceful, POST /actuator/shutdown). **Windows 로그인 시 자동 시작**: `scripts\install_autostart.bat` 1회 실행(+ Docker Desktop 설정에서 "Start when you sign in" 켜기, PC 절전 해제 필수). `autostock.trading.auto-start=true`가 [시작] 클릭까지 자동화. **킬스위치·일 손실은 재기동해도 유지된다**(2026-10-01 Phase 0.2, `risk_state`) — 켜진 채 재시작하면 DEGRADED로 시작하고 "재기동 복원: 원 사유" 알림, 해제는 사람만(대시보드 [해제…] 또는 텔레그램 `/resume` 2단계)
- **장외 대기 (2026-09-29)**: 앱은 24시간 켜 두면 된다. 거래일 **08:30~16:00(KST)만 ACTIVE**, 그 외(16:00~익거래일 08:30, 주말·휴장일 종일)는 **STANDBY** — WS 연결 해제, 5분 주기 대사·1분 미체결 취소 점검 중지. 운영 상태기계(RUNNING)는 그대로라 아침에 [시작]을 다시 누를 필요 없음. 로그: `시장 세션 ACTIVE → STANDBY — 장외 대기 … 다음 활성화: …` / `WS 장외 대기 해제 — 연결 시작`. 설정 `autostock.session.{enabled,wake-time,sleep-time}`(NXT 대응 시 07:30/20:30). 장외에 WS·대사를 직접 검증하려면 `--autostock.session.enabled=false`. 텔레그램 명령·DART/매크로/IPO 배치·기동 시 대사는 대기와 무관하게 동작
- **장중 절전 방지 (2026-09-30)**: ACTIVE(거래일 08:30~16:00) 동안 앱이 Windows **유휴 절전**을 막는다(`autostock.session.keep-awake`, 기본 true). 수동 절전·노트북 덮개 닫기는 못 막으니 장중엔 하지 말 것. 확인: 장중 관리자 PowerShell `powercfg /requests` → SYSTEM에 java.exe. 절전에서 깨어나면 `절전 복귀 감지 — A ~ B (N시간 M분)` 로그(장중 구간이 걸리면 WARN 알림). 키움이 만료 전 토큰을 `8005`로 거부하면 자동 재발급 후 1회 재시도 — 근거 `aiDoc/sleep-resume.md`
- **외부 감시·알림 (2026-10-01 Phase 0.7, D-09)** — 앱이 죽거나 PC가 잠들면 앱 스스로는 알릴 수 없다. 두 가지를 켠다:
  1. **텔레그램**: BotFather로 봇 생성 → 봇에게 아무 말이나 보낸 뒤 `https://api.telegram.org/bot<토큰>/getUpdates`에서 `chat.id` 확인 → `.env`에 `TELEGRAM_BOT_TOKEN`·`TELEGRAM_CHAT_ID`. `start_autostock.bat`가 두 값이 있으면 `--monitor.telegram.enabled=true`로 켠다(기동 창에 `[O] telegram on`). 명령: `/stop`(즉시 정지), `/resume`(4자리 확인 코드 답장 → 60초 안에 `/resume 1234`), `/status`
  2. **Healthchecks.io**(무료): 체크 1개 생성 → Schedule **Cron** `* 9-15 * * 1-5`, Time zone **Asia/Seoul**, Grace **3분** → Integrations에서 **Telegram** 연결 → ping URL을 `.env`의 `MONITOR_HEARTBEAT_URL`로. 앱이 장중(ACTIVE) 1분마다 핑한다(`monitor.HeartbeatPinger`). **휴장일 전날 체크를 Pause**(다음 핑이 오면 자동 재개) — 안 하면 휴장일 09시에 오탐 알림
  - 실측(1회): 장중 앱을 일부러 3분 이상 멈춰 텔레그램 알림이 오는지 확인 → 8절 점검 기록에 남긴다
- **Windows 전원·업데이트 (Phase 0.9, 관리자 PowerShell 1회)**: `powercfg /change standby-timeout-ac 0` · `powercfg /change hibernate-timeout-ac 0` · 장치 관리자 → 네트워크 어댑터 → 전원 관리 → "전원을 절약하기 위해 컴퓨터가 이 장치를 끌 수 있음" 해제 · 설정 → Windows 업데이트 → 사용 시간 06:00~24:00, 업데이트는 주말에 수동 설치. 확인: 장중 `powercfg /requests`에 java.exe, `powercfg /a`로 대기 상태 확인. 앱의 JNA 절전 차단(`SleepGuard`)은 그대로 둔다
- **DB 백업 (Phase 0.8)**: `scripts\backup_db.ps1` — 컨테이너 안 `pg_dump -Fc` → `D:\backup\autostock\autostock_yyyyMMdd_HHmm.dump`, 목차 확인, 같은 이름 `.versions.txt`(이미지·PostgreSQL·TimescaleDB 버전 — 복원은 같은 버전으로), 보존(최근 14개 + 월별 마지막 12개), 로그 `backup.log`. **작업 스케줄러 등록(1회)**: 동작 `powershell.exe`, 인수 `-NoProfile -ExecutionPolicy Bypass -File D:\myApp\autoStock\scripts\backup_db.ps1`, 트리거 평일 16:30(15:45 분봉·15:50 리포트 뒤). 오프사이트 복사는 `-OffsiteDir <OneDrive 폴더>`(선택). 복원 리허설은 8절(월 1회)
- 사전(수동 기동 시): 새 창이면 위 2절의 .env 로더를 먼저 실행(미로드 시 KiwoomProperties 바인딩 실패로 기동 불가)
- [ ] 앱 시작 → Reconciliation 로그(잔고 대사) 정상 → RUNNING
- [ ] **WS 로그인 성공(sor_yn=Y) — 재구독 N종목** 로그 확인 (LOGIN→REG 순서 실전 검증 포인트)
- [ ] 09:05 C3 요약 1줄 확인 — `C3 판단 YYYY-MM-DD — 국면 ON/OFF(지수 종목명(코드) 종가 / SMA200) · 매수 · 매도 · 보유 유지 · 미진입 · 주기 전 · 데이터 부족 · 오류 · 다음 판단 …`(2026-10-01 추가, `aiDoc/run-summary-logs.md`). 종목별 근거는 대시보드 판단 근거 탭. **판단 주기는 21거래일**이고 마지막 판단일은 DB(`strategy_state`)에 남아 재기동해도 다시 판단하지 않는다(2026-10-02 Phase 1.1) — 판단일 사이의 09:05는 `주기 전 5`가 정상, 그 앞에 `C3: 마지막 판단일 복원 n종목 {…}` 1줄(`aiDoc/c3-trading-day-cycle.md`)
- [ ] 08:20(·기동 시) `공모주 수집 완료 — … 증권신고(지분증권) N건(신규 딜 …)` 1줄. 공시 블랙리스트는 매매 대상·보유 종목만 즉시 WARN, 나머지는 1분 뒤 요약 INFO 1건(`aiDoc/alert-digest.md`)
- [ ] 첫 주문 발생 시: ClientOrderId 포맷, orders 상태 전이(SUBMITTING→SUBMITTED→FILLED), WS 체결통보→Fill→포지션 일치
- [ ] 15:45 `분봉 적재: 종목명(코드) N행 추가(1페이지 …)` 종목별 + `분봉 적재 완료(정기 적재) — 5종목 중 실패 0 …` 1줄. 분봉은 DB `minute_bars`(V12)에 쌓이고, 기동·매시 점검이 놓친 날을 따라잡는다. 장외 첫 기동 때는 1년 되채우기(약 10분, `분봉 되채우기: …`) — `aiDoc/minute-bars-db.md`
- [ ] 15:50 일일 리포트 수신
- [ ] **운영 규칙**: 게이트 ② 기준 = 2주+ 무인 운영, 치명 오류 0, 슬리피지 계측. 성과는 C3의 "새 데이터 검증"으로 축적(PROGRESS 3절)
- [ ] paper A/B: 판단 주기 21일(기본) vs 5일 비교 원하면 두 인스턴스/설정 기간 교차 운영

## 6. 문제 대응 (증상 → 조치)

> 실측 확정(2026-09-11): 모의투자 체결통보(FID 938) 수수료율은 0.35%로, 실전 수수료(0.015%)와
> 다르다 — 모의 성과·슬리피지를 해석할 때 수수료 차이를 감안할 것(docs/measured/ws_probe_20260911_intraday.txt).

| 증상 | 원인 후보 | 조치 |
|---|---|---|
| 텔레그램 "키움 인증 실패 [코드]" 긴급 알림 (2026-10-01~) | 아래 8001·8010·8040 행 | 알림의 코드별 안내대로 조치 후 앱 재기동. 같은 코드는 10분에 한 번만 온다(`aiDoc/kiwoom-error-codes.md`) |
| 토큰 발급 실패 return_code≠0 (8001/8002) | 키 오타/재발급됨/`.env` 미로드 | `.env` 재확인, 키움 앱키 상태 확인. 점검: `& scripts\load_env.ps1` 후 `$b=@{grant_type="client_credentials";appkey=$env:KIWOOM_MOCK_G_APP_KEY;secretkey=$env:KIWOOM_MOCK_G_APP_SECRET}|ConvertTo-Json -Compress; Invoke-RestMethod -Method Post -Uri https://mockapi.kiwoom.com/oauth2/token -ContentType "application/json;charset=UTF-8" -Body $b | Select return_code,return_msg` — 키 길이 0이면 로더 미실행(8002), 43자인데 8001이면 키 무효→재발급 |
| 8001이 반복되고 키·`.env`는 정상 | **서비스 해지**(3개월 실서버 미접속 시 매월 첫 영업일 자동 해지 — 모의만 쓰면 해지될 수 있음) | openapi.kiwoom.com → App Key 관리에서 서비스 상태 확인 → 재신청. 예방은 8절 월 1회 실서버 토큰 점검(D-02) |
| `8010` (토큰 발급 IP ≠ 요청 IP) | 공인 IP 변경(가정용 회선) | 앱이 재발급 1회로 자동 복구를 시도한다. 알림이 오면 openapi.kiwoom.com → 허용 IP 관리에 **현재 공인 IP 등록**(최대 10개) 후 재기동 |
| `8040`/`8050`/`8103` (단말기 인증 실패) | 허용 IP 미등록·단말기 미지정 | 허용 IP 목록에 현재 공인 IP 등록, App Key 상태 확인 |
| `8030`/`8031` (실전·모의 구분 불일치) | 모의 키로 실서버(또는 반대) 호출 | 실행 프로필(`paper`/`live`)과 키 종류 확인 — `live`는 게이트 ② 전 금지(7절) |
| `1700`/`1701`/`1702` (유량 초과) | TR·전체·그룹 호출 한도 | 정상 — 앱이 1.1초 간격 최대 3회 재시도. 계속 나면 동시 호출 스케줄 겹침 확인 |
| 토큰 발급 실패 "응답 없음" / UnsupportedMediaType | 주말·공휴일 모의 서버 점검(HTML 응답) | 정상 — 앱 백오프로 대기, 평일 재확인 |
| WS 연결 후 시세 없음 | REG 미등록/재구독 누락 | ws_probe로 REG 응답 확인, KiwoomWebSocketClient 로그 |
| 주문 UNKNOWN 발생 | 타임아웃(15초 무응답 — 2026-10-01부터 거부로 단정하지 않는다) | 정상 설계 — Reconciliation 로그 확인, 자동 해소 안 되면 미체결 조회로 수동 확정 |
| 미체결 주문이 5분 뒤 취소됨 | 미체결 타임아웃 취소(`execution.stale-order-timeout` 5분, SUBMITTED·ACCEPTED·부분체결 대상) | 정상 — 로그 `미체결 타임아웃 취소 처리`·`취소 완료(stale-timeout)`. 부분체결은 체결분 보존·잔량만 취소(`aiDoc/stale-cancel.md`) |
| 08:30 이후에도 WS 미연결 | 휴장일 판정(market_holidays) 오류 또는 session 설정 | 로그 `시장 세션 … STANDBY … 다음 활성화` 시각 확인, 휴장일 DB 점검. 급하면 `autostock.session.enabled=false`로 재기동 |
| 킬스위치가 저절로 켜짐 | WS 180초 단절 or 일 손실 -2% or VIX≥35 | 원인 로그 확인 후 해소 → 수동 해제(대시보드 [해제…] 확인 창, 또는 텔레그램 `/resume` → 받은 코드로 `/resume 1234`). **재기동해도 풀리지 않는다**(Phase 0.2) |
| 재기동 직후 "재기동 복원: …" 알림·DEGRADED | 끄기 전에 킬스위치가 켜져 있었음 | 의도된 동작 — 원 사유 확인 후 사람이 해제. 앱이 뜨지 않아 DB에서 풀어야 하면 `UPDATE risk_state SET kill_switch_engaged=false, changed_by='manual-sql', changed_at=now();` 후 재기동 |
| Healthchecks "DOWN" 알림 | 앱 중단·PC 절전/재부팅·네트워크 단절 | PC·Docker·앱 상태 확인 → `start_autostock.bat`. 휴장일이면 체크 Pause를 잊은 것 |
| 보수 모드 ON | VIX≥25 or 환율≥1450 | 정상 동작(매수만 금지) — 임계치는 macrointel.* 설정 |
| 텔레그램 "거시 지표 오래됨: …" (2026-10-02~) | VIX·원/달러를 7일 넘게 못 받음(FRED·ECOS 키 만료·네트워크·원천 장애) — 그동안 신규 매수 금지(보수 모드) | 로그 `FRED … 수집 실패`·`ECOS … 수집 실패` 확인 → 키(.env `FRED_API_KEY`·`ECOS_API_KEY`)·네트워크 점검 → 앱 재기동(기동 따라잡기가 바로 수집). 수집이 돌아오면 `거시 지표 다시 수신 — 오래됨 해제`(`aiDoc/macro-staleness.md`) |
| 매수가 "미체결 매수 주문 있음 — 추가 매수 차단"으로 거부 (2026-10-02~) | 같은 종목 매수가 아직 체결·취소로 확정되지 않음(전송 중·접수·부분 체결·취소 요청·UNKNOWN) | 정상 — 중복 매수 방지. 오래 남은 주문은 `SELECT client_order_id, symbol, status, updated_at FROM orders WHERE side='BUY' AND status IN ('SUBMITTING','SUBMITTED','ACCEPTED','PARTIALLY_FILLED','CANCEL_REQUESTED','UNKNOWN');`로 보고 대사·수동 확정(`aiDoc/open-order-aware-risk.md`) |
| 텔레그램 "잔고·장부 불일치: …" (대사를 켠 뒤) | 체결 통보 유실·HTS 수동 매매·복원 실패 | 자동 교정 없음. 대시보드 보유와 HTS 잔고 비교 → 수동 매매 흔적이면 재기동(기동 복원이 장부를 브로커 기준으로 다시 채움 — 장중이면 킬스위치 ON 상태에서). 대사 켜기: `--trading.position-reconcile.enabled=true`(장외, `aiDoc/position-reconcile.md`) |
| Flyway 마이그레이션 실패 | 새 마이그레이션 오류, 또는 TimescaleDB가 없는 옛 DB(16-alpine)에서 V12 이후 코드 기동 | **먼저 `scripts\backup_db.ps1`로 백업** → 로그의 실패 버전·SQL 오류 확인 → 코드 수정 후 재기동, DB가 망가졌으면 직전 백업 복원(`restore_check.ps1 -Lab`으로 먼저 확인). 옛 DB면 `switch_db_timescale.ps1`. **`docker compose down -v` 금지** — 주문 이력·분봉(1년 지나면 다시 못 받음)까지 지운다 |
| DB 전환(TimescaleDB) 뒤 이상 | 새 이미지·설정 문제 | 앱을 끄고 `scripts\rollback_db_pg16.ps1`(새 DB 덤프 → 옛 볼륨으로 복귀). V12 이후 코드면 그 이전 커밋으로 되돌린 뒤 기동 — `aiDoc/db-switch-timescale.md` 6절 |

## 7. 절대 금지

- `live` 프로필(실전 도메인) 사용 — 게이트 ② 통과 + 키 전량 재발급 전 금지
- 키/토큰의 커밋·로그 출력
- UNKNOWN 주문의 수동 재주문 — 반드시 브로커 조회로 확정 후

## 8. 정기 점검 (2026-10-01 Phase 0.8·0.10)

| 주기 | 항목 | 방법 | 통과 기준 |
|---|---|---|---|
| 매일(자동) | DB 백업 | 작업 스케줄러 16:30 → `scripts\backup_db.ps1` | `D:\backup\autostock\backup.log`에 `[O] 백업 완료` |
| 월 1회 | 복원 리허설 | `scripts\restore_check.ps1`(최신 덤프를 운영과 같은 이미지의 임시 컨테이너에 복원 — TimescaleDB면 pre/post restore → public 테이블 전부 행 수 대조). `-Lab`이면 복원본을 랩 `autostock-lab`(127.0.0.1:5433)으로 남긴다 — 학습·실험은 운영 DB가 아니라 여기서(D-19) | `[O] 결과: 복원 가능`, 테이블 누락 0 |
| 월 1회(매월 1영업일 **전**) | 키움 실서버 접속 기록(D-02) | **키움 고객센터에 "토큰 발급만으로 접속 인정되는지" 확인한 뒤 시작.** 실전 키 파일로 토큰 발급(au10001) → 즉시 폐기(au10002), **주문 없음**. 아래 스니펫 | `return_code 0` 두 번 |
| 월 1회 | 키움 포털 | openapi.kiwoom.com → App Key 관리: 서비스 상태, 허용 IP 목록에 현재 공인 IP(최대 10개) | 해지 아님, 현재 IP 등록됨 |
| 휴장일 전날 | Healthchecks 체크 Pause | healthchecks.io → 체크 → Pause | 다음 거래일 첫 핑에서 자동 재개 |
| DB 전환 2주 뒤(1회) | 옛 볼륨 정리 | 문제 없으면 `docker volume rm infra_pgdata`(16-alpine 시절 데이터, 롤백용) — 지우기 전 사용자 확인 | 전환 전 덤프 `autostock_preswitch_*.dump`는 남긴다 |
| 12월 | 2027 휴장일·연말휴장(12/31) | KRX 공지 확인 → `scripts\sql`에 MANUAL 등록(예: `20261001_holidays_2026q4.sql` 형식) | `market_holidays`에 반영 |

실서버 토큰 점검 스니펫(PowerShell, 키 값은 화면에 찍지 않는다 — 실전 키는 `.env`에 넣지 않고 키 파일 경로만 쓴다):

```powershell
$k = (Get-Content D:\keys\live_appkey.txt -Raw).Trim(); $s = (Get-Content D:\keys\live_secretkey.txt -Raw).Trim()
$h = @{ "api-id" = "au10001" }
$r = Invoke-RestMethod -Method Post -Uri https://api.kiwoom.com/oauth2/token -Headers $h -ContentType "application/json;charset=UTF-8" `
      -Body (@{ grant_type = "client_credentials"; appkey = $k; secretkey = $s } | ConvertTo-Json -Compress)
$r | Select-Object return_code, return_msg, expires_dt
$h = @{ "api-id" = "au10002" }
Invoke-RestMethod -Method Post -Uri https://api.kiwoom.com/oauth2/revoke -Headers $h -ContentType "application/json;charset=UTF-8" `
      -Body (@{ appkey = $k; secretkey = $s; token = $r.token } | ConvertTo-Json -Compress) | Select-Object return_code, return_msg
Remove-Variable k, s, r, h
```

- 폐기 요청 형식은 키움 스펙(au10002)으로 한 번 확인한 뒤 쓴다. 폐기에 실패해도 토큰은 24시간 뒤 만료된다.
- 실전 키 사용은 이 점검으로만 한정한다(PROGRESS 5절 보안 메모, D-02). 주문 TR은 절대 호출하지 않는다.

점검 기록(날짜 · 항목 · 결과 · 비고):

| 날짜 | 항목 | 결과 | 비고 |
|---|---|---|---|
| | 복원 리허설 1회차 | | Phase 0 완료 기준 |
| | Healthchecks 의도적 중단 알림 | | Phase 0 완료 기준 |
| | 키움 포털 상태 확인 | | Phase 0 완료 기준 |
