package com.autostock.monitor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * VALUNIVO 3-4 C4(D338, 기획 12 6장): 앱 봇 명령 메뉴를 명령 원천({@link AppCommandCatalog})으로 맞춘다. 기동이 끝나면 한 번
 * {@code setMyCommands}를 부른다(메뉴 전체를 바꾼다). 실패해도 기동·폴링은 그대로다 — 메뉴는 편의이고 명령 처리는 폴러가 한다.
 * {@code monitor.telegram.enabled=true}이고 {@code monitor.telegram.menu.enabled}가 false가 아닐 때만 생긴다. 토큰은 로그에 남기지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "monitor.telegram", name = {"enabled", "menu.enabled"}, havingValue = "true", matchIfMissing = false)
public class TelegramBotMenu {
    private static final Logger log = LoggerFactory.getLogger(TelegramBotMenu.class);

    private final WebClient webClient;
    private final TelegramProperties properties;
    private final AppCommandCatalog catalog;

    public TelegramBotMenu(WebClient.Builder builder, TelegramProperties properties, AppCommandCatalog catalog) {
        this.webClient = builder.baseUrl("https://api.telegram.org").build();
        this.properties = Objects.requireNonNull(properties);
        this.catalog = Objects.requireNonNull(catalog);
    }

    /** setMyCommands 본문. 설명은 텔레그램 한도(256자) 안이다(이름 ≤40자). */
    static Map<String, Object> body(AppCommandCatalog catalog) {
        List<Map<String, String>> commands = new ArrayList<>();
        for (var entry : catalog.botMenu()) commands.add(Map.of("command", entry.getKey(), "description", entry.getValue()));
        return Map.of("commands", commands);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        if (!properties.enabled() || properties.botToken() == null || properties.botToken().isBlank()) return;
        try {
            Map<?, ?> response = webClient.post()
                    .uri(uri -> uri.path("/bot{token}/setMyCommands").build(properties.botToken()))
                    .bodyValue(body(catalog))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(10));
            boolean ok = response != null && Boolean.TRUE.equals(response.get("ok"));
            if (ok) log.info("텔레그램 봇 명령 메뉴 등록: {}개(명령 원천)", catalog.botMenu().size());
            else log.warn("텔레그램 봇 명령 메뉴 등록 응답이 ok가 아님 — 메뉴만 옛 상태, 명령 처리는 그대로");
        } catch (RuntimeException e) {
            log.warn("텔레그램 봇 명령 메뉴 등록 실패 — 메뉴만 옛 상태, 명령 처리는 그대로: {}", e.getClass().getSimpleName());
        }
    }
}
