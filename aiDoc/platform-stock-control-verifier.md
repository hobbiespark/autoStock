# 플랫폼 주식제어 pinned proof 검증 기반

2026-10-26. 명시 구성자 StockControlVerifier를 추가했다. 고정Ed25519 공개키/key ID·bot/space/actor·clock만 메모리로 받는다. Bean/키파일/기동구성/HTTP/DB/실제제어0. key ID/시각/원본request base64를 purpose domain과 길이프레이밍으로 결합한 Ed25519 proof를 검사한다. wrapper는6필드 정확집합·엄격UTF8/8KiB/깊이2/중복·후행금지/정규base64·서명64B·원문4KiB 검사다. issued/expires는 canonical Instant·유효기간30초이하/미래발급거절/현재시각<expires를 요구한다. 30초는 전달proof의새내부profile이며 기존 /resume 60초일회용확인을 대체하거나 연장하지 않는다.

서명→독립앱 request codec→고정bot/space/actor→시간 재검사 후 private생성자 Verified를 반환한다. proof snapshot/조회는복사·toString은 code/원문 비노출. trusted 후속구성은 자기 verifier.requireVerified로 인스턴스소유/기한을 다시 검사해야 한다. decode된unsigned StockControlRequest는 Verified가 아니다. 이것만으로 실제Telegram출처/현재receiver권한/최초실행/재전달차단/업무권한이 증명되지는 않는다. 아직 실행·접수메서드가 없다.

이번 신규5+codec기존6=11/skipped0·Java21/runtime분리build 통과. private생성자/소유verifier/defensive copy·unsigned/잘못키·다른purpose·서명된다른space/변조/잘못request·기한경계/미래/초과·wrapper중복/추가/후행/크기·UTF8 거절 확인. 단위시험이 생성한 메모리 키만 사용하고 privatekey디스크/운영키읽기0. 실제 플랫폼 issuer가 발급한 proof 교차는 아직없으며 test helper발급만 했다.

기존poller/bridge/복구dirty보존·분리build runtime만. 다음 플랫폼 순수발급helper/독립앱 actual서명교차 또는 앱 durable접수·replay/fence/unknown·ACK. source만/운영DB/수신자/offset/제어설정0. D-18 DB조건을바꾸지않았다.
