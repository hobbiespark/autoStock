# R16 polling 잠금 뒤 정지 요청 guard

2026-10-26 source/표적시험 완료. 146 다음 접합을 작은 단위로 나눠, 147은 기존 TelegramCommandPoller에 stopForHandover(instance, beforeConfirmation) overload만 추가했다. 기존 진입점은 no-op guard로 위임해 기존 동작을 유지한다. 필수 callback은 pollLock을 획득하고 현재 정지 상태 확인을 마친 뒤, handoverStopped 변경·확인 getUpdates·pendingResume 폐기·DB 정지기록 전에 실행한다. 거절되면 그 부작용을 시작하지 않는다. 실제 operator adapter가 자기 gate/claim의 requireCurrent를 이 지점에서 호출해야 한다.

이번 D18 고정 Timescale 새 Testcontainers·JDK21/runtime layout2: TelegramReceiverHandoverDbTest 8/실패0/skipped0(기존6+신규2). Bot API는 전부 fake exchange이며 실제 통신0. 신규: 거절 시 확인호출/정지행0·기존 유효 resume 코드/폴링 유지, in-flight 회차 동안 guard를 실행하지 않고 대기 후 만료거절/확인호출0. 기존 처리분 offset만 확인·재기동 차단/철회·pendingResume 폐기·확인실패 복귀·비활성 동작·append-only를 함께 검증했다. 시험 종료/컨테이너 제거 확인. 실제 운영 키/DB/봇/offset/서비스/제어 변경0·54/118 재시도0·M6/D80 유지.

이 callback은 인증/단일 dispatch 토큰 자체가 아니며 아직146 claim과 연결하지 않았다. request→stop 원본 상관관계·성공 원장·응답유실 복구·listener/CLI는 미구현이다. 기존 Java 내부 메서드가 새 운영 API로 노출됐다고 보고하지 않는다. 다음148은 durable 최초 dispatch 및 request/attempt↔stop 원본의 원자적 연결을 만들고 합성 DB/poller 시험한다. TTL이 끝난 뒤 결과 유실을 새 stop/자동 resume으로 해결하지 않는다. 운영 활성/실제 이관은 별도이다.
