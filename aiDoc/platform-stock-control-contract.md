# 플랫폼 주식 제어 데이터 계약 기반

2026-10-26. monitor.control.StockControlRequest/StockControlWire와 독립 schema를 추가했다. VALUNIVO/platform-core 의존성 없이 autostock/v1 네 명령·정확 decimal update/fence·confirm에만4자리/null·framed지문·strict UTF8/JSON4KiB/9필드를 검사한다. unsigned decode 결과는 인증·권한·현재수신권·실제주체 증명이 아니며 킬스위치/주문/HTTP로 이어지는 메서드는 없다. Bean/키로딩/endpoint/poller/DB migration 변경0, 기존60초 일회용해제확인·/stop=KillSwitch 의미 유지.

이번 단위5+실제플랫폼 Java fixture4종 소비교차1=6/skipped0 통과. 합성 fixture의2^53초과 update ID/최대long fence와 code correlation/변조·중복키·타입·size/지문결합을 확인했다. 독립앱에서만 decode/reencode/재decode했고 실제네트워크·DB·거래 호출0. 앱schema와플랫폼schema 동일 사본을 사용한다.

첫 기본 toolchain시험은 JDK21 미탐색으로 실행 전 실패했다. 기존설치JDK21 경로를 명시한두번째는 Java컴파일후 common JAR 잠금으로 시험전중단됐다. 운영잠금파일/프로세스는 건드리지 않고 보호된 runtime의 임시Gradle init으로 buildDirectory를 분리해 세번째 시험을 통과했다. 초기시도는 기존 build/classes/resources를 재컴파일했으나 운영JAR교체/재시작/설정변경은 없었다. 이후 이PC의autoStock 시험은 분리build 경로를 사용한다. 근거: C:/ai/VALUNIVO-test-runtime/autostock-control-test-67a1496698c8430ab599418f6453a9a4. D-18 TimescaleDB 조건을 일반PG로 대체하지 않았다.

기존 poller/bridge·복구스크립트 미커밋변경은 보존했다. 이번새파일만 별도커밋한다. 다음 pinned 신뢰/신선도검증으로만 생성되는 private verified 값과 unsigned 거절 경계→durable 수신/최초실행/unknown/ACK. 운영token/수신권/실제제어 활성화는 하지 않는다.
