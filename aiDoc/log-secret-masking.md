# 로그 비밀값 마스킹 — ECOS 경로형 키 (S1)

- 날짜: 2026-09-29
- 계획: `refactoring-plan.md` S1

## 1. 목적

ECOS API 키가 URL **경로 세그먼트**에 실린다(`/api/StatisticSearch/{key}/json/...`, `EcosClient.callApi`).
그런데 `SecretMasking.RULES`는 쿼리·JSON·`bot<token>`·`Bearer` 형태만 잡았다.
그래서 ECOS 호출이 실패하면 WebClient 예외 메시지(호출 URL 전체를 포함)를 통해 키가 로그 파일에 평문으로 남을 수 있었다.
소스 지점 방어(`sanitizeForLogging`)와 로그백 최후 방어(`ThrowableMaskingConverter`) 두 겹 모두 뚫려 있었다.

## 2. 결정과 근거

- **규칙 한 곳에 추가(SSOT, 규칙 §2.1).** `SecretMasking.RULES`에 `(?i)(/api/[A-Za-z]+/)[A-Za-z0-9]{8,}(?=/(?:json|xml)/)` → `$1***`를 추가했다.
  - 두 방어 겹이 같은 `RULES`를 쓰므로 한 번 추가로 둘 다 막힌다.
  - 서비스명을 `StatisticSearch`로 고정하지 않은 이유: ECOS의 다른 서비스(`KeyStatisticList` 등)도 같은 URL 구조다.
  - 뒤따르는 `/json/` 또는 `/xml/`을 조건으로 건 이유: DART `/api/list.json` 같은 일반 경로를 오탐하지 않기 위해서다.
- **기존 관례를 따름(§0 유지보수).** DART와 Telegram 클라이언트처럼 catch 로그에서 `SecretMasking.sanitizeForLogging(e)`를 거친다.
  - 적용 위치: `EcosClient.fetchLatest`, `FredClient.fetchLatest`
  - FRED 키(`api_key=` 쿼리)는 기존 규칙으로도 잡히지만, 관례를 통일하려고 함께 적용했다.
- **응답 본문 전체 로그 제거(규칙 §6).**
  - ECOS "StatisticSearch 없음" 경고는 `RESULT`(CODE·MESSAGE)만 남긴다.
  - 파싱 실패 로그는 ECOS와 FRED 모두 최상위 키 목록(`response.keySet()`)만 남긴다.

## 3. 버린 대안

- **ECOS 키를 쿼리 파라미터로 옮기기**: API 사양상 경로에 넣어야 한다.
- **ECOS 전용으로 `stripPath` 유틸 추가**: 호출부마다 기억해야 하는 방어가 늘어난다. 최후 방어(로그백 컨버터)에는 적용되지 않는다.

## 4. 변경 파일

- `common/src/main/java/com/autostock/common/util/SecretMasking.java`: 규칙 추가, Javadoc 배경과 규칙 목록 갱신
- `common/src/test/java/com/autostock/common/util/SecretMaskingTest.java`: 테스트 3건 추가
  - ECOS 경로 마스킹
  - 일반 `/api/` 경로 비오탐
  - `sanitizeForLogging` 경로 키 마스킹
- `app/src/test/java/com/autostock/config/logging/SecretMaskingConverterTest.java`: 스택트레이스의 ECOS 경로 키 마스킹 테스트 1건 추가
- `app/src/main/java/com/autostock/macrointel/EcosClient.java`: catch에서 `sanitizeForLogging` 사용, 응답 본문 로그 축소
- `app/src/main/java/com/autostock/macrointel/FredClient.java`: 위와 같다.

## 5. 함정과 주의

- 키 패턴은 `[A-Za-z0-9]{8,}`다. ECOS 키에 영숫자 이외 문자가 섞이면 규칙이 빠진다. 실제 키 형식은 영숫자 20자로 추정한다(**추론**, 실키로 대조하지 않음).
- 규칙은 목록의 마지막에 있다. 앞선 `key=value` 규칙과 겹칠 일은 없다(경로에는 `=`가 없다).
- 파싱 실패 로그에서 본문을 뺐으므로, 스키마가 바뀌어 파싱이 깨지면 원인 분석에 본문이 필요할 수 있다. 그때는 `docs/measured/`와 같은 수동 실측(`scripts/probe_external_apis.ps1`)으로 확인한다.

## 6. 롤백

- 해당 커밋을 되돌린다. 설정 변경은 없다.

## 7. 검증 상태

- `.\gradlew.bat test` 전체 통과(2026-09-29).
  - `SecretMaskingTest` 17건, `SecretMaskingConverterTest` 4건, `EcosClientTest`와 `FredClientTest` 통과
- 새 테스트가 수정 전에 실패하는 것(Red)을 확인했다(ECOS 2건 실패).
- **미검증**: 실제 ECOS 호출 실패 시 운영 로그 파일에 키가 남지 않는지는 실측하지 않았다. 문자열 기반 단위 테스트와 로그백 파이프라인 테스트로만 확인했다.

## 8. 남은 일

- 없음. 다음 항목은 R1(타임아웃)이다.

## 9. 변경 이력

- 2026-09-29: 최초 작성
