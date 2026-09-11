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
# .env 전체를 현재 창 환경변수로 로드 (키를 화면에 노출하지 않음 — Spring은 .env를 자동으로 읽지 않는다)
Get-Content .env -Encoding UTF8 | ForEach-Object { $p = $_ -split '=',2; if ($p.Count -eq 2 -and $p[0].Trim() -and -not $p[0].Trim().StartsWith('#')) { Set-Item -Path ("Env:" + $p[0].Trim()) -Value $p[1].Trim() } }
if ($env:KIWOOM_MOCK_G_APP_KEY -or $env:KIWOOM_APP_KEY) { "키 로드 OK" } else { "로드 실패 — .env 확인" }
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

- 사전: 새 창이면 위 2절의 .env 로더를 먼저 실행(미로드 시 KiwoomProperties 바인딩 실패로 기동 불가)
- [ ] 앱 시작 → Reconciliation 로그(잔고 대사) 정상 → RUNNING
- [ ] **WS 로그인 성공(sor_yn=Y) — 재구독 N종목** 로그 확인 (LOGIN→REG 순서 실전 검증 포인트)
- [ ] 09:05 C3 판단 로그 확인 (거래일·국면·모멘텀 판단 근거 출력)
- [ ] 첫 주문 발생 시: ClientOrderId 포맷, orders 상태 전이(SUBMITTING→SUBMITTED→FILLED), WS 체결통보→Fill→포지션 일치
- [ ] 15:50 일일 리포트 수신
- [ ] **운영 규칙**: 게이트 ② 기준 = 2주+ 무인 운영, 치명 오류 0, 슬리피지 계측. 성과는 C3의 "새 데이터 검증"으로 축적(PROGRESS 3절)
- [ ] paper A/B: 판단 주기 21일(기본) vs 5일 비교 원하면 두 인스턴스/설정 기간 교차 운영

## 6. 문제 대응 (증상 → 조치)

> 실측 확정(2026-09-11): 모의투자 체결통보(FID 938) 수수료율은 0.35%로, 실전 수수료(0.015%)와
> 다르다 — 모의 성과·슬리피지를 해석할 때 수수료 차이를 감안할 것(docs/measured/ws_probe_20260911_intraday.txt).

| 증상 | 원인 후보 | 조치 |
|---|---|---|
| 토큰 발급 실패 return_code≠0 | 키 오타/재발급됨 | .env 재확인, 키움 앱키 상태 확인 |
| WS 연결 후 시세 없음 | REG 미등록/재구독 누락 | ws_probe로 REG 응답 확인, KiwoomWebSocketClient 로그 |
| 주문 UNKNOWN 발생 | 타임아웃 | 정상 설계 — Reconciliation 로그 확인, 자동 해소 안 되면 미체결 조회로 수동 확정 |
| 킬스위치가 저절로 켜짐 | WS 180초 단절 or 일 손실 -2% or VIX≥35 | 원인 로그 확인 후 해소 → 수동 해제(`/resume` 또는 대시보드) |
| 보수 모드 ON | VIX≥25 or 환율≥1450 | 정상 동작(매수만 금지) — 임계치는 macrointel.* 설정 |
| Flyway 마이그레이션 실패 | DB 초기화 필요 | `docker compose down -v` 후 재기동(모의 데이터라 소실 무방) |

## 7. 절대 금지

- `live` 프로필(실전 도메인) 사용 — 게이트 ② 통과 + 키 전량 재발급 전 금지
- 키/토큰의 커밋·로그 출력
- UNKNOWN 주문의 수동 재주문 — 반드시 브로커 조회로 확정 후
