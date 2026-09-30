# 수동 주문 안전장치와 위험 동작 UX (Phase 0.4 + 0.5)

- 날짜: 2026-10-01
- 계획: `upgrade-2026-10/11-execution-plan.md` 0.4(BE)·0.5(FE), 감사 `09-audit-be.md` BE-P1-6, `10-audit-fe.md` FE-#1~#5
- 사용자 확정: D-06 (a) — 수량 필수 + 1건 100만 원 상한 + 발행 전 확인 창(LIVE면 종목코드 재입력), 2026-10-01

## 1. 목적

2026-09-18 대시보드 테스트 시그널로 **193주(약 5,000만 원)가 즉시 체결**됐다. 네 가지가 겹쳤다.

- 종목 기본값(005930)이 미리 채워져 있었다.
- 수량을 비우면 자동 사이징이 됐다.
- 버튼 한 번으로 바로 발행됐다.
- LIVE 모드에서도 실주문 경로였다.

같은 성격의 위험 동작도 함께 다뤘다.

- 킬스위치 해제와 매매 시작이 1클릭이었다.
- 명령이 실패해도 화면에 보이지 않았다(`postJson`이 "요청 실패: HTTP 400"만 던짐).
- 헤더 부제가 LIVE에서도 "SIM 모드 검증용"으로 고정돼 있었다.

## 2. 결정과 근거

### BE (0.4)
- **수량 필수:** `TestSignalRequest.quantity`에 `@NotBlank` + 기존 정규식(1 이상 정수)을 걸었다. 빈 값 자동 사이징을 없앴다. 공란이나 누락이면 400 ProblemDetail(`errors[0].field = quantity`)을 돌려준다.
- **1건 금액 상한:** `risk.manual-order-max-krw`(기본 1,000,000). `RiskGate.sizeManual` 매수 가지에서 **지정 수량 × 기준가**가 상한을 넘으면 줄이지 않고 거부하고 `SignalDecision(REJECTED, "수동 주문 금액 상한 초과 — 거부")`를 남긴다.
  - 위치는 사이징 단계다. 기존 순서 "사이징 → 지정가 → 슬롯"을 유지하므로 거부돼도 주문 슬롯을 쓰지 않는다.
  - 줄이지 않고 거부하는 이유: 잘못 입력한 주문을 조용히 줄여 내보내면 사고의 모양만 바뀐다.
  - `RiskProperties`에 `@DefaultValue("1000000")`를 두어 키가 빠져도 0원 상한(전면 거부)이 되지 않게 했다.
- **매도에는 금액 상한을 적용하지 않았다(해석):** 매도는 노출을 줄이는 방향이다. 보유분 수동 청산(예: 19주 × 26만 원 ≈ 494만 원)을 막지 않으려는 것이다. 매도는 기존대로 보유량 캡을 받는다. D-06 문구("1건 100만 원 상한")는 방향을 구분하지 않았으므로, 매도에도 적용하려면 사용자 확인 후 한 줄만 바꾸면 된다.
- **응답 확장:** `TestSignalResponse`에 `executionMode`를 추가했다. `SystemStatusView`(GET /api/dashboard의 `system`)에 `manualOrderMaxKrw`를 추가했다. 둘 다 필드 추가라서 기존 소비자가 깨지지 않는다.

### FE (0.5) — 현 툴체인 그대로(Vite 5, 테스트 도구는 2.2에서 도입)
- **테스트 시그널 카드:**
  - 종목 기본값을 없앴고 수량은 필수다(BE와 같은 정규식).
  - 예상 금액(수량 × 기준가)을 표시하고, 매수 상한을 넘으면 경고하며 발행 버튼을 비활성화한다.
  - "발행 전 확인" → 브라우저 기본 `<dialog>`로 종목명·코드·방향·기준가·수량·예상 금액·실행 모드를 보여준다.
  - LIVE면 제목이 "실주문 발행 확인 (LIVE)"로 바뀌고, **종목코드를 다시 입력해야** 발행 버튼이 켜진다.
  - 발행 결과(실행 모드)와 실패(서버 설명·필드 오류)를 표시한다.
- **킬스위치:** 비상 정지는 1클릭을 유지했다(안전 방향). 해제는 확인 창을 거친다. 이미 해제 상태면 해제 버튼이 비활성이다.
- **운영 상태:** 시작은 확인 창을 거치고(LIVE면 "브로커로 주문이 전송됩니다" 경고), 정지는 1클릭을 유지했다.
- **오류 표시:** `api.ts`가 `application/problem+json`을 `ApiError`(status·detail·code·errors·currentStatus)로 옮겨 담는다. 명령 카드 3곳이 `role="alert"`로 보여준다. 공모주·주문 이력 화면도 `error.message`가 서버 설명으로 바뀌어 자연히 나아졌다.
- **실행 모드 표시:** 헤더 부제의 "SIM 모드 검증용" 하드코딩을 없애고 서버 값을 보여준다. LIVE면 헤더 맨 위에 붉은 띠를 둔다(모든 탭에서 보인다).
- **확인 창 구현:** 라이브러리 없이 `<dialog>.showModal()`을 쓴다. 포커스 가두기·배경 차단·Esc 닫기는 브라우저가 맡는다(Esc = 취소).

