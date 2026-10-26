# 앱 저장 terminal 결과 proof 기반

2026-10-26. StockControlResultIssuer와결과v1 schema를추가했다. 명시store/verifier/Ed25519 메모리privatekey/keyID/clock만받고Bean/keyloader/endpoint/worker0. 신선caller proof로DB의실제불변terminal결과만조회해 sourceupdate/attempt/원본request SHA/outcome/result SHA/canonical result본문·고정scope를statement에결합한다. running/unknown/결과없음은발급하지않는다. 새fence호출이어도저장된원본request fingerprint를서명하며현재caller지문으로갈아끼우지않는다.

purpose autostock-telegram-control-result-proof-v1/keyID/issued/expires/원본statement base64를길이프레이밍 Ed25519로서명한다. 기한min(발급+30초,신선callerproof기한)·발급전후시간재검사. 최대32KiB envelope/24KiB statement·본문16KiB범위. base64는암호화가아니며reply/code/hash/proof는private전달데이터로취급한다. 조회된DB결과를증명하는것이며실제거래/킬스위치효과를별도로증명하지않는다. 원래결과는내부trusted 합성보고로시험했고실제실행없다.

D18고정Timescale/newTestcontainers·Java21/runtime분리build 신규DB3/skipped0: succeeded/failed 실제암호DB결과발급·원본지문/시도/해시결합, 결과없음/running/unknown 거절, 새fence 과거원본결합·caller기한불연장/만료거절. 별도플랫폼JVM의pinned검증3/skipped0·실제성공/실패 2envelope/statement/content schema동일사본대조통과. 공개합성키/proof근거 C:/ai/VALUNIVO-test-runtime/stock-result-proof-fb0b6996a2f6471b9fc18968723c86dc,privatekey디스크0. 시험컨테이너61d21d6b184fa95a1a14ba797ebb17635eb58e41da25f1ffddef57fa342e6200 제거확인. Gradle에합성export경로input만추가해fixture경로변경을검증캐시가무시하지않게했다.

운영DB/migration/봇/offset/서비스/실제제어0·기존dirty보존. 다음platform durable 결과접수와독립purpose 결과ACK·앱ACK기록. 일반request/실행proof를ACK권한으로재사용하지않는다. 실제currentlive/최종업무검사·60초일회용해제/운영수신자이관은잔여다.
