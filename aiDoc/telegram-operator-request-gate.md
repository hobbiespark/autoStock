# 운영자 이관 요청 pure gate 기반

2026-10-26. VALUNIVO143 설계 이후 HandoverOperatorGate source를추가했다. 명시전용keyID/HMAC32바이트키/instance/clock을받으며키파일/Bean/listener/CLI/DB/poller호출이없다. 기존거래API/봇token/owner bearer/M6/결과키재사용0. Request는unsigned데이터이고오직MAC검증이private Verified를만든다. trusted후속컴포넌트는자기gate.requireVerified로소유와기한을다시검사해야한다.

정확POST /receiver-handover/stop 또는 /observe, strictUTF8/JSON4KiB/깊이2/문자열256/중복·후행·추가필드거절. 8필드/contract_version1.0/정규UUID/고정instance·stop의nullable필드모두null, observe의target_request_id필수/expected_active_stop_id null. withdraw/거래resume/임의action을받지않는다. canonical Instant·미래발급거절·최대30초/현재<expires. domain autostock-operator-handover-request-v1/keyID/POST/path/원본body를길이프레이밍해HMAC-SHA256·정규lowerhex64/MessageDigest.isEqual로대조한다. fingerprint는이framing의SHA256이며MAC/key를반환하거나로그하지않는다. 입력snapshot/설정key복사.

이번 JDK21/runtime layout2 분리build 단위7/skipped0: stop/observe/private생성자, method/path/kid/purpose/MAC/body 결합, unsupported/correlation/instance거절, owner/기한재검사·미래/초과/비정규시각, JSON/UUID/UTF8/크기, key/body복사, query/서명/설정오류의fallback없음을검증했다. DB/네트워크/운영키/실제명령0. 재전달은현재이gate만으로는막지않으며30초내동일요청검증은가능하다. durable replay/OS loopback·ACL/실제운영자신원은후속이며인증성공을실제정지성공으로표시하지않는다.

다음 D18 durable operator request ID/원본지문·중복/observe 원장→단일실행/unknown·별도listener/CLI/키절차. 기본비활성운영원칙/실제이관확인·54/118차단은유지한다.
