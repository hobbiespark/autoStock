# 플랫폼 durable 결과 접수 ACK 기록 기반

2026-10-26. `StockControlResultAckVerifier`, `StockControlResultAckStore`, `V24__platform_stock_control_result_ack.sql`, `contracts/telegram-stock-control-result-ack-v1.schema.json`을 추가했다. 명시 생성자만 있고 Bean·키 로더·endpoint·worker·재전송은 없다.

플랫폼(VALUNIVO 138)은 이 앱이 발급한 결과 proof를 검증·암호 저장한 뒤 저장 행에서만 purpose `valunivo-telegram-control-result-ack-v1` ACK를 서명한다. 앱은 pinned 플랫폼 공개키/keyID/bot/space/actor로 검증하고, 다른 purpose(요청 proof, 결과 proof)는 거절한다. statement는 update/attempt/원본 request SHA/outcome/result SHA/플랫폼이 저장한 원본 결과 proof SHA/플랫폼 저장 시각만 담고 응답 본문은 담지 않는다.

기록은 기존 제어와 같은 bot advisory lock에서 자기 결과 행·terminal 시도와 결합이 모두 일치할 때만 한다. 결과 없음·running·unknown·불일치는 거절, 같은 결합의 새 ACK는 최초 기록 반환, 같은 결과의 다른 플랫폼 receipt 값은 충돌. DB는 insert guard와 UPDATE/DELETE 불변 trigger로 같은 규칙을 강제한다.

ACK는 "플랫폼이 결과를 durable 저장했다"는 증거일 뿐이다. Telegram 사용자 전달 성공, 실제 거래/킬스위치 효과, 현재 수신권, 최종 업무 권한을 뜻하지 않으며 제어를 다시 실행하거나 확인 절차를 대체하지 않는다.

시험(D18 Timescale 다이제스트·새 Testcontainers·JDK21·runtime 분리 build): verifier 4(실제 플랫폼 ACK fixture 교차 포함) + DB 5, control 패키지 회귀 포함 48건 실패0·skipped2(기존 131/133 교차 fixture 미지정). 운영 DB migration·키·봇·offset·서비스 변경 0.
