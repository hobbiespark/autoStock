# R16 별도 loopback 운영자 listener·CLI (P2-1)

2026-10-26 source/표적시험 완료, 브랜치 `feat/p2-1-operator-listener`(main 097928a 기준). 사용자 확정(2026-10-26) "별도 loopback listener + HMAC·요청 원장"을 VALUNIVO 143 채택 설계대로 구현했다. 기존 business 웹서버(8080)에 route를 추가하지 않았고 X-Autostock-Operator 토큰 헤더 초안은 쓰지 않았다. stop/observe만 노출하며 withdraw·resume·거래 재개 경로는 없다. main 병합·운영 재기동·플래그 활성화·실제 키 생성·이관은 하지 않았다(G2/G3 사용자 관문).

## 구성 요소

- `HandoverOperatorListenerConfiguration`: `autostock.telegram.handover.operator-listener.enabled=true`일 때만 존재한다. 기본 off(application.yml에 명시)이면 bean이 없으므로 포트 바인드 0·키 파일 읽기 0이다. on이면 ① 기존 `TelegramReceiverStopStore`(autostock.telegram.handover.enabled)와 `TelegramCommandPoller`(monitor.telegram.enabled) bean 존재 ② `port` 명시·1024~65535·`server.port`와 다름 ③ `key-id`·`instance-id`·`key-file` 명시를 차례로 확인한 뒤에야 키 파일을 읽는다. 어느 하나라도 거절이면 기동 실패이며 기본값·자동 생성·fallback은 없다.
- `HandoverOperatorKeyFile`: 절대 경로만. NOFOLLOW 속성으로 일반 파일인지, symlink/기타 reparse가 아닌지, 128바이트 이하인지 보고, `toRealPath()`가 정규화 경로와 같아야 한다(상위 디렉터리 symlink·junction 차단). 내용은 소문자 hex 64자 + 선택적 LF/CRLF 하나만 허용한다. 오류 문구는 `operator-key-file-rejected` 하나이며 키 값·경로·원인 예외를 담지 않는다. 읽은 버퍼는 0으로 지운다.
- `HandoverOperatorListener`: JDK `HttpServer`를 `127.0.0.1`(주소 설정 불가)·명시 포트에 바인드, 처리 스레드 2개. 요청마다 실제 socket의 local/remote가 loopback이고 local 포트가 자기 포트인지 검사한다. Host·Forwarded·X-Forwarded-For는 인증에 쓰지 않는다. 그 뒤 query/fragment/absolute-form 거절 → 정확한 path → POST → Origin 거절(CORS 없음) → Content-Encoding·Transfer-Encoding 거절 → Content-Length 1~4096 → 144 gate → (stop) 기존 active 정지 사전확인 → 150 coordinator. 로그에는 상태코드·오류코드만 남기고 본문·MAC·키는 남기지 않는다.
- `HandoverOperatorSigner`: 클라이언트 측 정규 body와 gate와 동일한 길이 프레이밍 HMAC(독립 구현, 시험으로 gate와 상호 검증).
- `HandoverOperatorCli`(Gradle `:app:operatorHandoverCli`): listener 클라이언트일 뿐 poller·DB·Bot API를 띄우지 않는다. 키는 `--key-file` 경로로만 받고 값 인자는 없다. 새 request ID·현재시각(ms)·유효 20초로 서명, HTTP/1.1·redirect 미추적. stop은 보내기 전에 `request_id=`를 stderr에 먼저 출력하므로 응답이 유실돼도 observe 대상이 남는다. 같은 ID 재전송 옵션은 없다. 종료코드 0=2xx, 2=오류 응답, 3=응답 없음(결과 불명→observe), 64=사용법/키 파일 거절.

## 요청·응답 계약 (P2-2c가 호출할 때)

요청: `POST http://127.0.0.1:<port>/receiver-handover/{stop|observe}`, 헤더 `Content-Type: application/json`, `Content-Length`, `X-Operator-Key-Id: <key-id>`, `X-Operator-Signature: <소문자 hex 64>`. 서명 = HMAC-SHA256(key32, 길이프레이밍[`autostock-operator-handover-request-v1`, key-id, `POST`, path, 원본 body bytes]), 각 항목 앞에 4바이트 big-endian 길이. body는 정확히 8필드: `contract_version:"1.0"`, `request_id`(정규 UUID), `action`(path와 일치), `instance_id`(설정값), `expected_active_stop_id:null`, `target_request_id`(stop=null, observe=대상 stop request ID), `issued_at`/`expires_at`(정규 Instant, 미래 발급 금지, 최대 30초).

성공 응답(200, 공통 `contract_version`,`request_id`,`action`):
- stop: `result:"stopped"`, `stop:{stop_id, confirmed_next_offset, pending_resume_dropped, instance_id, recorded_at}`.
- observe: `result:"observed"`, `target_request_id`, `state`, `received_at`, `request_expires_at`, `attempt:{state, execute_before}|null`, `stop:{…, active}|null`.

observe `state` 의미: `received`=접수만(claim 없음, 실행 안 됨) · `running`=claim 발급 뒤 결과 미기록(진행 중이거나, 기한 경과 전) · `unknown`=실행 결과 불명으로 보수 기록(같은 요청 재실행 없음, 수신이 멈췄다고 간주하지 말 것) · `stopped`=이 요청에 연결된 원본 정지 행이 active(수신 정지 증거) · `withdrawn`=연결된 정지가 이후 철회됨. `stop.recorded_at`/`confirmed_next_offset`은 원본 값이며 observe가 갱신하지 않는다. stopped는 앱이 기록한 정지 증거일 뿐 플랫폼 수신 시작·이관 성공이 아니다.

