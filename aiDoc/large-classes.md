# 큰 클래스 조사 (B3)

- 날짜: 2026-09-30
- 계획: `refactoring-plan.md` B3 — 규칙 §2.1 SRP. 단 KISS·YAGNI·"잘못된 추상화는 중복보다 비싸다"와 저울질한다.
- 기준(계획서): **분리했을 때 테스트가 쉬워지는 경우에만 나눈다. 아니면 나누지 않는다.**
- 상태: 조사 완료, **코드 변경 없음**. 분리는 사용자 결정 후.

## 1. 측정

주석·import·빈 줄을 뺀 실코드 줄 수다(`app/src/main`, 2026-09-30 `66b5ed5` 이후). 상태 필드는 `private final` 필드 수다.

| 클래스 | 실코드 | 전체 | 메서드 | 상태 필드 | 성격 |
|---|---|---|---|---|---|
| `backtest/CrossSectionalBacktestRunner` | 435 | 580 | 15 | 2 | 오프라인 연구 |
| `backtest/InverseSwitchWalkForwardRunner` | 304 | 446 | 12 | 3 | 오프라인 연구 |
| `market/KiwoomWebSocketClient` | 242 | 449 | 10 | **13** | 운영 |
| `ipo/DartClient` | 235 | 361 | 14 | 8 | 운영(외부 API ACL) |
| `risk/RiskGate` | 205 | 387 | 9 | 2 | 운영(주문 안전) |
| `strategy/C3LiveStrategy` | 204 | 391 | 14 | 5 | 운영 |
| `backtest/PerformanceCalculator` | 195 | 340 | 14 | 2 | 오프라인 연구 |
| `trading/OrderNoticeHandler` | 183 | 314 | 8 | 7 | 운영 |
| `market/HolidaySyncService` | 181 | 348 | 13 | 4 | 운영 |

## 2. 후보별 판단

### 2.1 `KiwoomWebSocketClient` — **분리 권장(작게)**

- 한 클래스에 다섯 가지 일이 있다: ① 연결 수명주기(`connect`, `connecting` 가드) ② **재연결 지수 백오프**(`consecutiveFailures`, `nextAttemptAt`, 10초→최대 5분, 스택트레이스 3회까지) ③ 장시간 단절 감지(`disconnectedSince`, `staleEventFired` → `MarketDataStale`) ④ 장외 대기(`standby`) ⑤ LOGIN/REG 프로토콜과 구독(`loggedIn`, `loginToken`, `subscribedSymbols`).
- 상태 필드 13개가 서로 다른 일에 섞여 있다. 절전 복귀(2026-09-30)처럼 여러 상태가 같이 바뀌는 상황에서 추적이 어렵다.
- **테스트 공백:** ②의 백오프 계산과 로그 억제는 **테스트가 없다**. `connect()`가 실제 네트워크를 타서 단위 테스트에서 부를 수 없기 때문이다. 기존 테스트는 `connecting` 필드를 리플렉션으로 조작하고, `wsUrl()`이 null이라 생기는 NPE로 "연결 시도했음"을 추론한다.
- **권장:** 순수 로직 둘만 빼낸다. 프로토콜·수명주기는 그대로 둔다.
  - `ReconnectBackoff` — 연속 실패 수, 다음 시도 시각, 간격 계산, "스택트레이스를 남길지" 판단. `Clock`만 받는다. 백오프 경계(1회 10초, 6회 이후 300초 상한, 성공 시 초기화)를 바로 테스트할 수 있다.
  - `DisconnectionTracker` — 단절 시작 시각, 임계치 초과 시 1회 발행, 대기 진입 시 초기화. 지금 `trackDisconnection`으로 이미 테스트되지만, 상태 3개가 클라이언트에서 빠진다.
  - 기대 효과: 클라이언트 상태 필드 13 → 약 8, 백오프 테스트 신설. 리플렉션 테스트 2건은 그대로 둔다(`connecting` 가드는 연결 수명주기 소관).
- 위험: 낮음. 동작을 바꾸지 않는 추출이고, 기존 WS 테스트 17건이 회귀를 잡는다.

### 2.2 `OrderNoticeHandler` — **선택(효과 중간)**

- 누적 통보 → 증분 수량·단가 변환(`resolveDeltaPrice`, `cumulativeNotional` 맵, 재시작 시 평균가 근사)이 계산 로직인데 핸들러(저장소·발행·보류함 조율) 안에 있다.
- 테스트 15건이 핸들러 전체를 거쳐 계산을 검증한다. 부분체결 3회, 재시작 근사 같은 산술 경계를 더 보려면 매번 엔티티 스텁과 통보를 만들어야 한다.
- 추출하면(`FillDeltaCalculator` 같은 순수 계산) 산술 경계를 직접 테스트할 수 있다. 다만 지금 결함 보고는 없고, R2(낙관적 잠금 재시도)가 이 계산을 재실행하는 구조라 경계를 조심해야 한다.
- 권장: 2.1 뒤에, 원하면 한다.

### 2.3 `RiskGate` — **유지 권장**

- `onSignal`은 장 시간 → 킬스위치 → 사이징(매수: 보수 모드·블랙리스트·보유·동시 보유 한도·사이징 / 매도 / 수동) → 지정가 → 일 주문 한도 → 발행 순서의 **한 줄짜리 파이프라인**이다. 각 단계가 같은 모양(검사 → 로그 + 거부 기록 → 중단)이다.
- 테스트 18건(`RiskGateTest` 13, `RiskGateMacroTest` 5)이 단계별 거부와 순서(예: 거부된 신호는 주문 슬롯을 쓰지 않음)를 이미 검증한다.
- ARCH 2절의 "Policy 조합"(정책 인터페이스 + 체인)으로 나누면 정책 클래스 8~9개와 순서 설정이 생기지만, 테스트는 지금보다 쉬워지지 않는다. **순서가 곧 안전 규칙**이라(예: 슬롯은 사이징·지정가 뒤) 체인으로 흩으면 순서 실수 위험이 커진다.
- 생성자 의존 11개는 테스트 셋업을 무겁게 하지만, 각 의존이 실제 판단 재료다. 지금은 나누지 않는다.

### 2.4 그 밖 — 유지

- `DartClient`: 한 외부 API(OpenDART)의 ACL이다. 호출 2개 + 응답 파싱이 한 곳에 모인 것이 맞다. 실측 응답 픽스처 테스트 4건.
- `C3LiveStrategy`: 판단식은 이미 순수 함수(`MomentumMath`, `VolTargetMath`, `RegimeMath`)로 나뉜 Functional Core / Imperative Shell 구조다. 조각 20·22의 호가 조회도 이 셸의 일이다.
- `HolidaySyncService`: 외부 API 동기화 한 가지 일.
- **백테스트 러너들: 손대지 않는다.** 오프라인 연구 코드이고, PROGRESS 3절의 trial 기록이 이 코드로 재현돼야 한다(사전 선언·재튜닝 금지 원칙). 리팩토링이 결과 수치를 바꾸면 기존 trial과 비교할 수 없게 된다.

## 3. 결정 요청

| 안 | 내용 | 권장 |
|---|---|---|
| A | `KiwoomWebSocketClient`에서 `ReconnectBackoff`·`DisconnectionTracker` 추출 + 백오프 테스트 신설 | **권장** |
| B | A + `OrderNoticeHandler`의 증분 계산 추출 | 선택 |
| C | 나누지 않고 WS 백오프 테스트만 추가(리플렉션·가짜 연결로) | 비권장 — 리플렉션 테스트가 늘어난다 |
| — | `RiskGate`, 백테스트 러너 | 유지 |
