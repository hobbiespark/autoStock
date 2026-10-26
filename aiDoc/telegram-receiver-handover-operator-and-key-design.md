# 수신 이관 운영자 호출 경로·앱 서명 키 절차 설계 (초안, 미구현)

2026-10-26. 상태: AI 설계 제안이며 사용자가 확정하지 않았다. 코드와 키 변경은 0이다. VALUNIVO 143의 앱 측 문서이며, 앞 단계는 `telegram-receiver-stop-signal-design.md`(142에서 구현)다.

## 근거로 삼은 현재 사실

- autoStock 서버는 `server.address: 127.0.0.1`로 loopback에만 묶여 있다. `/api/trading/start|stop`, 킬스위치, shutdown 같은 관리 호출은 **인증 없는** loopback POST다. 원격 접근이 필요하면 인증부터 붙인다는 주석이 있다.
- `stopForHandover`는 실행 중인 poller의 메모리(`nextOffset`, 잠금, `pendingResume`)가 필요하다. 그래서 별도 CLI 프로세스로는 할 수 없고, **앱 프로세스 안의 호출 경로**가 있어야 한다.
- `scripts/load_env.ps1`의 `*_FILE` 규칙은 파일 **내용**을 환경변수로 내보낸다. 개인키에 쓰면 키가 프로세스 환경에 노출된다. bridge는 이 때문에 `*_PATH`(경로만 전달)를 썼다.
- VALUNIVO 선례는 세 가지다.
  - 키 파일: `ExternalDraftSettings`는 PKCS8 base64 개인키와 X509 공개키를 절대경로로 읽고(크기 상한), 명시 flag가 모두 켜졌을 때만 동작한다. 시작할 때 challenge 서명으로 키쌍 일치를 확인하고, `toString`은 값을 가린다.
  - 신뢰 파일: `OperationResultTrust`(86)는 공개키를 private 신뢰 파일에 고정하고, 원격 키·자동 회전을 쓰지 않으며 시작 뒤 교체도 무시한다.
  - 폴더 권한: `Initialize-LocalConfiguration.ps1`은 secrets 폴더의 상속을 끊고 현재 사용자와 SYSTEM에게만 권한을 준다(`icacls`).
- **공백:** 기존 draft-approval Ed25519 키쌍은 생성 절차가 문서나 스크립트 어디에도 기록되어 있지 않다.

## A. 운영자 정지 호출 경로

**권장안:** loopback 전용 관리 endpoint에 운영자 토큰을 요구한다. 기본은 비활성이다.

- `autostock.telegram.handover.enabled=true`이고 `autostock.telegram.handover.operator-token-path`가 절대경로로 지정됐을 때만 컨트롤러 빈이 생긴다. 하나라도 빠지면 경로 자체가 없다(404).
- `POST /api/telegram/handover/stop` 본문은 `{"instance_id":"pc-1"}`이다. 응답은 정지 기록이고, 서명 키가 구성돼 있으면 서명 envelope도 함께 준다.
- `POST /api/telegram/handover/withdraw` 본문은 `{"stop_id":"…","instance_id":"…"}`이다. 플랫폼이 수신권을 잡지 않았음을 운영자가 확인한 뒤에만 쓴다.
- 요청 조건과 제한:
  - 헤더 `X-Autostock-Operator`가 필요하다. 토큰 파일과 상수 시간으로 비교하고, 32~128자 ASCII만 허용한다.
  - 사용자 지정 헤더가 있으면 브라우저는 CORS preflight를 보내야 하고, 앱에는 CORS 허용 설정이 없으므로 웹페이지를 통한 위조 요청이 차단된다.
  - 원격 주소가 loopback이 아니면 거절한다(바인딩 외에 한 번 더 막음).
  - 응답과 로그에 토큰·키를 쓰지 않는다. 감사 로그 한 줄에 시각, stop_id, offset만 남긴다.
- 운영자 스크립트 `scripts/telegram_handover_stop.ps1`(설계):
  1. 토큰 파일을 읽어 호출한다.
  2. 받은 envelope을 플랫폼 입력 위치(`VALUNIVO-runtime/channels/telegram/handover/stopped-<stop_id>.json`)에 저장한다.
  3. 남은 유효 시간(30초)을 안내한다.

  envelope 유효 시간이 30초이고 수신 공백 상한이 60초이므로, 플랫폼 dry-run과 실제 claim은 같은 절차 안에서 이어서 해야 한다.