## 3. 버린 대안과 보류

- **상한을 넘으면 수량을 줄여 발행(예산 캡처럼):** 1절 이유로 거부를 택했다.
- **컨트롤러에서 상한을 먼저 검사해 400:** 규칙이 두 곳에 생긴다. 판정은 RiskGate 한 곳(단일 게이트 원칙)에 두고, 화면은 같은 값(`manualOrderMaxKrw`)으로 미리 막아 헛발행만 없앴다.
- **LIVE 모드 수동 주문 전면 금지(D-06 (b)):** 사용자가 (a)를 택했다.
- **`window.confirm`:** 스타일·접근성 제어가 어렵고 LIVE 재입력을 넣을 수 없다.
- **RiskGate 거부 결과를 발행 응답에 싣기:** 이벤트 경로(Signal → RiskGate)를 바꿔야 한다. 지금은 "발행됨 — 판정은 이벤트 피드·판단 근거 탭에서 확인"으로 안내한다. 필요하면 1.x에서 다룬다.

## 4. 변경 파일

- BE 운영 6개: `risk/RiskProperties`(`manualOrderMaxKrw`), `risk/RiskGate`(수동 매수 금액 상한), `monitor/DashboardController`(수량 필수, 응답 `executionMode`, 생성자에 `TradingProperties`), `monitor/DashboardFacade`·`monitor/view/SystemStatusView`(`manualOrderMaxKrw`), `application.yml`(`risk.manual-order-max-krw: 1000000`)
- BE 테스트:
  - `RiskGateTest` +4건: 상한 초과 거부·슬롯 미사용, 상한과 같은 금액 통과, 매도 상한 미적용, 매도 보유량 캡(기존에 없던 수동 경로 테스트)
  - `RequestValidationTest`: 정상 발행 응답에 `executionMode`, 수량 공란·누락 400
  - `DashboardFacadeTest`: `manualOrderMaxKrw` 조합
  - 생성자 변경 반영: `RiskGateMacroTest`, `DailyPnlTrackerTest`, `DailyReportSchedulerTest`, `BrokerEquitySourceTest`, `DashboardControllerQuoteTest`
- FE 소스 9개: `api.ts`, `types.ts`, `App.tsx`, `index.css`, `components/ConfirmDialog.tsx`(신규), `TestSignalCard.tsx`, `KillSwitchCard.tsx`, `OperationCard.tsx`, `Header.tsx`
- 정적 산출물(같은 배포): `static/index.html`, `static/assets/index-DX_qMPhJ.js`·`index-Dl2kFy4j.css`(신규). 옛 `index-DEzaDXuB.js`·`index-DQ51jXj5.css`는 삭제했다.

## 5. 함정과 주의

- **계약 변경(수량 필수)이라 FE와 BE는 같은 배포여야 한다.** 둘 다 같은 jar로 서빙되므로 한 번에 바뀐다. 다른 클라이언트(스크립트 등)가 수량 없이 호출하면 이제 400이다.
- 전역 CSS `* { margin: 0 }`이 `<dialog>`의 기본 가운데 정렬을 지운다. `.confirm-dialog { margin: auto }`로 되살렸다(첫 확인에서 창이 왼쪽 위에 붙어 발견).
- 빌드 재현성: 기존 소스를 다시 빌드한 결과가 커밋된 정적 산출물과 바이트 단위로 같았다(파일 이름 해시 동일). 커밋된 산출물이 소스와 어긋나 있지 않았다.
- `SystemCard`의 "LIVE 모드 — 실거래가 발생합니다" 문구는 그대로 두었다. 모의 서버(`paper` 프로필)에서도 브로커 주문은 실제로 나간다.

## 6. 롤백

- 커밋을 되돌린다(정적 산출물 포함). 설정 키는 남아도 무해하다.

## 7. 검증 상태

- BE 전체 테스트: 619건 통과(+5), 실패 0.
- FE: `tsc --noEmit` 통과, `npm run build` 통과.
- **실제 백엔드에 붙인 화면 점검 23항목 통과**(컨테이너, Playwright + Chromium). 방법: LIVE 모드로 앱을 띄우고 브로커 주소는 닿지 않는 로컬 포트로 돌렸다. 외부 호출은 없고, 장외라 RiskGate가 거부해 주문도 나가지 않았다. 항목:
  - 표시·입력: LIVE 띠, 종목 기본값 없음, 수량 필수, 상한 초과 경고·비활성, 매도는 상한 미적용
  - 확인 창: 예상 금액 요약, 재입력 불일치 시 비활성, Esc 취소 시 요청 없음
  - 발행과 오류: 발행 응답 `executionMode=LIVE`, 결과 표시, ProblemDetail 오류 문구(`role=alert`)
  - 명령 카드: 비상 정지 1클릭, 해제 확인 창, 시작 확인 창과 취소
- 미검증(운영 PC): 장중 실제 수동 주문 1건(소액)으로 확인 창 → 주문 → 체결 흐름을 확인한다. 브라우저 캐시 때문에 옛 화면이 보이면 새로고침(Ctrl+F5)한다.
