# 미체결 타임아웃 취소 정상화 (Phase 0.1)

- 날짜: 2026-10-01
- 계획: `upgrade-2026-10/11-execution-plan.md` 0.1, 감사 `upgrade-2026-10/09-audit-be.md` BE-P0-3
- 사용자 확정: 2026-10-01 Phase 0 착수("다음"), D-05 — `execution.stale-order-timeout` 5분 유지, 배포 후 1~2주 관찰

## 1. 목적

미체결 주문 타임아웃 취소(`StaleOrderCanceller`)가 LIVE에서 사실상 동작하지 않았다.

- 대상이 `SUBMITTED`뿐이었다. LIVE에서는 WS "접수" 통보가 곧바로 `ACCEPTED`로 바꾸므로(`OrderNoticeHandler`), 체결이 안 되는 지정가 주문은 취소 대상에서 빠진 채 장 마감까지 남았다.
- 취소 요청 뒤 `CANCELLED` 확정이 없었다. 브로커 취소를 보낸 주문이 `CANCEL_REQUESTED`에 영구히 머물렀다.
- 대시보드 취소(`TradingService.onCancelRequest`)와 흐름이 둘로 나뉘어 있었다. 대시보드 쪽만 `CANCELLED` 확정·실패 시 `UNKNOWN`+대사가 있었다.
- 대사(`ReconciliationService`)는 `UNKNOWN`·`SUBMITTED`만 봐서, 취소 요청 저장 직후 앱이 멈추면 `CANCEL_REQUESTED`를 아무도 정리하지 않았다.

운영 로그 확인(2026-09-18~09-30): 타임아웃 취소가 실제로 발동한 기록은 없다(로그의 해당 줄은 PC에서 돌린 테스트 출력뿐). 따라서 기존 `CANCEL_REQUESTED` 잔존 행은 없을 것으로 본다(DB 미확인 — 7절).

## 2. 결정과 근거

- **취소 경로 일원화(SSOT):** `TradingService.requestCancel(clientOrderId, requestedBy, precondition)`을 추출했다. 대시보드 취소는 조건 없이(`e -> true`) 호출해 동작이 같다. 타임아웃 취소도 같은 메서드를 부른다.
  - 흐름: 최신 주문 조회 → 취소 가능(`brokerOrderId` 있음 + `SUBMITTED`/`ACCEPTED`/`PARTIALLY_FILLED`)·조건 확인 → `CANCEL_REQUESTED` 저장 → `BrokerPort.cancelOrder(…, 0)`(0 = 잔량 전량, 2026-09-11 실측) → 성공 `CANCELLED`, 실패 `UNKNOWN` + 단건 대사.
  - 반환값: 브로커에 취소를 보냈으면 true. 대상 없음·취소 불가·조건 불충족이면 false.
- **정체 재판정 조건(precondition):** 목록 조회와 취소 사이에 체결 통보가 먼저 저장될 수 있다(R2). 조건은 낙관적 잠금 충돌 뒤 다시 읽은 **최신 주문**에도 적용되어, 방금 체결이 들어온 주문은 취소하지 않는다. 계획 문서의 `requestCancel(id, requestedBy)` 2인자 안에서 이 조건 인자를 더했다.
- **대상 상태:** `SUBMITTED, ACCEPTED, PARTIALLY_FILLED`(`StaleOrderCanceller.OPEN_STATUSES`) — 취소 가능 상태와 같다.
- **정체 기준:** 마지막 상태 갱신 시각(`updatedAt`)이 타임아웃(기본 5분)보다 오래됐으면 정체. 부분체결은 "마지막 체결 후 5분 동안 추가 체결 없음"이면 잔량을 취소하고 체결분은 보존한다.
- **한 건 실패 격리:** 주문별로 예외를 잡아 다음 주문 점검을 계속한다. 다음 주기(1분)에 다시 판정한다.
- **대사에 `CANCEL_REQUESTED` 추가:** `RECONCILE_STATUSES = UNKNOWN, SUBMITTED, CANCEL_REQUESTED`.
  - 브로커 미체결 목록(ka10075)에 **있고** 취소 요청 후 1분(`CANCEL_GRACE`)이 지났으면 취소 미반영으로 본다. `CANCEL_REQUESTED → UNKNOWN → SUBMITTED`(체결분이 있으면 `PARTIALLY_FILLED`)로 되돌려 타임아웃 취소가 다시 취소하게 한다. 상태기계에 `CANCEL_REQUESTED → SUBMITTED` 전이가 없으므로 기존 `UNKNOWN` 확정 경로를 그대로 탄다. 1분 유예는 진행 중인 취소(브로커 호출 수 초)와 겹치지 않게 하기 위해서다.
  - 목록에 **없으면** 취소됐는지 체결됐는지 모른다. 기존 원칙대로 단정하지 않고 상태를 유지한 채 경고한다. 경고 문구는 "UNKNOWN 유지"로 고정돼 있던 것을 현재 상태를 찍도록 바꿨다.
  - `ACCEPTED`는 대사 대상에 넣지 않았다(계획 문서 초안과 다름). 정체된 `ACCEPTED`는 타임아웃 취소가 처리하고, 취소가 실패하면 `UNKNOWN`이 되어 대사로 넘어온다. 체결 통보 유실로 `ACCEPTED`에 남는 주문의 확정은 체결내역 조회 TR이 필요하다(1.6).