**대안**

| 대안 | 장점 | 단점 |
|---|---|---|
| 기존 관례처럼 무인증 loopback | 구현이 가장 간단하다 | 수신이 멈추면 긴급 Telegram `/stop`도 지연되는데, 브라우저 위조 요청으로 이를 일으킬 수 있다. 권장하지 않는다. |
| 파일 트리거(감시 폴더에 요청 파일) | 네트워크가 필요 없다 | 감시 스레드·권한·중복 처리가 추가로 필요하고 응답 전달이 어렵다. |
| JMX | 표준 관리 채널이다 | 기존 운영 관례에 없고, 로컬 JMX 설정과 인증을 새로 다뤄야 한다. |

기존 무인증 관리 endpoint의 위조 요청 위험은 이번 범위 밖의 관찰 사항이며 변경하지 않는다.

## B. 앱 정지 서명 키 절차 (PB-03: 앱 키는 앱 소유)

- **위치:** `C:\ai\autoStock-runtime\secrets\receiver-stop\`(신규, 저장소 밖). 이 폴더에 기존 `icacls` 규칙(상속 끊기, 현재 사용자와 SYSTEM만)을 적용한다.
  - `signing.pkcs8.b64`: 앱만 읽는다.
  - `verification.x509.b64`: 공개키다.

  플랫폼에는 **공개키 사본만** 신뢰 파일로 넘긴다(`VALUNIVO-runtime/secrets/trust/autostock-receiver-stop.json`: app_id, bot_ref, key_id, public_key_x509). 플랫폼은 앱 개인키를 읽지 않는다.
- **생성:** 저장소의 생성 스크립트(설계)가 JDK `KeyPairGenerator("Ed25519")`로 키를 만든다. OpenSSL에 의존하지 않는다.
  1. 대상 파일이 이미 있으면 중단한다(덮어쓰기 금지).
  2. 폴더 권한을 먼저 설정한다.
  3. 개인키 파일을 쓴다.
  4. 공개키 SHA-256 지문을 출력해 운영자가 플랫폼 신뢰 파일과 대조하게 한다.
- **key_id:** `autostock-receiver-stop-YYYYMMDD`로 정하고, 키를 회전할 때 새 ID를 쓴다.
- **앱 구성:** `autostock.telegram.handover.signing-key-path`, `…verification-key-path`, `…key-id`, `…bot-ref`를 둔다. 환경변수는 `*_PATH` 규칙을 따르며, `*_FILE`로 내용을 주입하지 않는다.
  - 시작할 때 키쌍 challenge 검사를 하고, 실패하면 handover 빈 생성을 실패시킨다. 그래도 poller의 기존 동작은 영향을 받지 않는다.
  - 절대경로만 허용하고, 크기 상한을 두며, 심볼릭 링크를 거부한다.
- **회전·폐기:**
  1. 새 키를 만들고 플랫폼 신뢰 파일에 추가 바인딩을 넣은 뒤 재시작한다.
  2. 앱을 새 key_id로 바꾼다.
  3. 옛 바인딩을 제거한다.

  키가 노출됐을 때는 플랫폼에서 해당 바인딩을 즉시 제거한다. 그러면 옛 서명 신호는 검증에 실패하고, 계획기는 신호 없는 보수 계획으로 돌아간다. 자동 회전이나 원격 키는 쓰지 않는다.
- **백업:** 개인키는 백업하지 않는 것을 권장한다. 잃어버리면 새로 만들고 공개키를 다시 고정하면 된다. 서명 신호는 30초짜리 일회성 근거라 옛 키로 검증할 필요가 없다.

## 구현 전 사용자 결정 필요

1. 운영자 호출 경로를 권장안(토큰 헤더가 필요한 loopback endpoint와 스크립트)으로 할지
2. 앱 키 위치를 `C:\ai\autoStock-runtime\secrets\receiver-stop\`(신규 폴더)로 할지

결정 후 구현 범위는 컨트롤러, 구성 로더, 생성 스크립트, 시험이다. 모두 기본 비활성이다. 실제 키 생성, 운영 flag 활성화, 이관 실행은 그 다음 단계로 따로 확인을 받는다.
