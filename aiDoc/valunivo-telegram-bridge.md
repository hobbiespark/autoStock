# VALUNIVO Telegram 전달기 — 2026-10-24

기존 TelegramCommandPoller 하나를 유지하고 IGNORED 분기에 opt-in PlatformTelegramBridge를 연결했다. /stop·/resume·/status와 킬스위치 로직은 그대로다. 자연어와 /nivo 계열만 허용 개인 채팅/발신자 검사 후 암호화 spool에 저장한다. 별도 worker가 loopback 플랫폼에 전달하고, 회신을 같은 봇으로 보내 성공 시 ack한다. 금융 설정·전략·계좌/주문 API를 변경하지 않는다.

.env 설정은 VALUNIVO_BRIDGE_ENABLED, VALUNIVO_BRIDGE_TOKEN_PATH, VALUNIVO_BRIDGE_SPOOL_PATH다. *_PATH를 쓴 이유는 기존 .env 로더가 *_FILE을 비밀값 참조로 처리하기 때문이다. 공개 예시는 .env.example에 기본false로 추가했다. 실제 설정/키는 Git 밖에 있다.

플랫폼 장애와 reply 실패는 별도 작업자에서 재시도한다. Bot API 발송 성공 뒤 플랫폼 ack가 유실되면 중복 회신이 가능하다. 디스크 접수 실패에는 미실행 안내를 보낼 수 있지만 프로세스 재시작과 디스크 장애를 함께 견디는 무손실 보장은 아니다. 토큰/키를 회전하기 전에 spool을 비워야 한다. 금융 위험 제어의 영속성은 이 전달기로 대체하지 않는다.

2026-10-24 재부팅 후 실제 개인 봇 /nivo_help 회신을 사용자가 확인했다. bridge의 mock/보안·장애 시험과 기존 Telegram 회귀41건 통과. 운영 /resume는 시험하지 않았다. 전달기의 통합 테스트는 실제 getUpdates 소비자를 새로 만들지 않는다.

전체 사용자 안내는 <작업루트>/VALUNIVO/aiDoc/guides/telegram-common-bot.md, 구현 기록은 같은 aiDoc/implementation/11-telegram-and-approvals.md다.
