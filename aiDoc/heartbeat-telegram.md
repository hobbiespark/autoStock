# 외부 heartbeat와 텔레그램 가동, /resume 2단계 확인 (Phase 0.7)

- 날짜: 2026-10-01
- 계획: `upgrade-2026-10/11-execution-plan.md` 0.7(+0.9·0.10 RUNBOOK 반영), 조사 `06-research-infra.md` dead-man switch
- 사용자 확정: D-09 (a) — Healthchecks.io 무료 + 텔레그램 활성, `/resume` 2단계 확인(2026-10-01). 봇 토큰·Healthchecks 계정은 사용자가 발급

## 1. 목적

- **앱 스스로는 알릴 수 없는 장애**(PC 절전·재부팅·앱 사망)를 외부에서 잡는다. 앱 내부의 텔레그램 알림은 앱과 함께 죽는다. PROGRESS C2-5 "장중 앱이 멈춰도 사람이 모른다"가 그 사례다.
- 텔레그램 봇(알림·원격 명령)은 구현만 돼 있고 꺼져 있었다(`monitor.telegram.enabled=false`, 토큰 미발급).
- `/resume` 한 번으로 비상 정지가 풀렸다. PLAN A3은 "위험 명령 2단계 확인"을 요구한다.
- `TelegramCommandPoller`가 `fixedRate`였다. 절전에서 깨어나면 밀린 회차가 동시에 실행돼 같은 명령을 두 번 처리할 수 있다(BE-P2-7, 9/23 fixedDelay 결정과 같은 이유).

## 2. 결정과 근거

- **`monitor.HeartbeatPinger`(신규):**
  - `@Scheduled(fixedDelay = 60s)`. `MarketSessionService`가 ACTIVE(거래일 08:30~16:00)이고 `monitor.heartbeat.url`이 있을 때만 GET을 보낸다(본문 없음, 10초 상한).
  - 실패는 WARN 한 줄로 남기고 재시도하지 않는다(다음 주기가 곧 재시도다). URL(체크 UUID)은 로그에 남기지 않는다.
  - 기동 때 켜짐·꺼짐을 한 줄 로그로 남긴다.
- **Healthchecks 체크 설정(사용자):** Cron `* 9-15 * * 1-5`, 시간대 Asia/Seoul, Grace 3분, Telegram 통합.
  - 앱은 08:30~16:00에 핑하므로 체크 창(09:00~15:59)을 감싼다. 창 밖의 여분 핑은 무해하다.
  - Healthchecks cron은 휴장일을 모른다. **휴장일 전날 Pause** 한다(다음 핑이 오면 자동 재개). 계획 초안의 "`30 8 * * 1-5` ~ 16:00" 표기를 Healthchecks 문법으로 바로잡았다.
- **`/resume` 2단계:**
  - `/resume`을 받으면 무작위 4자리 코드(SecureRandom)를 WARN 알림으로 답한다. 60초 안에 `/resume 1234`가 오면 해제한다.
  - 코드는 한 번 쓰면 버린다(맞든 틀리든) — 추측 반복을 막는다. 이미 해제 상태면 코드를 발급하지 않고 알려만 준다.
  - `/stop`은 1단계를 유지한다(안전 방향).
  - chat_id 제한(등록된 한 방만)은 그대로다.
- **`fixedRate` → `fixedDelay`**(5초).
- **텔레그램 켜기:** `application.yml` 기본값(false)은 그대로 두고, `start_autostock.bat`가 `.env`에 `TELEGRAM_BOT_TOKEN`·`TELEGRAM_CHAT_ID`가 **둘 다 있을 때만** `--monitor.telegram.enabled=true`를 붙인다. 키 없이 켜면 5초마다 폴링 오류가 쌓이기 때문이다. 기동 창에 `[O] telegram on`·`heartbeat on/off`가 표시된다.
- **설정 추가:** `monitor.heartbeat.url: ${MONITOR_HEARTBEAT_URL:}`. `.env.example`에 키와 발급처를 적었다.
- **RUNBOOK(0.7·0.9·0.10):**
  - 5절: 텔레그램·Healthchecks 설정 절차, Windows 전원·업데이트 하드닝 명령, 백업 등록
  - 6절: 인증 실패 알림, 8001 반복(해지), 8010, 8040/8050/8103, 8030/8031, 1700~1702, 재기동 복원, Healthchecks DOWN, 미체결 타임아웃 취소 행 추가·정정
  - 8절 신설: 정기 점검 — 백업, 월 1회 복원 리허설, 실서버 토큰 점검(D-02), 포털 확인, 휴장일 Pause

