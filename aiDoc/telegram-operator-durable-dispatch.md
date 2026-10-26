# R16 durable dispatch 예약·단일 메모리 진입권

2026-10-26 source/표적시험 완료. 147 이후 접합을 나눠 148에서는 HandoverOperatorAttemptStore에 private Dispatch와 dispatch/enter를 추가했다. V28은 request/attempt FK의 불변 예약 한 행이다. 자기 claim/동일 지문·원본 접수·fresh gate·unknown 아님·기한을 UUID 잠금 안에서 검사하고 최초 예약을 commit한 뒤에만 private Dispatch를 반환한다. 중복/재접속으로 다시 발급하지 않는다. 토큰 원문은 DB/로그에 저장하지 않는다.

enter는 poller lock 뒤 guard가 사용할 경계다. 자기 Dispatch·원본 DB 예약·동일 유효 claim을 대조하고 AtomicBoolean을 한 번만 소비한다. commit 지연 뒤에도 기한을 재검사하며 오류가 나면 소비를 되돌리지 않는다. 같은 객체의 동시 진입/합성 효과 실패 후 재진입은 거절한다. commit 실패/프로세스 유실로 성공 여부가 불분명한 실제 예약은 재발급·실행하지 않고 observe/unknown 대상이다. 시험에서 deferred commit이 확실히 rollback된 경우만 첫 예약을 새로 시도했다. 일반 자동 재시도 기능은 없다.

이번 JDK21/runtime layout2·D18 고정 Timescale 새 Testcontainers: 신규 dispatch6 + 공통 current 검사 리팩터링 영향 attempt7 + schema 기대28 검증3 =16/실패0/skipped0. 단일 durable 예약/재접속·foreign store 거절, 합성 응답 유실 뒤 재진입0, 동시 진입1, 만료/신선 observe unknown, deferred commit 실패, DB 수정/삭제 거절을 검증했다. 실제 Telegram/HTTP 호출0·최종 시험 컨테이너 제거 확인. 전체1092 회귀는 반복하지 않았다.

아직 poller callback과 private Dispatch를 연결하지 않았고, request/attempt↔실제 stop 행의 원자적 결과 연결·성공 조회도 미구현이다. dispatch/enter는 실행 준비 경계이며 실제 정지 성공 증거가 아니다. Bean/listener/CLI/키파일/운영 DB·봇·offset·서비스·거래제어 변경0. 54/118 재시도0·M6/D80 유지, 기존 infra/복구 dirty 보존.

다음149: request/attempt↔stop 원본을 같은 DB transaction에서 기록하고 poller adapter/유실 관측을 source·합성 시험으로 연결한다. 기존 active stop을 새 요청 성공으로 채택하거나, 결과가 불명하다고 재정지/자동 resume하지 않는다. listener/CLI·운영 키/활성/실제이관은 후속이다.
