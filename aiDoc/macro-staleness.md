# 거시 지표 신선도 + 지표 ID 단일화 (Phase 1.4)

- 날짜: 2026-10-02(금) 장마감 후
- 계획: `upgrade-2026-10/11-execution-plan.md` 1.4(BE-P1-8·P2-14), 새 설정 키는 규약-6 `macrointel.max-staleness-days`

## 1. 목적

- **낡은 값으로 판단(BE-P1-8).** `MacroGuard`는 지표별 최신값만 들고 있고 받은 시각을 보관하지 않았다. FRED·ECOS 수집이 며칠째 실패해도 마지막 VIX·환율로 매수를 계속 허용했다.
- **지표 ID 이중 선언(BE-P2-14).** `MacroGuard`가 `"FRED_VIX"`·`"ECOS_USDKRW"`를 따로 적어, 발행 쪽(`MacroSyncScheduler`) 상수와 어긋날 수 있었다.

## 2. 결정과 근거

- **판정 지표 2개만 본다:** VIX·원/달러(실제 규칙에 쓰는 것). DXY·기준금리는 보관만 한다.
- **기준:** 마지막 수신 시각(이벤트 `timestamp` = 발행 시각)에서 `macrointel.max-staleness-days`(기본 7)일을 **넘으면** 오래됨. 정확히 7일은 신선하다.
  - 받은 적이 없으면 **기동 시각**부터 잰다. 키가 없거나 원천이 계속 실패하면 기동 8일째부터 매수가 막힌다(모르면 사지 않는다).
  - 수집이 꺼져 있으면(`macrointel.enabled=false`) 보지 않는다. 데이터가 올 일이 없는데 매수를 영구히 막으면 안 된다.
  - **관측일이 아니라 수신 시각이다.** 연휴(추석)에도 따라잡기 수집(매시 05분)은 매일 성공해 최신 관측치를 다시 발행하므로 걸리지 않는다. 관측일 기준이면 FRED의 1~3영업일 지연과 연휴 미갱신 때문에 오경보가 잦다. 이벤트에 관측일 필드를 더하는 것은 이벤트 형식 변경이라(규약-1) 하지 않았다.
- **오래되면 보수 모드와 같이 신규 매수만 막는다.** 매도(청산)는 허용한다.
  - 사유 문구: `보수 모드(거시 지표 오래됨 — 수집 확인 필요) — 신규 매수 거부`. 임계 초과(`… VIX/환율 임계 초과 …`)가 우선이다.
  - `MacroGuard.buyBlockReason()`이 사유를 주고 `RiskGate`는 그 문구를 로그·판단 근거에 그대로 싣는다. `isConservativeMode()`(대시보드·일일 리포트)도 오래됨을 포함한다.
  - **킬스위치(VIX 35)는 새 값이 들어올 때만 판정한다.** 오래된 값으로 킬스위치를 켜지 않는다.
- **알림:** 매시 10분(KST) 점검이 오래된 지표를 찾으면 **하루 한 번** WARN 로그와 새 이벤트 `MacroIndicatorStale`을 낸다. monitor(`TradeNotificationListener`)가 텔레그램 WARN으로 보낸다(risk는 알림 수단을 모른다 — `KillSwitchChanged`와 같은 설계). 다시 받으면 INFO `거시 지표 다시 수신 — 오래됨 해제`.
  - 새 이벤트는 기존 이벤트를 바꾸지 않고 추가한 것이다(규약-1). 알림용이라 감사 저장소(event_store) 기록 대상에는 넣지 않았다(감사 대상 7종은 그대로 — 1.8).
- **ID 단일화:** `MacroGuard.INDICATOR_VIX/USDKRW`가 `MacroSyncScheduler`의 공개 상수를 그대로 가리킨다(risk → macrointel 기존 의존 범위 안).

## 3. 버린 대안과 보류

- **관측일 기준:** 위 이유로 버렸다. 원천 자체가 멈춘 경우(관측일 정체)는 이번 범위가 아니다 — 수집 로그 `거시 지표 발행: …`의 값이 며칠째 같으면 사람이 본다.
- **오래되면 킬스위치:** 매도까지 막는 것은 과하다. 계획대로 매수만 막는다.
- **신선도 점검마다 경고:** 매시 텔레그램이 오면 소음이다. 하루 한 번으로 줄였다.

## 4. 변경 파일

- 운영
  - `risk/MacroGuard` — 수신 시각 보관, `staleIndicators`, `buyBlockReason`, `checkFreshness`(매시 10분), 생성자에 발행기·시계, ID 상수 단일화
  - `risk/RiskGate` — 매수·수동 매수의 거시 거부 사유를 `buyBlockReason()`에서 받음
  - `macrointel/MacroIntelProperties` — `maxStalenessDays`(기본 7)
  - `common/event/MacroIndicatorStale`(신규)
  - `monitor/TradeNotificationListener` — 오래됨 WARN 알림
  - `application.yml` — `macrointel.max-staleness-days: 7`
- 테스트
  - `MacroGuardTest` 7 → 14건: 7일 신선·8일 오래됨(킬스위치 안 켬), 연휴에 같은 값 재발행은 신선, 받은 적 없으면 기동부터, 수집 꺼짐이면 안 봄, 임계 초과 우선, 경고 하루 1회·재수신 해제, ID 상수 일치
  - `RiskGateMacroTest` +1: 오래됨 사유로 매수 거부·매도 허용
  - `TradeNotificationListenerTest` +1: 오래됨 WARN 문구
  - `MacroIntelProperties`·`MacroGuard` 생성 지점(테스트 7개 파일) — 인자 추가만

## 5. 함정과 주의

- **운영 영향:** 수집이 7일 넘게 실패하면 매수가 멈춘다(의도). 텔레그램 `거시 지표 오래됨` 경고가 오면 FRED·ECOS 키와 네트워크, 로그의 `FRED … 수집 실패`·`ECOS … 수집 실패`를 확인한다.
- 앱을 껐다 켜면 수신 기록이 사라지고 기동 시각부터 다시 잰다. 기동 직후 따라잡기 수집이 바로 채운다.
- ECOS 키만 없는 환경(`macrointel.enabled=true`)이면 기동 8일째부터 매수가 막힌다. 그런 환경은 `macrointel.enabled=false`로 둔다.

## 6. 롤백

- 커밋을 되돌린다. 스키마 변경은 없다. 설정 키는 남아도 무해하다(되돌린 코드는 읽지 않는다). 급하면 `--macrointel.max-staleness-days=36500`으로 사실상 끌 수 있다.

## 7. 검증 상태

- 컨테이너: 대상 테스트 통과(전체 결과는 `aiDoc/phase1-offhours-2026-10-02.md`).
- 미검증(재기동 후): 기동 로그 `거시 지표 발행: FRED_VIX=…`·`ECOS_USDKRW=…`가 나오고 `거시 지표 오래됨` WARN이 없는지. 매시 10분 점검은 정상이면 아무 로그도 남기지 않는다.
