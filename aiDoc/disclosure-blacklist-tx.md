# 공시 블랙리스트 삭제 트랜잭션 (Phase 0.3)

- 날짜: 2026-10-01
- 계획: `upgrade-2026-10/11-execution-plan.md` 0.3, 감사 `upgrade-2026-10/09-audit-be.md` BE-P0-1
- 사용자 확정: 2026-10-01 Phase 0 착수("다음")

## 1. 목적

감사에서 "공시 블랙리스트 삭제가 트랜잭션 밖이라 반영되지 않을 수 있다"를 **추론**으로 올렸다. 계획대로 실패하는 테스트를 먼저 만들어 확인했다.

- 확인 결과: 추론보다 나빴다. 파생 삭제(`deleteByExpiresOnBefore`, `deleteBySymbol`)를 트랜잭션 밖에서 부르면 `InvalidDataAccessApiUsageException: No EntityManager with actual transaction available for current thread - cannot reliably process 'remove' call` 예외가 나고 **한 건도 지워지지 않는다**. 파생 삭제는 대상 엔티티를 읽은 뒤 하나씩 `remove`하는데, 여기에 트랜잭션이 필요하다.
- 영향:
  - 매일 08:00 만료 해제 배치(`DisclosureBlacklistExpiryScheduler` → `releaseExpired`)는 만료된 행이 처음 생기는 날부터 예외로 끝난다. 그 뒤의 메모리 캐시 재구성도 건너뛴다.
  - 매수 차단 판단은 영향이 없다. `isBlacklisted`가 만료일을 호출할 때마다 비교하기 때문이다. 대신 만료된 행이 DB에 쌓인다.
  - 운영 로그(9/18~9/30)에는 이 예외가 없다. 등록 기간이 180일이라 아직 만료된 행이 없는 것으로 본다(잠복 결함).
  - 수동 `remove`는 현재 호출하는 곳이 없다(골격만 있음).

## 2. 결정과 근거

- **저장소 메서드에 `@Transactional`:** 두 파생 삭제 메서드가 각자 트랜잭션에서 커밋한다. 호출하는 쪽이 트랜잭션을 몰라도 되고, 계획의 "캐시 갱신은 커밋 후"가 자연히 지켜진다. 저장소 호출이 끝나면 이미 커밋된 상태다.
- **`remove` 순서 변경:** DB 삭제를 먼저 하고 메모리는 그 뒤에 비운다. 삭제가 실패하면 예외가 나고 차단은 유지된다(안전 방향). 예전 순서(메모리 먼저)에서는 메모리 차단은 풀렸는데 DB 행은 남아, 재기동하면 차단이 되살아났다.
- `releaseExpired`는 이미 "DB 삭제 → 캐시 재구성" 순서라 설명만 보강했다.

## 3. 버린 대안과 보류

- **`DisclosureBlacklist.remove/releaseExpired`에 `@Transactional`:** 메모리 캐시 갱신이 커밋 전에 일어나 커밋이 실패하면 어긋난다. 또 `@DataJpaTest`로는 `@Component`를 띄우지 않아 같은 조건으로 검증하기 어렵다.
- **`@Modifying @Query` 벌크 삭제(JPQL):** 한 문장이라 빠르지만 영속성 컨텍스트를 우회한다. 행 수가 적어(종목당 수 건) 이점이 없다.

## 4. 변경 파일

- 운영 2개
  - `risk/DisclosureBlacklistRepository` — `deleteByExpiresOnBefore`·`deleteBySymbol`에 `@Transactional`
  - `risk/DisclosureBlacklist` — `remove` 순서(DB 먼저), 설명
- 테스트
  - 신규 `risk/DisclosureBlacklistRepositoryDbTest` 2건(실제 PostgreSQL, 운영처럼 트랜잭션 밖 호출 — `@Transactional(propagation = NOT_SUPPORTED)`): 종목별 삭제, 만료 삭제. **수정 전 코드로 2건 모두 실패**(위 예외)를 확인한 뒤 고쳤다.
  - `DisclosureBlacklistTest` +1건: `remove`의 DB 삭제가 실패하면 예외가 나고 차단은 유지된다.

## 5. 함정과 주의

- `@DataJpaTest`는 테스트마다 쓰기 트랜잭션을 열기 때문에 저장소 호출이 거기에 합류해 이 결함을 가린다. 운영 호출 경로에 트랜잭션이 없는 저장소 메서드는 `NOT_SUPPORTED`로 검증한다. 이 경우 커밋된 행은 `@AfterEach`에서 직접 지운다.
- 같은 모양의 파생 삭제가 다른 저장소에도 있으면 같은 결함이다. 2026-10-01 기준 `deleteBy…` 파생 삭제는 이 두 메서드뿐이다.

## 6. 롤백

- 커밋을 되돌린다. 스키마·설정 변경은 없다.

## 7. 검증 상태

- 컨테이너 테스트(내장 PostgreSQL 16.15): 수정 전 DB 테스트 2건 실패 → 수정 후 통과. 공시 블랙리스트 관련 테스트 11건 통과.
- 운영에서는 만료된 행이 처음 생기는 날 08:00 로그에 "공시 블랙리스트 만료 해제: N건"이 찍히는지 확인한다(예외 없음).
