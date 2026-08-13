package com.autostock.marketdata;

import com.autostock.common.event.MarketTick;
import com.autostock.kiwoom.KiwoomProperties;
import com.autostock.kiwoom.TokenManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 키움 실시간 WebSocket 클라이언트.
 * 사고 대응(PLAN 3절): 단절 감지(스케줄 감시) → 자동 재연결 + 등록 종목 재구독.
 * autostock.ws.enabled=true 일 때만 동작 (앱키 필요).
 * TODO Phase 2 검증: LOGIN/REG 메시지 포맷·응답 코드 모의투자 실측 확인.
 */
@Component
public class KiwoomWebSocketClient extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(KiwoomWebSocketClient.class);

    private final KiwoomProperties kiwoomProperties;
    private final TokenManager tokenManager;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    private final Set<String> subscribedSymbols = ConcurrentHashMap.newKeySet();
    private final AtomicReference<WebSocketSession> session = new AtomicReference<>();

    public KiwoomWebSocketClient(KiwoomProperties kiwoomProperties,
                                 TokenManager tokenManager,
                                 ApplicationEventPublisher publisher,
                                 ObjectMapper objectMapper,
                                 @Value("${autostock.ws.enabled:false}") boolean enabled) {
        this.kiwoomProperties = kiwoomProperties;
        this.tokenManager = tokenManager;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) {
            log.info("WS 비활성 (autostock.ws.enabled=false) — 앱키 발급 후 활성화");
            return;
        }
        connect();
    }

    public void subscribe(String symbol) {
        subscribedSymbols.add(symbol);
        WebSocketSession current = session.get();
        if (current != null && current.isOpen()) {
            sendRegister(current, symbol);
        }
    }

    /** 단절 감시: 10초 주기. 끊긴 줄 모르고 매매 정지되는 사고 방지. */
    @Scheduled(fixedDelay = 10_000)
    public void watchdog() {
        if (!enabled) {
            return;
        }
        WebSocketSession current = session.get();
        if (current == null || !current.isOpen()) {
            log.warn("WS 단절 감지 — 재연결 시도");
            connect();
        }
    }

    private void connect() {
        try {
            StandardWebSocketClient client = new StandardWebSocketClient();
            client.execute(this, null, URI.create(kiwoomProperties.wsUrl()))
                    .whenComplete((s, ex) -> {
                        if (ex != null) {
                            log.error("WS 연결 실패", ex);
                        }
                    });
        } catch (Exception e) {
            log.error("WS 연결 예외", e);
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession newSession) throws Exception {
        session.set(newSession);
        // 로그인
        newSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
                Map.of("trnm", "LOGIN", "token", tokenManager.accessToken()))));
        // 재구독 (재연결 시 등록 누락 방지)
        subscribedSymbols.forEach(symbol -> sendRegister(newSession, symbol));
        log.info("WS 연결 완료, 재구독 {}종목", subscribedSymbols.size());
    }

    private void sendRegister(WebSocketSession target, String symbol) {
        try {
            target.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of(
                    "trnm", "REG",
                    "grp_no", "1",
                    "refresh", "1",
                    "data", new Object[]{Map.of(
                            "item", new String[]{symbol},
                            "type", new String[]{"0B"}   // 주식체결
                    )}))));
        } catch (Exception e) {
            log.error("구독 등록 실패: {}", symbol, e);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession current, TextMessage message) throws Exception {
        JsonNode root = objectMapper.readTree(message.getPayload());
        String trnm = root.path("trnm").asText();
        if ("PING".equals(trnm)) {
            current.sendMessage(message); // PING은 그대로 응답
            return;
        }
        if ("REAL".equals(trnm)) {
            for (JsonNode data : root.path("data")) {
                publishTick(data);
            }
        }
    }

    private void publishTick(JsonNode data) {
        // TODO Phase 2 검증: 실시간 필드 번호(10=현재가, 15=거래량 등) 실측 확인
        String symbol = data.path("item").asText();
        String priceRaw = data.path("values").path("10").asText("");
        if (symbol.isEmpty() || priceRaw.isEmpty()) {
            return;
        }
        BigDecimal price = new BigDecimal(priceRaw.replace("+", "").replace("-", ""));
        long volume = data.path("values").path("15").asLong(0);
        publisher.publishEvent(new MarketTick(symbol, price, volume, Instant.now(), MarketTick.Source.LIVE));
    }
}
