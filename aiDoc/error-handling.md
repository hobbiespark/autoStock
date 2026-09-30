# 전역 예외 처리와 오류 응답 형식 (B1)

- 날짜: 2026-09-30
- 계획: `refactoring-plan.md` B1
- 사용자 확정: 진행

## 1. 목적

규칙 §6(오류 응답 형식은 하나, 전역 예외 처리는 한 곳, 오류 코드는 enum 한 곳, 예상된 오류는 WARN 이하), §10.2.2(상태 코드 의미, 필드별 검증 오류 목록), §5.5(내부 정보 비노출)를 적용한다.

적용 전에는 오류를 만드는 방식이 컨트롤러마다 달랐다.

- `IpoController`
  - 없는 딜: 본문 없는 `404`
  - 동시 갱신: 본문 없는 `409`(로컬 catch)
  - 잘못된 status: 풀 네임 `ResponseStatusException`
- `TradingSystemController`: 불법 전이가 나면 `409`에 성공 응답과 같은 모양의 본문(`{"status":...}`)을 실었다.
- 처리되지 않은 예외는 Spring 기본 `500`이 됐다. 브로커 실패도 `500`이었다(§6은 `502`).
- B2의 `@Valid` 실패는 Spring 기본 `400` 본문이었다(필드 목록 없음).

## 2. 결정과 근거

