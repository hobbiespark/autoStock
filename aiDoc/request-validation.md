# 요청 본문 형식 검증 (B2)

- 날짜: 2026-09-30
- 계획: `refactoring-plan.md` B2
- 사용자 확정: B2부터 진행

## 1. 목적

- 규칙 §2.1: 형식 검증은 요청 DTO 한 곳에서 한다.
- 규칙 §5.2: 화이트리스트로 검증하고, 숫자 범위를 검증한다.
- 규칙 §5.1 fail-closed.
- 규칙 §10.2.2: 형식 오류는 `400`이다.

`spring-boot-starter-validation`은 이미 들어 있었지만 어떤 요청 본문에도 `@Valid`가 없었다.

### 발견한 결함 (실측)

**킬스위치 fail-open.** `POST /api/dashboard/killswitch`는 본문을 `Map`으로 받아 `Boolean.TRUE.equals(body.get("engage"))`로 판정했다. 그 결과 키 오타(`engaged`), 문자열 값(`"yes"`), 빈 본문이 모두 **킬스위치 해제**로 처리됐다.

새 테스트를 수정 전 코드로 돌려 확인했다. `{"engaged":true}`와 `{"engage":"yes"}` 요청이 모두 `killSwitch.release`를 호출했다.

그 밖의 결함:

- 테스트 시그널: side, 가격, 수량 형식이 틀리면 `IllegalArgumentException`·`NumberFormatException`으로 `500`이 됐다.
- IPO 수동 입력: 음수와 범위 밖 값을 받았다. DB 정밀도(`NUMERIC(p,s)`)를 넘으면 DB 오류로 `500`이 됐다.

## 2. 결정과 근거

- **킬스위치:** `KillSwitchRequest(@NotNull Boolean engage)`로 받는다. 값이 없거나 불리언이 아니면 `400`이고, 킬스위치 상태는 바뀌지 않는다.
  - Jackson 기본 설정은 `"true"` 문자열을 `true`로 강제 변환한다. 의도가 분명한 값이라 허용한다.
- **테스트 시그널(`TestSignalRequest`):** 필드 타입(모두 문자열)은 유지하고 `@Pattern`으로 형식만 제한한다. 프론트엔드 계약(`frontend/src/api.ts` `sendTestSignal`)을 깨지 않기 위해서다(§10.2.7).
  - 종목코드 `[0-9A-Z]{6}`, side `BUY|SELL`
  - 가격: 양수, 정수부 최대 9자리, 소수 최대 4자리
  - 수량: 빈 문자열(자동 사이징) 또는 양의 정수
- **IPO 기록·지표:** 수량과 금액은 `@PositiveOrZero`, 확약률은 0~1이다.
  - `@Digits`는 `V6__ipo_deals.sql` 컬럼 정밀도에 맞췄다: `deposit` 16,2 / `sell_price` 14,2 / `institutional_competition_rate` 10,2 / `lockup_commit_rate` 6,4
  - `memo`는 최대 2000자다. DB는 `TEXT`지만 입력 길이 상한을 두었다(§5.2).
- **컨트롤러:** 요청 본문 3곳에 `@Valid`를 붙였다.
- **오류 본문:** Spring 기본 `400`을 쓴다. 필드별 오류 목록과 RFC 9457 형식은 B1(전역 예외 처리)에서 통일한다.
  - 프론트엔드 `postJson`은 상태 코드만 보고 `요청 실패: HTTP 400`을 던진다. 화면 동작은 기존 `500`일 때와 같다.

## 3. 버린 대안

- **테스트 시그널 필드를 `BigDecimal`·`Long`·`Side` 타입으로 바꾸기:** 프론트엔드가 빈 문자열 수량을 보낸다. 빈 문자열을 `Long`으로 강제 변환하는 동작은 Jackson 설정에 따라 달라 계약이 흔들린다.
- **경로 변수(`/quote/{symbol}`) 검증:** 메서드 검증 설정이 따로 필요하다. 이번 범위(요청 본문) 밖이다.

## 4. 변경 파일

- `monitor/DashboardController.java`
  - `KillSwitchRequest` 추가, `TestSignalRequest`에 제약 추가, `@Valid` 추가
  - 형식 상수 `SYMBOL`, `POSITIVE_PRICE`, `POSITIVE_QUANTITY` 추가
  - `Map` import 제거
- `monitor/IpoRecordRequest.java`, `monitor/IpoMetricsRequest.java`: 제약 추가
- `monitor/IpoController.java`: `@Valid` 추가
- 테스트 `monitor/RequestValidationTest.java`(신규, 8건)
  - standalone MockMvc로 JSON 바인딩 → `@Valid` → `400` 경로를 검증한다. 이 리포에는 Spring 컨텍스트 테스트가 없다.

## 5. 함정과 주의

- 종목코드 패턴은 대문자만 허용한다. 프론트엔드는 `trim()`만 하고 대문자로 바꾸지 않는다. 영문이 섞인 코드를 소문자로 입력하면 `400`이다. 현재 대상은 숫자 코드뿐이라 영향이 없다고 **추론**한다.
- 새 요청 본문을 추가할 때는 record에 제약을 달고 컨트롤러 파라미터에 `@Valid`를 붙인다. `Map`으로 받지 않는다.
- springdoc 문서(Swagger)의 required 표기는 실제 생성 문서로 확인하지 않았다(§10.2.8, **미검증**).

## 6. 롤백

- 커밋을 되돌린다. 설정과 스키마 변경은 없다.

## 7. 검증 상태

- **Red(실측):** 새 테스트 8건 중 6건이 수정 전 코드에서 실패했다. 킬스위치 fail-open 2건, 테스트 시그널 400, IPO 3건이다.
- `.\gradlew.bat test` 전체 통과(2026-09-30). `RequestValidationTest` 8, `IpoControllerTest` 8, `ArchitectureRulesTest` 8, `ModularityTests` 통과.
- **미검증:** 실제 기동 후 대시보드에서 킬스위치와 테스트 시그널이 정상 동작하는지. 프론트엔드 요청 형식은 코드로만 대조했다.

## 8. 남은 일

- B1에서 `400` 본문을 필드별 오류 목록(RFC 9457)으로 통일한다.

## 9. 변경 이력

- 2026-09-30: 최초 작성
