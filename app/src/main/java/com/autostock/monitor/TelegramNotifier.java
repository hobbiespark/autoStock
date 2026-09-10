package com.autostock.monitor;

import com.autostock.common.util.SecretMasking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/**
 * 텔레그램 Bot API로 알림을 보내는 어댑터 — Notifier Port의 실제 구현체.
 *
 * <p>{@code monitor.telegram.enabled=true}일 때만 빈으로 등록된다(기본 false).
 * 자택망에서 Bot API 실측 검증 전까지는 {@link LogOnlyNotifier}가 대신 등록된다.
 *
 * <p><b>실패 처리 원칙</b>: sendMessage 호출이 실패해도(네트워크 오류, 봇 차단, 잘못된
 * chat_id 등) 예외를 삼키고 error 로그만 남긴다 — 알림 발송 실패가 매매 흐름(리스너 체인)을
 * 막으면 절대 안 되기 때문이다. 알림은 부가 기능이지 매매의 전제조건이 아니다.
 */
@Component
@ConditionalOnProperty(prefix = "monitor.telegram", name = "enabled", havingValue = "true")
public class TelegramNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);

    private final WebClient webClient;
    private final TelegramProperties properties;

    public TelegramNotifier(WebClient.Builder builder, TelegramProperties properties) {
        // 봇 토큰이 URL 경로 자체에 들어가는 텔레그램 Bot API 특성상 baseUrl만 고정하고
        // 토큰은 매 호출 시 경로 변수로 채운다(TokenManager처럼 헤더에 싣지 않음).
        this.webClient = builder.baseUrl("https://api.telegram.org").build();
        this.properties = properties;
    }

    @Override
    public void notify(NoticeLevel level, String message) {
        try {
            String text = "[%s] %s".formatted(level, message);
            // uri(String) 단일 인자 오버로드를 쓴다(가변인자 오버로드 대신) — 토큰을 담은
            // 경로를 미리 조립해서 넘기면 테스트에서 WebClient 체인을 스텁하기 쉬워진다.
            webClient.post()
                    .uri("/bot%s/sendMessage".formatted(properties.botToken()))
                    .bodyValue(Map.of(
                            "chat_id", properties.chatId(),
                            "text", text))
                    .retrieve()
                    .toBodilessEntity()
                    // TODO 실측(자택망): getUpdates와 마찬가지로 sendMessage 응답 포맷/오류 코드도
                    //  문서 기반 추정이다. 실제 봇으로 성공/실패 케이스를 확인해야 한다.
                    .block();
        } catch (Exception e) {
            // 알림 실패가 매매를 막으면 안 된다 — 여기서 끝낸다(재던지지 않음).
            // 예외 메시지에 봇 토큰이 담긴 전체 URL(/bot{token}/sendMessage)이 그대로 들어있을
            // 수 있어(WebClientResponseException 등) 마스킹 후 로그로 남긴다 — SecretMasking 참고.
            log.error("텔레그램 알림 발송 실패 — level={} message={}", level, message,
                    SecretMasking.sanitizeForLogging(e));
        }
    }
}