- 조직 공통 응답 봉투가 없다. 그래서 **RFC 9457 ProblemDetail**(Spring 6 내장, `application/problem+json`)을 쓰고, 기계용 `code` 확장 필드를 붙였다(§6).
- `monitor/ErrorCode`(enum): 코드, HTTP 상태, 기본 제목을 한 곳에 둔다.
  - 상수: `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `INVALID_PARAMETER`, `NOT_FOUND`, `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE`, `CONFLICT`, `UPSTREAM_FAILURE`, `INTERNAL_ERROR`
  - 상수 이름은 계약이다(§10.2.7).
- `monitor/ApiException`: 컨트롤러가 던지는 예상된 업무 오류. ProblemDetail 확장 필드를 실을 수 있다.
- `monitor/ApiExceptionHandler`(`@RestControllerAdvice`, `ResponseEntityExceptionHandler` 상속)
  - Spring MVC 표준 예외(JSON 읽기 실패, 405, 415, 정적 리소스 404 등): 상위 클래스가 ProblemDetail을 만들고, `handleExceptionInternal`에서 상태 코드별 기본 `code`를 붙인다.
  - `@Valid` 실패: `400 VALIDATION_FAILED`와 `errors[{field, message}]`. 거부된 값 자체는 싣지 않는다(개인정보·주입 문자열 반사 방지).
  - `ApiException`: 지정한 코드, detail, 확장 필드로 응답한다.
  - `OptimisticLockingFailureException`: `409 CONFLICT`. R2 이후 동시 갱신 충돌을 모든 API에서 같게 처리한다. `IpoController`의 로컬 catch는 제거했다.
  - `KiwoomApiException`: `502 UPSTREAM_FAILURE`(§6). 원인 메시지는 `SecretMasking`을 거쳐 WARN 로그에만 남긴다.
  - 그 밖의 `Exception`: `500 INTERNAL_ERROR`. 스택은 마스킹해 ERROR 로그에만 남기고, 응답에는 일반 문구만 준다(§5.5).
- 컨트롤러
  - `IpoController`: 없는 딜은 `NOT_FOUND`, 잘못된 status는 `INVALID_PARAMETER`와 허용값 목록(`allowed`)
  - `TradingSystemController`: 불법 전이는 `409 CONFLICT`. 현재 상태는 확장 필드 `currentStatus`로 준다.
  - 성공 응답 형식은 바꾸지 않았다.
- `spring.mvc.problemdetails.enabled`는 켜지 않았다. Boot 자동 처리기는 사용자 `ResponseEntityExceptionHandler` 빈이 있으면 물러나므로 중복되지 않는다. 설정 없이 이 클래스 하나로 끝난다.

## 3. 프론트엔드 영향 (코드 대조)

- `frontend/src/api.ts`는 오류 응답의 본문을 읽지 않는다. `res.ok`로 판정하고 `HTTP {status}`만 표시한다.
- 시작·정지는 `onSettled`에서 다시 조회만 한다. 따라서 오류 본문 형식이 바뀌어도 화면 동작은 같다.
- 상태 코드가 바뀐 경우:
  - 브로커 실패가 바깥으로 새던 경로: `500` → `502`
  - B2 이전에 `500`이던 형식 오류: `400`(B2에서 이미 바뀜)
- 필드별 오류 표시는 프론트엔드가 `errors`를 읽도록 바꿀 때 활용할 수 있다(미착수).

## 4. 버린 대안

- **컨트롤러별 try-catch 유지:** §6 위반이다. 형식이 계속 어긋난다.
- **`IllegalStateException`을 전역으로 `409`에 매핑:** 버그로 난 `IllegalStateException`까지 `409`로 숨긴다. 불법 전이가 확실한 `TradingSystemController`에서만 `ApiException`으로 바꿨다.
- **검증 오류에 거부된 값(`rejectedValue`) 싣기:** 입력을 그대로 되돌려 주면 반사형 주입과 개인정보 노출 위험이 있다.

## 5. 변경 파일

- 신규: `monitor/ErrorCode.java`, `monitor/ApiException.java`, `monitor/ApiExceptionHandler.java`
- 수정: `monitor/IpoController.java`, `monitor/TradingSystemController.java`
- 테스트
  - 신규 `monitor/ApiExceptionHandlerTest`(7): 검증 실패 필드 목록, JSON 형식 오류, 업무 예외 확장 필드, 409, 502 원인 비노출, 500 내부 정보 비노출, 코드 enum 고유성
  - 신규 `monitor/TradingSystemControllerTest`(2)
  - `IpoControllerTest`: 404·409·400 세 건을 새 계약에 맞췄다
  - `RequestValidationTest`: 전역 처리기를 붙이고 `VALIDATION_FAILED`, `errors[0].field`를 확인한다

## 6. 함정과 주의

- 새 컨트롤러 오류는 `ResponseEntity.status(...)`로 직접 만들지 말고 `ApiException`을 던진다. 새 오류 종류가 필요하면 `ErrorCode`에 상수를 추가한다.
- `ApiException`의 detail은 그대로 클라이언트에 나간다. 내부 원인을 넣지 않는다.
- 이 처리기는 `@RestControllerAdvice` 전역이라 springdoc(`/v3/api-docs`)과 정적 리소스 404에도 적용된다. 상태 코드는 같고 본문만 ProblemDetail이다.
- monitor 모듈이 `kiwoom.KiwoomApiException`을 안다. `502` 매핑을 위한 것이며 ArchUnit 규칙 대상(strategy, risk, market)이 아니다.

## 7. 롤백

- 커밋을 되돌린다. 설정과 스키마 변경은 없다.

## 8. 검증 상태

- `.\gradlew.bat test` 전체 통과(2026-09-30). 결과:
  - `ApiExceptionHandlerTest` 7
  - `TradingSystemControllerTest` 2
  - `RequestValidationTest` 8
  - `IpoControllerTest` 8
  - `ArchitectureRulesTest` 8
  - `ModularityTests` 통과
- `Content-Type: application/problem+json`을 테스트로 확인했다.
- Red: 새 테스트는 처리기가 없을 때 컴파일되지 않았다(동작 Red는 미실행).
- **미검증:** 실제 기동 후 브라우저와 Swagger에서 오류 응답 확인.

## 9. 남은 일

- 프론트엔드가 `errors`와 `detail`을 표시하도록 개선(선택)

## 10. 변경 이력

- 2026-09-30: 최초 작성