## 3. 버린 대안과 보류

- **`StaleOrderCanceller`에 `CANCELLED` 확정만 추가:** 흐름이 두 벌로 남아 한쪽만 고쳐지는 일이 반복된다. 버렸다.
- **`CancelRequest` 이벤트 발행으로 취소:** 모듈 경계상 가능하지만, 같은 모듈 안에서 이벤트를 쓰면 "최신 상태 재판정" 조건을 넘길 수 없다. 버렸다.
- **대사가 `CANCEL_REQUESTED` 미발견 시 `CANCELLED` 확정:** 체결됐을 가능성을 배제할 수 없다. 체결내역 조회 TR 연동(1.6) 후 결정한다.
- **타임아웃 값 변경:** D-05에 따라 5분 유지. C3는 09:05 최유리 호가 지정가라 대부분 즉시 체결될 것으로 보나(추론), 5분 안에 안 잡히면 취소되어 다음 판단까지 미진입할 수 있다. 1~2주 관찰한다.

## 4. 변경 파일

- 운영 3개
  - `trading/TradingService` — `requestCancel` 추출, `onCancelRequest`는 위임
  - `trading/StaleOrderCanceller` — 생성자 `BrokerPort` → `TradingService`, 대상 3상태, 주문별 예외 격리
  - `trading/ReconciliationService` — `RECONCILE_STATUSES`, `CANCEL_GRACE`, `reopenUncancelled`, 경고 문구
- 테스트 3개
  - `StaleOrderCancellerTest` 5 → 11건: 대상 상태, SUBMITTED·ACCEPTED 취소 후 `CANCELLED`, 부분체결 잔량 취소·체결분 보존, 미경과 무시, 목록 뒤 체결 반영 시 생략, 저장 충돌 후 재판정, 브로커 실패 시 `UNKNOWN`+대사, 한 건 실패 격리, SIM·장외 무동작. `TradingService`는 실제 객체로 조립했다.
  - `ReconciliationServiceTest` 10 → 15건: 대상 상태, 취소 미반영 되돌림(SUBMITTED·PARTIALLY_FILLED), 유예 안 무시, 미발견 시 유지
  - `TradingServiceTest` 9 → 12건: `requestCancel` 조건 거짓·대상 없음·성공
- 운영 스크립트: `scripts/sql/20261001_holidays_2026q4.sql`(0.0 사전 점검 — 4분기 휴장일 확인·보강, 수동 실행)

## 5. 함정과 주의

- 취소가 **실제로 동작하기 시작한다**(의도). 장중 로그에서 `미체결 타임아웃 취소 처리`와 `[LIVE] 취소 완료(stale-timeout)`를 확인한다.
- 브로커가 취소를 거부하면(이미 체결 등) `UNKNOWN` → 단건 대사 → 미체결 목록에 있으면 `SUBMITTED`로 복귀 → 5분 뒤 다시 취소를 시도한다. 반복되면 로그에 5분 간격으로 남는다. 장 마감 후에는 장외 대기로 멈춘다.
- `StaleOrderCanceller`는 이제 `TradingService`에 의존한다(같은 `trading` 모듈, 순환 없음).
- 줄바꿈: 수정한 Java 파일은 모두 CRLF를 유지했다.

## 6. 롤백

- 커밋을 되돌린다. 설정·스키마 변경은 없다.

## 7. 검증 상태

- 컨테이너 전체 테스트: 587건 통과, 실패 0, 건너뜀 16(실데이터 실험·스모크 — 기존과 같음). 기준선 573건 + 신규 14건.
- 미검증(다음 거래일 장중): 체결되지 않는 지정가 주문이 5분 뒤 `CANCELLED`로 끝나는지, 키움 취소 응답·WS 취소 통보와 겹칠 때 로그. DB에 기존 `CANCEL_REQUESTED` 행이 있는지(`SELECT client_order_id, status, updated_at FROM orders WHERE status = 'CANCEL_REQUESTED';`).
