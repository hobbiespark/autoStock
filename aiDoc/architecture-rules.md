# 의존 규칙 테스트 (A3)

- 날짜: 2026-09-30
- 계획: `refactoring-plan.md` A3

## 1. 목적

`docs/ARCHITECTURE.md`의 방향 규칙은 문서로만 존재했다.

- 2절: execution은 trading을 모른다, portfolio는 리프
- 5절: Functional Core
- 11절: strategy→kiwoom 금지, risk→kiwoom 금지 등
- 13절: 설계 규칙 1·2·3·5

`ModularityTests`(Spring Modulith `verify()`)는 모듈 경계와 순환만 검사한다. 공개 타입을 통한 "허용되지만 설계상 금지된" 방향은 잡지 못한다. 그래서 규칙 §3("모듈 의존 방향을 빌드로 강제")과 §18.2(ArchUnit)에 따라 테스트로 고정했다.

## 2. 결정과 근거

- `app/src/test/java/com/autostock/ArchitectureRulesTest.java`(신규) 7개 규칙:
  1. strategy → kiwoom / execution / trading 금지(설계 규칙 1·2·5)
  2. strategy → `OrderRequest`·`CancelRequest` 의존 금지(설계 규칙 1·3)
  3. `OrderRequest` 생성은 risk(RiskGate)와 오프라인 backtest만(설계 규칙 3). 생성자 시그니처에 묶이지 않게 "호출 대상의 소유 클래스"로 판정한다.
  4. risk → kiwoom 금지(ARCH 11절)
  5. execution → trading 금지(ARCH 2절)
  6. portfolio → risk / trading / execution / strategy 금지(ARCH 2절 리프)
  7. 순수 계산 코어(`strategy/*Math`, `risk/KrxTickSize`)는 `java.lang/math/util/time`과 자기 패키지만 의존하고, `Clock`을 쓰지 않는다(ARCH 5절)
- 규칙 7에서 `PositionSizer`를 뺐다. `@Component` 빈이고 `RiskProperties`를 주입받아, 순수 정적 함수가 아니다. 넣으면 즉시 위반이다. 탐색 보고서가 "순수 함수 코어"로 분류했지만 코드상 사실이 아니다(확인).
- ArchUnit을 `testImplementation 'com.tngtech.archunit:archunit:1.4.2'`로 명시했다(규칙 §11).
  - 전이 의존에 기대면 Modulith가 올라갈 때 조용히 바뀐다.
  - Modulith BOM은 ArchUnit 버전을 관리하지 않는다(BOM pom에서 확인).
  - 선언 전후 모두 1.4.2로 해석된다.

## 3. 버린 대안

- **market → kiwoom 금지 규칙**: 지금은 위반이다(`MarketQueryService`, `MinuteBarArchiver`가 `KiwoomRestClient`를 직접 사용). A2(시세 포트 추출) 뒤에 추가한다. 위반을 허용 목록으로 끼워 넣는 규칙은 만들지 않았다.
- **ArchUnit JUnit5 엔진(`archunit-junit5`, `@ArchTest`)**: 의존성이 하나 더 는다. core API의 `check()`로 충분하다(규칙 §11 최소 의존성).

## 4. 변경 파일

- `app/build.gradle`: `testImplementation 'com.tngtech.archunit:archunit:1.4.2'`
- `app/src/test/java/com/autostock/ArchitectureRulesTest.java`(신규)

## 5. 함정과 주의

- 규칙 7은 `System.currentTimeMillis()` 같은 `java.lang` 경유의 시각 접근까지는 막지 못한다. `Clock`과 프레임워크 의존만 막는다.
- ArchUnit 1.x는 규칙 대상이 비어 있으면 실패한다(`failOnEmptyShould` 기본값). 클래스 이름을 바꾸면 규칙도 함께 고쳐야 한다.

## 6. 롤백

- 테스트 파일과 의존성 한 줄을 지운다. 운영 코드 변경은 없다.

## 7. 검증 상태

- `.\gradlew.bat test` 전체 통과(2026-09-30). `ArchitectureRulesTest` 7건, `ModularityTests` 통과.
- **Red 확인(실측)**: 규칙을 어기는 임시 클래스를 `strategy` 패키지에 넣어 실행했다. 이후 임시 파일은 삭제하고 `git status`로 확인했다.
  - kiwoom 참조, `OrderRequest` 생성, `Clock`·Spring 의존 → 규칙 1·2·3·7이 실패했다.
  - 규칙 4·5·6은 같은 패턴이라 임시 위반 실행은 생략했다(미실행).

## 8. 남은 일

- ~~A2 완료 후 market → kiwoom 금지 규칙 추가~~ 2026-09-30 A2에서 추가했다(어댑터 2개만 허용, `market-data-port.md`).

## 9. 변경 이력

- 2026-09-30: 최초 작성
