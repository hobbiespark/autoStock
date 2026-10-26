# 제어 결과 암호화·terminal 원자 저장

2026-10-26. StockControlResultCipher, AttemptStore.complete/result 및 V23을추가했다. source 내부의trusted caller가보고한합성Result(outcome/code/reply)를받는기반이며실제업무효과/정책준수증명이나제어실행메서드가아니다. cipher 없는기존생성자는completion/read를거절한다. 결과reply최대3000자/code64를canonical 순서JSON으로표현하고bot/update/attempt/원본request지문/keyID AAD·AES256GCM랜덤nonce로암호화한다. 공개로그/toString은reply·확인코드를숨긴다. 원문결과hash는private메타데이터로취급한다.

complete는privateClaim/신선동일Verified·원본receipt/현재관측floor·running/시도/token/기한을같은bot잠금안에서확인하고결과insert+terminal(succeeded/failed)을한tx로기록한다. 끝에서기한/proof/floor재검사한다. 이미저장된동일결과는원본복구만/다른결과는충돌·암호문재작성0. 기존결과의기한뒤복구는새실행/기한연장이아니다. result 조회는신선동일intent에만원본을복구하며새fence여도재claim불가. unknown/expired/낮은floor의늦은최초결과는거절한다. markUnknown/recover가terminal을덮어쓰지않는다.

V23은result불변,시도/원본지문일치insert검사,terminal전이에결과필수,DEFERRABLE guard로결과만/terminal없음commit거절을강제한다. schemaowner까지악의적변조를막는주장은아니다. at-rest다른AAD/key/hash/cipher변조는암호검증거절·keyrotation은미구현이다. ACK/서명된앱결과/현재receiver live/최종업무권한·기존60초일회용resume/실제킬스위치는아직미연결이다.

D18 고정Timescale/신규Testcontainers·Java21/runtime분리build: 결과DB7+암호3+기존시도7=17통과후 orphan-result commit부정시험추가로최종결과DB8+암호3=11/skipped0통과(직전시도7은별도기록/반복합산0). 원자완료/재접속·동일재전달/변경충돌·failed/unknown/expired/floor·terminal실패주입rollback·DB무결성/원본수정삭제·fresh새fence복구·orphan commit거절,랜덤암호/canonicalhash·AAD/key/hash/변조·bounds확인. 첫시험은test var복합선언컴파일실패후분리수정했다. 모든결과는synthetic이며실제제어호출0.

운영migration/DB/봇/offset/서비스/거래0·기존dirty보존. 다음ACK는일반request proof를ACK권한으로재사용하지않고, 결과식별/명시적ACK 목적을구분해야한다. 앱terminal결과증명/플랫폼검증 경계→durable ACK를준비하되운영신뢰/키등록/실제polling은연결하지않는다.
