# 플랫폼 주식 제어 입구 + 로컬 운영자 legacy 수신 재개 (P2-2d)

2026-10-27. 브랜치 `feat/p2-2d-stock-control-entry`(기준 `feat/p2-1-operator-listener` deab891). VALUNIVO 기록: `aiDoc/implementation/160-p2-2d-autostock-stock-control-entry.md`. main 병합·운영 재기동·플래그 활성화·키 생성(G2)·실제 인계(G3)는 하지 않았다. 두 기능 모두 기본 꺼짐이다.

## 1. 플랫폼 주식 제어 입구 (VALUNIVO 159 §5)

- 플래그 `autostock.platform.stock-control.enabled`(기본 false). 꺼져 있으면 bean·포트 바인드·키 파일 읽기 0.
- 켤 때 필수(모두 `autostock.platform.stock-control.` 접두): `port`(1024~65535, server.port·운영자 listener 포트와 다름), `bot-ref`/`space-id`/`actor-ref`(플랫폼과 같은 pinned 범위), `platform-request-key-id`+`platform-request-public-key-file`(133 검증, Ed25519 X.509 base64), `platform-ack-key-id`+`platform-ack-public-key-file`(138 검증), `app-result-key-id`+`app-result-private-key-file`(137 서명, Ed25519 PKCS8 base64), `store-key-id`+`store-key-file`(receipt proof·결과 암호화, AES-256 base64). 선택 `attempt-budget-seconds`(기본 10, 1~30). 하나라도 거절이면 기동 실패, 오류 문구는 `stock-control-key-file-rejected` 등 코드만(키·경로 비노출).
- `PlatformStockControlListener`: 127.0.0.1 고정, 실제 socket loopback·자기 포트 검사, query/Origin/압축/chunked 거절, body 1~8192. 경로는 `/platform/stock-control/v1/` 아래 `GET health`, `POST requests|observe|acks`뿐.
- `PlatformStockControlEntry`(판단):

| 경로 | 처리 | 응답 |
|---|---|---|
| health | 실행 0, 원장 DB 연결만 확인 | 200 빈 본문 / 503 |
| requests | 형식 → 132 검증 → 134 receipt → (처음일 때만) 135 시도 → 공유 명령 → 136 결과 → 137 proof | 200 결과 proof / 202 미완료·결과 불명 / 400 / 403(proof·낮은 fence) / 409(같은 update 다른 intent) / 503 |
| observe | receipt 조회만(생성 0), 기한 지난 running→unknown, 저장 결과만 | 200 / 202 / 404 미수신 / 409 / 403 |
| acks | 138 검증 → 기록(같은 결합 멱등) | 200 / 403 / 409 충돌 / 400 |

같은 (bot,update)는 재실행하지 않는다(중복 receipt·이미 시도됨 → 저장 결과만). 실행 중 예외·결과 기록 실패·시도 기한 초과는 unknown(202)이며 재시도하지 않는다. 명령은 한 번에 하나(잠금)만 실행한다.

### 명령 의미 공유 (159 §8-1, 이중화 금지)

`TelegramStockCommands` 빈 하나가 기존 `TelegramCommandPoller`와 입구에 공유된다. /resume 확인 코드도 하나다. 의미·문구는 기존 poller에서 그대로 옮겼다: stop=`killSwitch.engage("텔레그램 원격 명령")` 즉시, resume 요청=작동 중일 때만 4자리 코드 60초, 확인=맞든 틀리든 폐기·일치 시 `release("telegram")`, status=요약. 기존 poller는 이전과 같은 알림만 보낸다(작동·해제는 KillSwitchChanged 알림). 입구는 같은 문구를 결과 content `{outcome,code,reply}`로 돌려준다. 거래·주문 권한 추가 0.

결과 코드: `kill-switch-engaged`, `kill-switch-already-engaged`, `resume-code-issued`, `kill-switch-not-engaged`(failed), `resume-code-missing-or-expired`(failed), `resume-code-mismatch`(failed), `kill-switch-released`, `status`.

