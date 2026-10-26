# 운영자 stop 최초 claim·unknown 원장 기반

2026-10-26 source/격리시험 완료. HandoverOperatorAttemptStore/V27은 V26 접수된 stop의 자기 gate·키/instance·원본 지문을 대조하고 UUID 잠금 뒤 최초 claim을 한 번만 발급한다. claim은 private 생성자/동일 store 소유이며 DB에는 토큰 SHA만 보관한다. commit 실패면 claim을 반환하지 않는다. 재접속/중복 요청은 claim을 재발급하지 않는다. claim 기한은 원본 expires 이하로 microsecond 내림이며 매 검증 때 원본 gate 신선도·DB 토큰/unknown·기한을 재검사한다.

명시 실패 기록은 원본 claim만 허용하며 만료 뒤에도 unknown을 남길 수 있다. 신선 observe는 원본 접수의 동일 키/instance를 확인하고 만료 시도를 append-only unknown으로 보수 기록한다. 기한 연장·재claim·자동 resume은 없다. received 원본 행과 claim/unknown 행은 각각 불변이다. observe는 업무 정지를 실행하지 않지만 만료 시도에 unknown 메타데이터를 추가할 수 있다. DB 제약은 FK 상관관계·원본기한 상한·수정/삭제 금지를 검사한다. DB owner의 임의 DDL까지 방어하는 주장은 아니다.

이번 검증: JDK21/runtime layout2, D18 고정 Timescale 새 Testcontainers DB 신규7/실패0/skipped0. 단일 claim·동시 경쟁·재접속 재발급 거절·다른 gate/지문/역할/접수 누락·만료/신선 observe·명시 unknown 멱등·deferred commit 실패 rollback·DB 변경 및 기한 연장 거절을 확인했다. 함께 실행한 SchemaAndTimeZoneDbTest는 기대값이26으로 남아 첫 실행1건 실패(기대값 수정 스크립트 문자열 불일치). 실제 V27 적용 확인 뒤 기대값27로 정확히 수정하여 해당 클래스만 재실행: 3/실패0/skipped0. 신규7을 반복 합산하지 않았다. 종료 후 최종 시험 컨테이너 제거 확인. 기존 전체1092 회귀/운영DB 시험0.

한계: claim은 실제 명령을 실행하지 않는다. requireCurrent는 실행 횟수를 소비하는 메서드가 아니며, claim 소유자가 같은 값을 여러 번 검사할 수 있다. poller 단일 외부효과/결과 상관관계·성공 기록·동시 다른 요청의 active stop 충돌·listener/CLI는 후속이다. 성공 저장 경로가 없으므로 지금 단계의 미완료 claim은 기한 뒤 unknown이 된다. 이를 실제 정지 성공이나 exactly-once 효과로 표시하지 않는다. Bean/키파일/실제 poller/봇/offset/운영 설정·서비스·거래제어0, 기존 infra/복구 dirty 보존, 54/118 재시도0·M6/D80 유지.

사용자 확정 경로는 별도 loopback listener + HMAC·요청 원장이다. 다음147: poller lock 뒤 기한 검사·request와 stop 원본 상관관계·한 번의 호출 및 결과 유실 경계를 source로 연결하고 합성 poller 시험한다. 실제 운영 키/활성/이관은 별도이다.
