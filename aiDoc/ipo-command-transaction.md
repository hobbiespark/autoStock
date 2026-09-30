# IPO 수동 입력 트랜잭션 경계 (R3)

- 날짜: 2026-09-29
- 계획: `refactoring-plan.md` R3

## 1. 목적

`monitor/IpoController`의 `POST /api/ipo/{id}/record`와 `/metrics`는 컨트롤러 안에서 다음 순서를 트랜잭션 없이 실행했다.

`findById` → 엔티티 변경 → (`evaluateFilter`, `refreshStatus`) → `save`

그래서 조회와 저장이 각각 리포지토리 기본 트랜잭션으로 따로 열렸다. 업무 흐름 조율(필터·상태 재평가)도 웹 어댑터에 있었다.

- 규칙 §7 "트랜잭션은 애플리케이션 서비스에서 연다"
- 규칙 §3 "컨트롤러는 변환·호출만"
- ARCH §10 "Command는 Application Service 호출"

## 2. 결정과 근거

- `ipo/IpoDealCommandService`(신규, `@Service`)를 만든다. `recordMyDeal`과 `updateMetrics`가 `@Transactional`로 조회·변경·재평가·저장을 한 경계에 묶는다.
- 없는 딜은 `Optional.empty()`를 반환하고, 컨트롤러가 `404`로 바꾼다. 기존 응답 계약은 바뀌지 않았다.
- 입력은 ipo가 소유한 `RecordCommand`와 `MetricsCommand` record로 받는다.
  - monitor의 요청 DTO(`IpoRecordRequest` 등)를 ipo가 참조하면 ipo → monitor 역의존과 순환이 생긴다.
  - 같은 타입의 위치 인자 6개를 그대로 넘기면 순서가 바뀌어도 컴파일된다(규칙 §4).
- 조회(`GET /api/ipo`)는 기존대로 컨트롤러가 `IpoDealRepository`를 직접 참조한다. 문서화된 기존 패턴(`OrderHistoryController`, ARCH 규칙 20)이므로 유지한다(§0 유지보수).
- 필터와 상태 재평가 로직(`IpoSyncScheduler.evaluateFilter`, `refreshStatus`)은 옮기지 않고 서비스가 호출만 한다. 범위를 최소로 잡았다.
- 서비스는 명시적 `save`를 유지한다. 관리 상태 엔티티라 dirty checking으로도 반영되지만, 트랜잭션이 빠지는 경우에도 저장이 보장된다.

## 3. 버린 대안

- **컨트롤러에 `@Transactional`**: 웹 어댑터가 트랜잭션 경계를 갖게 된다(§3 위반).
- **요청 DTO를 ipo로 옮기기**: 웹 계약(API 문서 Javadoc)이 도메인 모듈로 샌다.
- **필터 정책을 별도 클래스로 추출**: 지금은 호출처가 둘뿐이다(YAGNI). 필요하면 B3에서 검토한다.

## 4. 변경 파일

- `app/src/main/java/com/autostock/ipo/IpoDealCommandService.java`(신규)
- `app/src/main/java/com/autostock/monitor/IpoController.java`: 명령 두 개를 서비스 호출로 바꾸고 `toResponse` 추가, `IpoSyncScheduler` 의존 제거, Javadoc 갱신
- `app/src/main/java/com/autostock/ipo/package-info.java`: monitor 참조 설명 갱신
- `app/src/test/java/com/autostock/ipo/IpoDealCommandServiceTest.java`(신규): 테스트 5건
  - 없음 → 빈 결과 + 저장 안 함(두 명령)
  - 부분 갱신 저장
  - 지표 입력 → RECOMMEND·LISTED 재평가
  - 두 메서드의 `@Transactional` 고정
- `app/src/test/java/com/autostock/monitor/IpoControllerTest.java`: 생성자 변경만 반영. 기존 7건이 회귀 검증을 한다.

## 5. 함정과 주의

- `@Transactional`은 프록시로 동작한다. 서비스 내부에서 자기 메서드를 호출하면 경계가 걸리지 않는다. 현재는 컨트롤러 → 서비스 호출만 있다.
- 트랜잭션은 한 요청 안의 원자성만 보장한다. **동시 갱신(배치 `IpoSyncScheduler.syncNow`의 `findAll → saveAll`과 수동 입력이 겹칠 때 나중 저장이 앞 저장을 덮는 문제)은 막지 못한다.** `IpoDealEntity`에 `@Version`이 없기 때문이다. 발생 가능성은 **추론**이다(배치는 08:20과 매시 5분 따라잡기, 수동 입력은 사람). R2(`@Version`) 조사 때 함께 판단한다.
- 반환된 엔티티는 트랜잭션이 끝난 뒤 컨트롤러에서 View로 변환된다. 연관관계가 없는 단순 컬럼뿐이라 지연 로딩 문제는 없다(코드 확인).

## 6. 롤백

- 커밋을 되돌린다. 스키마와 설정 변경은 없다.

## 7. 검증 상태

- `.\gradlew.bat test` 전체 통과(2026-09-29). 결과:
  - `IpoDealCommandServiceTest` 5건
  - `IpoControllerTest` 7건
  - `IpoSyncSchedulerTest` 10건
  - `ModularityTests` 통과
- Red 확인: 서비스 클래스를 만들기 전에는 새 테스트가 컴파일되지 않았다.
- **미검증**: 실제 DB에서 트랜잭션이 하나로 묶이는지는 확인하지 않았다. 통합 테스트 인프라(Testcontainers)가 없다(P3 결정 대기). 애너테이션 존재는 테스트로 고정했다.

## 8. 남은 일

- R2 조사 때 `IpoDealEntity`도 동시 갱신 대상에 포함해 검토한다.

## 9. 변경 이력

- 2026-09-29: 최초 작성