## 2. 로컬 운영자 legacy 수신 재개 (사용자 결정 2026-10-27, 대안 A)

플랫폼 listener(stop/observe만, 2026-10-26 결정)는 바꾸지 않았다. 별도 경로:

- 플래그 `autostock.telegram.handover.local-resume.enabled`(기본 false). 켜려면 `autostock.telegram.handover.enabled`·`monitor.telegram.enabled`(정지 store·poller)와 `port`·`key-id`·`key-file`·`instance-id`. 포트는 업무·운영자 listener·주식 입구와 달라야 하고, key-id와 키 파일 경로가 운영자 listener(158)와 같으면 기동 거절(플랫폼이 가진 키로 재개 불가). 키 파일 형식은 158과 같다(소문자 hex 64).
- `LegacyResumeListener`: 127.0.0.1, `POST /legacy-receiver/resume` 하나. `LegacyResumeGate`: HMAC-SHA256, 서명 도메인 `autostock-local-legacy-resume-request-v1`(158과 다름), body 13필드 정확 집합, 정규 Instant·최대 30초. 증거 필드는 null 허용(증거 없음도 원장에 거절로 남김).
- `LegacyResumeOperator` 대조 순서(첫 실패가 reason): `release-evidence-missing` → `platform-lease-not-returned` → `platform-run-not-released` → `platform-fence-invalid`(<1) → `operator-confirmation-mismatch`(정확히 `resume-legacy-polling:<stop_id>`) → `legacy-stop-not-active` → `stop-id-mismatch`(142 활성 정지 행) → `platform-stop-request-mismatch`(플랫폼 run의 stop_request_id가 158 원장에서 같은 활성 정지에 연결) → `platform-offset-below-legacy-stop`.
- 통과하면 `TelegramCommandPoller.resumeAfterPlatformRelease`: pollLock 안에서 다음 offset을 `max(현재, platform_next_offset)`로 올린 뒤 기존 `resumeAfterWithdraw`(변경 없음)를 호출. 철회가 거절되면 offset을 되돌린다.
- 원장 V30 `telegram_legacy_resume_request`: 인증된 요청마다 1행(rejected는 reason 필수, received→resumed|failed 한 번만, 식별·증거 열 불변, 삭제 불가). 같은 request_id는 재실행 없이 409(기록된 결정 보고), 다른 본문이면 409 충돌. 감사 로그는 코드·ID만.
- CLI `:app:legacyResumeCli --args="--port N --key-id ID --key-file PATH --instance ID --evidence-file <플랫폼 release 응답 JSON> --confirm resume-legacy-polling:<stop_id>"`. 보내기 전 request_id 출력, 재전송 기능 없음. 종료코드 0 재개 / 2 거절·오류 / 3 응답 없음(원장 확인 후 판단) / 64 사용법·파일 거절.

한계: 증거는 운영자가 복사한 플랫폼 응답이며 플랫폼 서명이 없다. 신뢰 근거는 로컬 키·운영자 확인 문구·autoStock 자체 정지/요청 원장 대조다. 원장 기록과 정지 철회는 별도 트랜잭션이다(철회 성공 후 결정 갱신 실패 시 received 행이 남을 수 있음 — 정지 행 상태가 기준).

## 3. 시험

`PlatformStockControlEntryDbTest`(실제 loopback·격리 DB, 플랫폼 측은 VALUNIVO eaa7a2a 알고리즘을 독립 복제한 `PlatformStockControlReplica`), `PlatformStockControlConfigurationTest`, `TelegramStockCommandsTest`, `LegacyResumeDbTest`(실제 P2-1 listener로 정지 → release 응답 파일 → CLI), `LegacyResumeConfigurationTest`, opt-in `PlatformStockControlInteropDbTest`(env `AUTOSTOCK_PLATFORM_STOCK_PROOF_DIR`·`AUTOSTOCK_ENTRY_RESULT_EXPORT_DIR`). `SchemaAndTimeZoneDbTest` 최신 버전 기대값 29→30. 결과 수치는 VALUNIVO 160 문서.
