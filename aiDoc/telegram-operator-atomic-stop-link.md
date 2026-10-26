# R16 운영자 요청과 원본 정지 행 원자적 연결

2026-10-26 source/표적시험 완료. 148 다음 접합 중 149는 저수준 persistence만 구현했다. TelegramReceiverStopStore.recordStoppedForOperator는 명시 request/attempt의 durable dispatch와 원본 instance를 대조하고, 기존 단일 active stop 잠금/검사 안에서 stopped 행과 V29 연결 행을 같은 transaction으로 저장한다. 연결 실패/중복이면 새 stopped 행도 rollback한다. 다른 요청의 기존 active stop은 충돌이며 새 요청 성공으로 채택하지 않는다. 원본/연결 행은 불변이다.

V29는 dispatch(request,attempt)와 stop(stop_id,stopped)의 FK·요청당1/stop당1·instance 대조 trigger·수정삭제 금지를 둔다. operatorStop은 request에 연결된 원본 offset/코드폐기/instance/기록시각과 현재 withdrawn 여부를 읽는다. 철회해도 원본 시각/offset은 바뀌지 않는다. 이 API는 앱 내부 저수준 DB helper이며 unsigned UUID가 인증된 실행 허가는 아니다. 실제 HTTP에서 사용하려면 fresh observe/자기 gate·권한 대조가 먼저 필요하다.

이번 JDK21/runtime layout2·D18 고정 Timescale 새 Testcontainers: 신규 연결5 + StopStore 변경에 따른 기존poller8 + schema 기대29 검증3 =16/실패0/skipped0. 원본 재접속/철회 관측·중복연결 rollback·기존 active stop 오채택 거절·deferred 연결 commit실패 원자 rollback·없는 dispatch/다른 attempt/instance 거절·DB 연결 불변을 확인했다. 실제 Bot API는 fake exchange만·운영 DB/봇/offset/서비스/거래제어 변경0. 최종 시험 컨테이너 제거 확인·전체 회귀 반복0·54/118/M6/D80 유지. 기존 infra/복구 dirty 보존.

한계: private Dispatch.enter를 poller callback에 연결하는 coordinator는 아직 없다. low-level 기록 API의 성공을 실제 Telegram 정지 완료나 운영 활성으로 보고하지 않는다. 다음150: 기본 bean 없는 명시 coordinator가 gate→접수→claim→dispatch→poller lock 뒤 enter→원본stop 결합을 잇고, 유실/만료를 재정지·자동 resume 없이 관측하게 한다. 합성 네트워크/격리 DB로 검증한다. listener/CLI·운영 키/활성/실제 이관은 후속이다.
