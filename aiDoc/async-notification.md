# 체결·주문·킬스위치 알림 비동기 발송 (Phase 1.3)

- 날짜: 2026-10-02(금) 장마감 후
- 계획: `upgrade-2026-10/11-execution-plan.md` 1.3(BE-P1-5)

## 1. 목적

`TradeNotificationListener`가 텔레그램 발송(왕복 수백 ms, 발송 제한 429면 최대 10초 대기)을 **이벤트 발행 스레드에서** 했다.

- 체결(`Fill`)은 WS 수신 스레드에서 발행된다. 그 스레드가 막히면 PING 에코가 늦어 서버가 연결을 끊을 수 있다.
- 주문 요청(`OrderRequest`)은 `TradingService`(브로커 전송)와 같은 이벤트를 듣는다. 리스너 실행 순서에 따라 브로커 전송이 알림 뒤로 밀릴 수 있다.

## 2. 결정과 근거

- **`monitor.NotificationDispatcher`(신규):** 알림을 대기열에 넣고 바로 돌아온다. `TradeNotificationListener`의 세 메서드(체결·주문 요청·킬스위치)가 이것을 쓴다.
  - **작업자 1개(가상 스레드 `notify-0`)** — 넣은 순서대로 하나씩 보낸다. 킬스위치 작동·해제처럼 순서가 의미 있는 알림이 뒤집히지 않고, 텔레그램에 한꺼번에 쏘지 않아 429를 덜 부른다(09:05 C3 주문·체결이 몰리는 순간).
  - **대기열 상한 500건** — 넘치면 버리고 ERROR(`알림 대기열이 가득 찼거나 종료 중 — 버림`). 발행 스레드를 막지 않는 것이 우선이다.
  - **종료 시 비우기** — 수명주기(SmartLifecycle) 종료 단계 1024에서 남은 알림을 최대 10초까지 보내고 닫는다. 그 뒤 들어온 알림은 버린다(ERROR 로그). `@PreDestroy`가 아닌 이유: 그 시점엔 Reactor Netty 이벤트 루프(단계 0)가 이미 닫혀 텔레그램 발송이 실패한다(2026-10-02 18:56 공시 요약 유실 실측 — `aiDoc/small-fixes-2026-10-02.md`).
  - 알림 하나가 예외를 던져도 작업자는 다음 알림을 보낸다.
- **계획과 다른 점:** 계획은 기본 `@Async`(요청마다 가상 스레드)였다.
  - 요청마다 스레드면 순서가 보장되지 않고 동시 발송으로 429가 늘어난다.
  - 전용 작업자를 Spring `Executor` 빈으로 등록하면 Spring Boot 3.5가 기본 실행기(`applicationTaskExecutor`)를 만들지 않는다(`spring.task.execution.mode=auto`). 그러면 감사 기록(`EventAuditListener`의 `@Async`)까지 이 작업자 하나로 몰린다. 그래서 빈이 아니라 발송기 내부에 둔다.
- **동기로 남긴 리스너:** `PositionBook`·`DailyPnlTracker`·`SlippageTracker`·`OrderNoticeHandler` — 순서가 결과를 바꾼다(계획과 같음). 다른 알림 경로(일일 리포트·공시 요약·인증 경보·텔레그램 명령 응답)는 이미 스케줄러·폴러 스레드라 그대로 둔다.

## 3. 버린 대안과 보류

- **`Notifier`를 감싸는 비동기 `Notifier` 구현:** `Notifier` 빈이 둘이 되어 다른 주입 지점이 모호해진다. 바꾸면 전체 알림이 비동기가 되는데, 텔레그램 명령 응답처럼 동기가 자연스러운 곳까지 바뀐다.
- **`spring.task.execution.mode=force`로 기본 실행기 유지 + 전용 `Executor` 빈:** 전역 설정을 바꾼다. 이 작업에 비해 영향이 넓다.

## 4. 변경 파일

- 운영: `monitor/NotificationDispatcher`(신규), `monitor/TradeNotificationListener`(발송기 사용·설명), `monitor/TelegramNotifier`(설명 한 줄)
- 테스트: `NotificationDispatcherTest`(신규 4건: 느린 알림에도 발행 스레드 무대기·순서·작업자 이름, 상한 초과 시 버림, 종료 시 비우기·이후 버림, 예외 격리), `TradeNotificationListenerTest`(4건 — 대기열을 비운 뒤 확인)

## 5. 함정과 주의

- **알림 도착 순서가 로그와 어긋날 수 있다**(의도). 예: 체결 알림이 장부 반영 로그보다 늦게 온다. 이 리스너의 알림끼리는 순서가 지켜진다.
- 종료 직전의 알림은 10초 안에 못 보내면 버린다(WARN `종료 — 알림 n건을 보내지 못하고 닫음`).
- 앱이 강제 종료(작업 관리자·전원)되면 대기열의 알림은 사라진다 — 예전에도 발송 중이던 알림은 같았다.

## 6. 롤백

- 커밋을 되돌린다. 설정·스키마 변경은 없다.

## 7. 검증 상태

- 컨테이너: 대상 테스트 통과(전체 결과는 `aiDoc/phase1-offhours-2026-10-02.md`).
- 미검증(10/6 장중): 09:05 주문·체결 알림이 순서대로 오는지, 체결 시점에 WS PING 지연·재연결 로그가 없는지. 텔레그램이 꺼져 있으면(`LogOnlyNotifier`) 로그 스레드 이름이 `notify-0`인지로 확인한다.
