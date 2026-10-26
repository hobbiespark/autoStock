# Telegram 수신자 정상정지·정지확인 신호 설계 (초안, 미구현)

2026-10-26. 상태: AI 설계 제안. 같은 날 사용자 결정(bridge 먼저 별도 커밋)에 따라 1~5단계를 source로 구현했다(VALUNIVO 142). V25, `TelegramReceiverStopStore`(기본 비활성), poller `stopForHandover`/`resumeAfterWithdraw`, `TelegramReceiverStopIssuer`. 운영 활성화와 운영자 호출 경로는 아직 없다. VALUNIVO 140(이관 dry-run)·141(비확인 peek)과 짝을 이룬다. 실제 이관 실행은 사용자 확인 대상이다.

## 왜 필요한가 (현재 코드 사실)

- `TelegramCommandPoller.nextOffset`은 메모리 `AtomicLong(0)`이다. 처리한 update는 다음 `getUpdates(offset)` 호출 때 Telegram에 확인된다. 프로세스가 멈추면 마지막으로 처리한 묶음이 미확인으로 남고, 재시작하거나 다른 수신자가 받으면 다시 전달된다. 같은 `/stop`이 다시 와도 결과는 안전하다. 다만 처리 여부를 밖에서 구분할 수 없으므로 플랫폼 dry-run은 backlog를 실행하지 않는다.
- `pendingResume`(60초 1회용 코드)도 메모리라서 정지하면 사라진다(해제되지 않는 방향).
- 정상 종료 훅(`SmartLifecycle`/`@PreDestroy`)이 없다.

## 제안: 앱 소유 "handover stop"

1. **진입:** 운영자의 명시 명령으로만 시작한다(로컬 CLI/관리 endpoint, Telegram 명령 아님). 진입하면 poller는 새 `getUpdates`를 중단하고, 진행 중인 `handleUpdate` 묶음을 끝까지 처리한다(`fixedDelay` 단일 실행이라 동시 처리 없음).
2. **처리분만 확인:** 마지막으로 `getUpdates(offset=nextOffset, limit=1, timeout=0)`를 한 번 호출한다. offset 미만(=처리 완료분)만 확인되고, 응답에 오는 새 update는 처리하지 않고 버린다. 버린 update는 미확인이라 Telegram에 남는다. 그 결과 "offset 미만=처리됨, 이상=미처리"가 확정된다.
3. **pending 폐기:** `pendingResume`을 비우고 그 사실을 기록한다. 이관 후 `/resume 1234`는 실패하며, 사용자에게 `/resume`부터 다시 하라고 안내한다.
4. **정지 확인 신호:** 앱 DB에 불변 행(bot_ref, stopped_at, confirmed_next_offset, pending_resume_dropped, app instance id)을 남기고, 앱 Ed25519 키로 서명한 envelope을 만든다. purpose는 `autostock-telegram-receiver-stopped-v1`이고 기존 결과 proof와 키 용도를 분리한다. 플랫폼은 pinned 키로 검증한 뒤에만 dry-run의 `legacyStopConfirmed/legacyStoppedAt`과 offset 하한으로 쓴다.
5. **재시작 방지:** 신호를 남긴 뒤 같은 bot의 poller가 재기동되면 안 된다. `telegram.enabled=false` 전환 또는 앱 DB의 handover 상태가 polling 시작을 막아야 한다. 플랫폼 수신권 claim(fence)과 함께, 두 수신자가 동시에 돌지 않는 근거로 삼는다.
6. **긴급 경로:** handover stop부터 플랫폼 시작까지 Telegram `/stop`은 지연된다. 로컬 킬스위치 경로는 그대로 유지하고, 공백은 60초 이내로 제한한다(dry-run `receiver-gap-exceeded`). 실패하면 플랫폼 claim 없이 poller를 재개해 되돌린다. 신호 행은 "철회됨"으로 추가 기록하고, 원래 행은 수정하지 않는다.

## 플랫폼 쪽 반영 (다음 구현 후보)

- 서명된 정지 신호가 있으면, `confirmed_next_offset` 이상의 backlog는 "미처리"로 분류할 수 있다. 다만 그 안의 주식 제어 명령은 이미 오래된 것일 수 있으므로 기존 receipt/fence/60초 규칙으로만 처리한다. 확인 코드는 폐기됐으므로 `resume_confirm`은 실패하는 쪽이 안전하다.
- 신호가 없으면 140의 보수 규칙(backlog 실행 0, 재전송 안내)을 유지한다.

## 구현 전 사용자 결정 필요

`TelegramCommandPoller.java`에는 10/24 bridge 연결 훅(필드·setter·IGNORED 분기 1줄)이 **미커밋 상태로 운영 중**이다. 정지 신호를 구현하려면 이 파일을 고쳐야 하므로, 다음 둘 중 하나를 먼저 정해야 한다.
- (권장) 기존 bridge 훅과 `PlatformTelegramBridge`·시험을 별도 커밋으로 먼저 확정하고, 그 위에 정지 신호를 구현한다.
- 또는 bridge 변경을 보류한 채 poller 변경 없이 별도 컴포넌트로 구현한다. 다만 poller 정지를 제어할 수 없으므로 효과가 제한된다.
