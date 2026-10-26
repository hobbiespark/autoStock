# 플랫폼 주식제어 durable 접수 기반

2026-10-26. StockControlReceiptStore/V21을 추가했다. 명시 DataSource/자기pinned verifier/AES256 key ID·메모리키만 받으며 Bean/endpoint/worker/운영신뢰/실제제어가 없다. receive는 private Verified만 받으며 트랜잭션 전·bot advisory lock뒤·커밋전에 자기verifier/기한을 재확인한다. 원본 proof(code포함)는 AES256-GCM 랜덤nonce/봇·update·keyID AAD로암호화한다. 확인코드/원문plaintext DB컬럼은없고 조회메타데이터/toString은code를표시하지않는다. key관리/rotation/decrypt·실행은후속이다.

봇/update 유일receipt와 최초요청 지문·최초fence/수신시각/received 상태를보존한다. intent는receiver fence만제외한typed인자지문을별도domain으로결합해, 새fence로동일update를재전달해도기존receipt/암호문/수신시각을반환한다. 코드/명령/주체/공간 등인자가바뀌면충돌이다. 이미수신된update의낮은fence는메타데이터복구만가능하고실행하지않는다. 새update는앱이본highest fence보다낮으면거절한다. 이watermark는플랫폼현재수신권live증명이아니며 같은fence의철회/미관측새fence는별도대조가필요하다.

봇fence상향과receipt는한tx·실패시rollback·자동retry0. 불확실commit은명시재전달로기존유일receipt를확인해야한다. DBtrigger가원본receipt update/delete를거절한다. 상태는received만허용하며첫실행/terminal/unknown/ACK를만들지않았다. 앱schemaowner/운영자까지막는통제라고주장하지않는다. expires된저장proof로새실행권한이생기지않으며후속실행은신선한인증/현재권한이필요하다.

D-18 확인: 외부test URL없음·Docker엔진/고정이미지이미존재. PostgresTestDatabase의운영동일digest/사전적재설정으로새Testcontainers DB만사용했다. 신규DB6/skipped0·Java21/runtime분리build 통과: 암호원본decrypt/새fence재연결시원본보존·동시중복1·code충돌/낮은fence신규거절·receipt실패주입시watermark까지rollback·만료/타verifier쓰기0·DB원본수정/삭제42501. plainPG대체/운영DB시험0. 테스트컨테이너a768320bb09d51c8194b31e95168e2c9845a4bfa1cd591ce60ff9741225ed6d5는시험종료후목록0(정리확인). 증거는C:/ai/VALUNIVO-test-runtime/autostock-control-test-67a1496698c8430ab599418f6453a9a4/app/test-results/test/TEST-com.autostock.monitor.control.StockControlReceiptStoreDbTest.xml.

기존poller/복구dirty파일보존·신규파일만커밋한다. 운영migration/DB/봇/offset/서비스/킬스위치/거래0. 다음 기존receipt를재실행허가로보지않는최초실행/unknown·멱등ACK 기반. 기존60초일회용resume규칙과실제주문정책을플랫폼이대신하지않는다.