오류(`{contract_version, error}`):

| HTTP | error | 의미·다음 행동 |
|---|---|---|
| 400 | operator-handover-malformed | query/fragment/absolute-form/인코딩/chunked/길이 문제. 효과 없음 |
| 403 | operator-handover-denied | 비loopback·Origin·키ID/서명/기한/path·action/instance 불일치·observe 문맥 거절. 효과 없음 |
| 404 | operator-handover-not-found | 다른 path(withdraw/resume 포함) |
| 404 | operator-request-not-found | observe 대상이 접수된 적 없음(stop이 gate/사전확인에서 거절된 경우 포함) |
| 405 | operator-handover-method-not-allowed | POST 외 |
| 409 | operator-receiver-already-stopped | 이미 active 정지 존재. 이번 요청은 접수하지 않음. 원래 요청 observe |
| 409 | operator-request-already-received-use-observe | 같은 request ID 재전송. 재실행 없음. observe |
| 409 | operator-request-id-conflict | 같은 ID·다른 본문. 거절 |
| 413 | operator-handover-too-large | 4KiB 초과 |
| 503 | operator-stop-outcome-unconfirmed-use-observe | 실행 중 실패, unknown 기록 시도. 재전송 금지, observe |
| 503 | operator-handover-unavailable-use-observe | 원장/DB 불가 등. 접수 여부 불명일 수 있음. observe(404면 미접수) |

응답 유실·연결 끊김은 결과 불명이다. 같은 stop을 다시 보내지 말고 새로 서명한 observe(`target_request_id`=원래 ID)로 대조한다. 같은 ID 재전송은 409로 끝나며 Bot API 확인 호출을 반복하지 않는다.

## 검증

JDK21, worktree 자체 build 디렉터리(운영 작업트리·JAR 무관), D-18 고정 Timescale 새 Testcontainers(`AUTOSTOCK_TEST_DB_URL` 해제, 운영 15432 미사용), Bot API는 fake exchange만, 샘플 키만. 한 번의 Gradle 실행에서 15개 클래스 103건/실패0/skipped1.

- 신규 19(통과18·skip1): `HandoverOperatorKeyFileTest` 6(정상 3형식, 상대·없음·디렉터리·`..` 경로, 129바이트·형식오류 9종, junction 상위 디렉터리 거절, 오류에 키·경로 미포함; symlink 1건은 계정 권한 없음으로 skip), `HandoverOperatorListenerConfigurationTest` 5(off/미설정/"FALSE-ish"면 bean 0·포트 바인드 가능·존재하지 않는 키 경로 미접근, on인데 stop store/poller 없음·포트 0/80/8080/70000/빈값/문자/누락/server.port와 동일·설정 누락·키 거절이면 기동 실패와 포트 미점유, 정상 on은 127.0.0.1 바인드 후 context 종료 시 해제), `HandoverOperatorListenerDbTest` 8(실제 loopback socket: 정상 stop 200·Forwarded 위조 무관·같은 bytes 재전송 409·Bot API 확인 1회, 확인 실패 503→같은 ID 409·다른 본문 409 conflict·observe unknown·호출 1회, 응답을 읽지 않고 끊은 뒤 observe stopped·호출 1회, 없는 target 404, 서명 변조·다른 키ID·서명 누락·path 교차·미래발급·31초·만료 403·효과0, withdraw/resume/끝슬래시/대문자/인코딩 path/business path 404·query·absolute-form 400·GET/PUT/OPTIONS/DELETE 405·gzip/chunked 400·Origin 403·5000바이트 413·0바이트 400, loopback 판정 단위와 비loopback IPv4 주소로의 접속 거부, CLI stop/observe 왕복·재요청 409(종료2)·listener 없음 종료3·키 거절/금지 인자 종료64·출력에 키 없음).
- 관련 회귀 84/실패0: gate7·접수7·claim7·dispatch6·stop 연결5·coordinator4·`TelegramReceiverHandoverDbTest`9·poller 기타25·`SchemaAndTimeZoneDbTest`3·`ArchitectureRulesTest`11.
- 1차 실행에서 구성 시험 3건이 실패했다. 원인은 시험 구성(`withBean`으로 등록한 stop store/poller 클래스 자체의 조건 플래그 미설정)이었고 제품 코드는 바꾸지 않았다. 시험에 두 기존 플래그를 켠 뒤 위 최종 실행에서 통과했다.
- 전체 회귀(1092+)는 반복하지 않았다. 운영 DB·봇·offset·키·설정·서비스·거래제어 변경 0.

## 한계

- 실제 운영 키 위치·생성·ACL, 운영 설정·활성화, 실제 이관은 미실행(G2/G3). 샘플 키만 시험했다.
- loopback 경계는 같은 PC의 다른 프로세스를 막지 않는다. 그 구분은 HMAC 키 파일 ACL에 의존하며 개인 PC 관리자까지 차단한다는 주장은 하지 않는다.
- JDK HttpServer의 요청 시간 제한은 전역 시스템 속성이라 설정하지 않았다. loopback 전용·스레드 2개라 느린 로컬 클라이언트가 처리 스레드를 점유할 수 있다.
- 기존 active 정지 사전확인과 coordinator 사이 경합은 coordinator/poller가 다시 막는다(이 경우 503 unconfirmed + unknown).
- symlink 파일 거절 시험은 이 계정에 symlink 생성 권한이 없어 skip했고, 같은 경로 검사를 junction(reparse point) 상위 디렉터리로 실제 시험했다.
