package com.autostock.monitor;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 텔레그램 알림/원격 명령 설정 (prefix: monitor.telegram).
 *
 * <p>botToken/chatId는 소스에 값을 직접 적지 않고 환경변수(TELEGRAM_BOT_TOKEN,
 * TELEGRAM_CHAT_ID)로만 주입한다 — application.yml에는 플레이스홀더만 남긴다.
 *
 * @param enabled  텔레그램 연동 전체 스위치. 기본 false — 자택망(개인 인터넷 회선)에서
 *                 Bot API 왕복을 실측 검증하기 전까지는 회사망(현재 개발 환경)에서
 *                 실제 외부 호출이 나가면 안 되므로 꺼둔다.
 * @param botToken BotFather가 발급한 봇 토큰. "bot{token}/METHOD" 형태로 URL에 들어간다.
 * @param chatId   알림을 받을(그리고 원격 명령을 보낼 수 있는) 대화방 chat_id.
 *                 TelegramCommandPoller가 이 값과 다른 chat_id의 명령은 전부 무시한다
 *                 (보안 — 타인이 봇 토큰을 알아내도 다른 chat_id로는 명령을 실행할 수 없다).
 */
@ConfigurationProperties(prefix = "monitor.telegram")
public record TelegramProperties(
        boolean enabled,
        String botToken,
        String chatId
) {
}
