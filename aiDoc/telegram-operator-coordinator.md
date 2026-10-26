# R16 운영자 stop coordinator·원본 관측

2026-10-26 source/표적시험 완료. 사용자의 현재 기준 커밋 요청에 따라 검증된150 범위를 커밋한다. OperatorHandoverCoordinator는 명시 생성만 가능하며 Bean/listener/키파일/worker가 없다. 자기 gate→불변 요청 접수→최초 claim→durable dispatch→poller lock/상태확인 뒤 enter 한 번→처리분 offset 확인→pendingResume 폐기→원본 stop+request 연결 순서다. 접수 중복은 observe 안내로 거절하고 claim/dispatch를 재발급하지 않는다. 실패 시 가능한 범위에서 unknown을 추가하며 자동 retry/resume은 없다. claim을 반환받기 전 commit 불명은 유실 토큰을 재구성하지 않고 기한 뒤 observe로 보수 처리한다.

recordStopped는 자기 store의 이미-entered private Dispatch·원본 token SHA·dispatch 결합만 허용한다. 입력 TTL은 실행 진입 전에 검사하며, 확인 I/O 도중 TTL이 지나도 이미 완료된 정지 증거를 버리지 않도록 원본 정지 행은 기록한다. DB 저장/commit 결과가 불명해도 재실행하지 않는다. fresh observe는 같은 키/instance의 접수·시도와 원본 stop 연결을 읽는다. 연결된 원본이 있으면 stopped/withdrawn으로 표시하고 기한이 지난 시도 메타데이터 unknown은 보존한다. 원본 recordedAt/confirmed offset을 새 요청 시각으로 바꾸지 않는다. stop 행은 앱이 기록한 증거이며 플랫폼 수신 시작·운영 이관 성공을 뜻하지 않는다.

발견/수정: 기존 poller는 정지 기록 중 RuntimeException 때 handoverStopped=false를 캐시했다. DB commit은 성공했지만 응답만 유실된 경우 다음 poll에서 durable stop을 무시할 수 있었다. 이제 null로 돌려 다음 회차 전에 원본 정지원장을 다시 조회한다. 확인 자체가 실패하고 정지행이 없으면 기존 polling은 이어진다. 실제 /stop 킬스위치와 60초 1회용 /resume 규칙을 다른 제어에 매핑하지 않는다.

이번 JDK21/runtime layout2·D18 고정 Timescale 새 Testcontainers: coordinator4 + poller9(기존8/신규 유실1) + 관련 attempt7/dispatch6 =26/실패0/skipped0. 신규5와 관련21을 구분한다. 인증된 접수/단일 호출/원본 observe·만료 뒤 진입 거절·확인 실패 unknown/같은 요청 재실행0·확인 중 TTL 경과 후 원본 증거 보존·commit 응답 유실 후 polling 차단을 검증했다. Bot API는 fake exchange만이며 외부 통신0. 최종 시험 컨테이너 제거 확인. 운영DB/봇/offset/키/설정/서비스·거래제어 변경0·54/118 재시도0·M6/D80 유지. 기존 infra/복구 및 사용자 자본한도 변경을 보존했다.

남은 한계: OS/socket 경계·기본off 별도 loopback listener/HTTP 응답·CLI·운영 키파일/ACL·신뢰 설정·실제 활성/수신 이관은 미구현/미적용이다. withdraw/resume endpoint는 제공하지 않는다. 다음151은 별도 listener의 제한 HTTP/socket 입구 설계/구현과 합성 시험이며, 150 검증 범위와 무관한 기존 인프라/복구 미커밋 변경은 별도로 보존한다. 실제 운영 키 위치/생성·활성/이관은 별도 확인 대상이다.
