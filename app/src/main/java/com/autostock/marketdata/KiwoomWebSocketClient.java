package com.autostock.marketdata;

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

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 키움 실시간 WebSocket 클라이언트 — 시세를 "받아오는" 게 아니라 "흘러들어오는" 통로.
 *
 * <p>REST와 WS의 역할 분담:
 * <pre>
 *   REST : 내가 물어보면 답해주는 방식 (현재가 조회, 주문 등) — 호출 한도 있음
 *   WS   : 한 번 연결해 두면 서버가 계속 밀어주는 방식 (실시간 체결가) — 장중 시세는 이쪽
 * </pre>
 *
 * <p>메시지 프로토콜 (trnm 필드로 구분):
 * <pre>
 *   → LOGIN {token}          연결 직후 토큰으로 인증
 *   → REG   {item, type}     종목/이벤트 구독 등록 (type 0B = 주식체결, type 00 = 주문체결통보)
 *   ← PING                   서버 생존 확인 — 같은 내용 그대로 되돌려줘야 연결 유지
 *   ← REAL  {data[]}         실시간 데이터 — {@link RealMessageParser}가 type별로
 *                            MarketTick(0B) 또는 OrderNotice(00) 이벤트로 변환한다
 * </pre>
 *
 * <p>type 00(주문체결통보)은 특정 종목이 아니라 "내 계좌"에 걸린 이벤트라서
 * 시세 구독(grp_no 1)과 분리된 그룹(grp_no 2)으로 연결 시 1회 등록한다.
 * (item을 빈 배열로 등록하는 것이 맞는지는 문서상 불명확 — 실측 TODO)
 *
 * <p><b>가장 중요한 설계: 단절 대응.</b> 커뮤니티 사고 사례 1순위가
 * "WS가 끊긴 줄 모르고 시세 없이 매매가 멈춰 있었다"이다. (PLAN 3절)
 * 대응 3단계:
 * <ol>
 *   <li>watchdog이 10초마다 연결 상태 점검</li>
 *   <li>끊겼으면 자동 재연결</li>
 *   <li>재연결 직후 기존 구독 종목 전체 재등록 — 이걸 빼먹으면
 *       "연결은 됐는데 시세는 안 오는" 더 찾기 어려운 상태가 된다</li>
 * </ol>
 *
 * <p>autostock.ws.enabled=true일 때만 동작 (앱키 필요).
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
        // 주문체결통보(계좌 단위)는 종목과 무관하게 별도 그룹으로 1회 등록
        registerOrderNotice(newSession);
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

    /**
     * 주문체결통보(type 00) 등록. 시세와 달리 종목 단위가 아니라 계좌 단위 이벤트라서
     * item을 빈 배열로 두고 grp_no를 시세(1)와 분리된 "2"로 등록한다.
     * TODO Phase 2 실측: item 빈 배열이 "전 종목 통보"로 동작하는지 문서상 불명확 — 확인 필요.
     */
    private void registerOrderNotice(WebSocketSession target) {
        try {
            target.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of(
                    "trnm", "REG",
                    "grp_no", "2",
                    "refresh", "1",
                    "data", new Object[]{Map.of(
                            "item", new String[]{},
                            "type", new String[]{"00"}   // 주문체결통보
                    )}))));
        } catch (Exception e) {
            log.error("주문체결통보 등록 실패", e);
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
                // type에 따라 MarketTick(0B) 또는 OrderNotice(00)로 변환됨. 그 외 타입은 null.
                Object event = RealMessageParser.parse(data);
                if (event != null) {
                    publisher.publishEvent(event);
                }
            }
        }
    }
}