## 3. 버린 대안과 보류

- **`monitor.telegram.resume-confirm` 설정 키(계획 초안):** 2단계 확인을 끌 수 있는 스위치는 안전장치를 약하게 한다. 항상 켜 두고 설정 키를 만들지 않았다.
- **heartbeat에 상태 싣기(`/fail` 신호 등):** Healthchecks는 `<url>/fail`로 즉시 실패를 알릴 수 있다. 예를 들어 매매 상태가 ERROR일 때 쓸 수 있지만, "외부로 나가는 정보는 살아 있음뿐" 원칙과 범위(0.7)를 넘는다. 1.7 관측 작업에서 검토한다.
- **Docker Desktop 없이 서비스화(로그인 전 기동):** D-08(11월)로 남겼다.

## 4. 변경 파일

- 운영: `monitor/HeartbeatPinger`(신규), `monitor/TelegramCommandPoller`(2단계 확인·`fixedDelay`·생성자에 `Clock`·`handleUpdate` 패키지 공개), `application.yml`(`monitor.heartbeat.url`, 텔레그램 주석)
- 스크립트·문서: `scripts/start_autostock.bat`(조건부 텔레그램 플래그, ASCII·LF 유지), `.env.example`(`MONITOR_HEARTBEAT_URL`), `docs/RUNBOOK.md`(5·6·8절)
- 테스트:
  - 신규 `HeartbeatPingerTest` 4건: 장중 GET, 장외 무동작, URL 없음 무동작, 실패 삼킴
  - `TelegramCommandPollerPollTest` 4 → 8건: `/resume`은 코드만 발급, 틀린 코드면 해제 안 함·코드 폐기, 60초 안 맞는 코드면 해제, 만료 코드 거부, 이미 해제 상태면 코드 미발급
  - `TelegramCommandPollerTest` +3건: `/resume 1234` 파싱, 형식 오류 무시, 타 chat_id 차단

## 5. 함정과 주의

- 텔레그램이 꺼져 있으면(키 미설정) 모든 알림은 `LogOnlyNotifier`로 로그에만 남는다. Phase 0 효과의 상당 부분(인증 실패·킬스위치 복원·heartbeat 알림)이 **사용자 작업(봇·Healthchecks 발급)** 뒤에야 켜진다.
- Healthchecks의 텔레그램 알림은 Healthchecks 봇에서 온다(앱 봇과 별개). 둘 다 같은 방으로 받으려면 Healthchecks 통합에서 같은 대화방을 고른다.
- `/resume` 확인 코드는 알림으로 같은 방에 간다. 방 자체가 탈취되면 막지 못한다 — 2단계는 "실수로 누른 해제"를 막는 장치다.

## 6. 롤백

- 코드 되돌림. 운영 중 끄기만 하려면 `.env`의 `MONITOR_HEARTBEAT_URL`을 비우거나, 텔레그램 키를 비운 뒤 재기동한다.

## 7. 검증 상태

- 컨테이너 테스트: 위 4절 통과.
- 미검증(사용자 작업 후 실측):
  - 봇 토큰 발급 뒤 `/status`·`/stop`·`/resume`→코드 흐름
  - Healthchecks 체크 생성 뒤 장중 앱을 3분 이상 멈춰 DOWN 알림 수신 — RUNBOOK 8절 점검 기록에 남긴다(Phase 0 완료 기준)
