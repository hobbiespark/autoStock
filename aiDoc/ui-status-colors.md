# 화면 상태 색 대응표 (UI-M1)

- 작성: 2026-11-01, VALUNIVO 계획 4-2 S6(구현 기록 VALUNIVO 506)
- 기준: VALUNIVO 공통 표준 2.3 UI-M1(공통 상태 6개: 접수됨·진행 중·완료·실패·결과 불명·대기, 주의는 `warning`)
- 보이는 단어는 autoStock 말(영문 상태 이름·한국어 라벨)을 그대로 쓰고, **색만** 공통 6상태 토큰으로 대응한다.
- 색 값은 `frontend/src/valunivo-tokens.css`(VALUNIVO 원본 바이트 복사)에서 받는다. `index.css`에 16진 색을 직접 쓰지 않는다.

## 1. 대응표

| 화면 | 앱 상태 | CSS 클래스 | 공통 상태 토큰 |
|---|---|---|---|
| 운영 상태 | STOPPED | `badge-STOPPED` | 대기 `status-waiting` |
| 운영 상태 | STARTING·STOPPING | `badge-STARTING`·`badge-STOPPING` | 진행 중 `status-running` |
| 운영 상태 | RUNNING | `badge-RUNNING` | 완료 `status-done` |
| 운영 상태 | DEGRADED | `badge-DEGRADED` | 주의 `warning` |
| 운영 상태 | ERROR | `badge-ERROR` | 실패 `status-failed` |
| 실행 모드 | SIM | `mode-SIM` | 완료 `status-done` |
| 실행 모드 | LIVE | `mode-LIVE` | 위험 `danger`(UI-M5) |
| 주문 이력 | FILLED | `order-badge-ok` | 완료 `status-done` |
| 주문 이력 | REJECTED·CANCELLED | `order-badge-danger` | 실패 `status-failed` |
| 주문 이력 | UNKNOWN | `order-badge-warn` | 결과 불명 `status-unknown`(실패와 다른 색) |
| 주문 이력 | 그 밖(접수·대기 등) | `order-badge-neutral` | 대기 `status-waiting` |
| 이벤트 피드 | SIGNAL | `SIGNAL` | 접수됨 `status-received` |
| 이벤트 피드 | ORDER | `ORDER` | 주의 `warning` |
| 이벤트 피드 | FILL | `FILL` | 완료 `status-done` |
| 종목 선정 이유 | BUY | `decision-badge-buy` | 완료 `status-done` |
| 종목 선정 이유 | SELL | `decision-badge-sell` | 주의 `warning` |
| 종목 선정 이유 | REJECTED | `decision-badge-rejected` | 실패 `status-failed` |
| 종목 선정 이유 | SKIP | `decision-badge-skip` | 대기 `status-waiting` |
| 종목 선정 이유 | HOLD | `decision-badge-hold` | 접수됨 `status-received` |
| 공모주 권고 | RECOMMEND | `ipo-badge-recommend` | 완료 `status-done` |
| 공모주 권고 | SKIP | `ipo-badge-skip` | 대기 `status-waiting` |
| 공모주 권고 | PENDING | `ipo-badge-pending` | 주의 `warning` |
| 공모주 D-day | 예정·청약 중·상장·지남 | `ipo-dday-upcoming`·`active`·`listed`·`passed` | 접수됨·완료·주의·대기 |
| 킬스위치·LIVE 띠·오류 | 비상 정지 중·LIVE 띠·명령 실패 | `ks-on`·`live-band`·`error-text`·`error-banner` | 위험 `danger` |

## 2. 위험 표시(UI-M5)

- 위험 확인 창(`ConfirmDialog`의 `danger`)의 확인 버튼은 `[위험] 해제`, `[위험] 시작`(LIVE)처럼 `[위험]` 단어를 앞에 붙이고 위험 색을 쓴다.
- **비상 정지·정지 버튼에는 `[위험]`을 붙이지 않는다.** 이 둘은 주문을 멈추는 안전 방향 조작(1클릭 유지, Phase 0.5)이라 "위험한 일"이 아니다. 눈에 띄게 하려고 위험 색만 유지한다.

## 3. 테마

- `index.html`의 `data-theme` 고정을 풀어 시스템 밝게·어둡게 설정을 따른다(S3에서 임시로 어둡게 고정했던 것).
- 강조색은 공통 초록(`--vn-color-primary`)이다(VALUNIVO 421 결정 제안 6). 성과 차트 막대·평균선은 파랑(`status-running`), 누적선은 초록(`status-done`)으로 구분을 유지한다.
