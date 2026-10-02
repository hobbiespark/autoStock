package com.autostock.market;

import com.autostock.common.event.MarketDataStale;
import com.autostock.common.util.StockNames;
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
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * 대응 4단계:
 * <ol>
 *   <li>watchdog이 10초마다 연결 상태 점검</li>
 *   <li>끊겼으면 자동 재연결</li>
 *   <li>재연결 직후 기존 구독 종목 전체 재등록 — 이걸 빼먹으면
 *       "연결은 됐는데 시세는 안 오는" 더 찾기 어려운 상태가 된다</li>
 *   <li><b>장시간 단절이면 킬스위치까지 연동</b>: 자동 재연결로도 해소되지 않고 연속 단절이
 *       {@code autostock.ws.stale-after}(기본 180초)를 넘으면 {@link MarketDataStale} 이벤트를
 *       발행한다. risk 모듈의 리스너가 이걸 구독해 킬스위치를 켠다 — "끊긴 걸 알고 있지만
 *       그래도 계속 매매하는" 사고까지 막는다(risk.MarketDataStaleListener 참고). 이 모듈은
 *       risk의 존재를 몰라도 되도록 이벤트만 던진다(모듈 경계 원칙).</li>
 * </ol>
 *
 * <p>autostock.ws.enabled=true일 때만 동작 (앱키 필요).
 *
 * <p><b>장외 대기 (2026-09-29)</b>: {@link MarketSessionService}가 STANDBY(기본 16:00~익거래일
 * 08:30, 주말·휴장일 종일)이면 연결을 스스로 닫고 재연결·단절 감시를 모두 쉰다. 장외에는 받을
 * 시세가 없는데도 연결을 유지하면 ① 야간·주말 서버 점검 때 재연결 실패 로그가 쌓이고
 * ② 단절이 180초를 넘기면 {@link MarketDataStale} → 킬스위치가 켜져 다음 날 아침 사람이
 * 수동 해제해야 하는 상태로 장을 맞게 된다. 대기 중에는 단절 시계 자체를 멈춰 이 경로를 끊고,
 * ACTIVE로 돌아오는 첫 watchdog 틱에서 곧바로 재연결한다(LOGIN 성공 시 기존 재구독 경로가
 * 구독 종목·체결통보를 일괄 복구).
 *
 * <p><b>실측 확정 (2026-09-10, mockapi wss 포트 10000 — docs/measured/ws_probe_20260910_offhours.txt)</b>:
 * <ul>
 *   <li>REG는 <b>LOGIN 응답(return_code=0) 수신 후에만</b> 유효 — 인증 완료 전에 보낸 REG는
 *       {@code return_code:100013 "로그인 인증이 들어오기 전에 다른 전문이 들어왔습니다.
 *       해당 전문은 무시됩니다"}로 조용히 버려진다. 그래서 이 클래스는 연결 직후가 아니라
 *       LOGIN 응답을 받은 시점에 재구독을 수행한다({@link #handleTextMessage}).</li>
 *   <li>LOGIN 응답 포맷: {@code {"trnm":"LOGIN","return_code":0,"return_msg":"","sor_yn":"Y"}}</li>
 *   <li>서버가 약 10초 간격으로 {@code {"trnm":"PING"}} 전송 — 동일 전문 에코 필요.</li>
 * </ul>
 * TODO 실측(잔여): REAL 시세의 FID 매핑(장중 재실행 필요), 체결통보(type 00) item 빈 배열 동작.
 */
@Component
public class KiwoomWebSocketClient extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(KiwoomWebSocketClient.class);

    private final KiwoomProperties kiwoomProperties;
    private final TokenManager tokenManager;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final Clock clock;
    private final MarketSessionService marketSession;

    /** 장외 대기 중인지 — 대기 진입/해제 로그를 전환 시 한 번만 남기기 위한 플래그. */
    private final AtomicBoolean standby = new AtomicBoolean(false);

    private final Set<String> subscribedSymbols = ConcurrentHashMap.newKeySet();
    private final AtomicReference<WebSocketSession> session = new AtomicReference<>();

    /** 장시간 단절 감지(→ MarketDataStale). B3에서 추출 — {@link DisconnectionTracker}. */
    private final DisconnectionTracker disconnection;

    /**
     * LOGIN 응답(return_code=0)을 받았는지 — 실측(클래스 Javadoc)에 따라 인증 전 REG는
     * 서버가 무시하므로, 이 플래그가 서기 전에는 어떤 REG도 보내지 않는다.
     */
    private final AtomicBoolean loggedIn = new AtomicBoolean(false);

    /** 마지막 LOGIN에 실은 토큰 — 로그인이 거부되면 이 토큰을 폐기해 재연결이 새 토큰을 쓰게 한다. */
    private final AtomicReference<String> loginToken = new AtomicReference<>();

    /**
     * connect() 진행 중(연결 시도가 아직 완료되지 않음)을 나타내는 가드 — 실측(2026-09-11 운영 로그):
     * start()(ApplicationReadyEvent)의 connect()와 watchdog의 첫 틱이 거의 동시에 실행되면,
     * 세션이 아직 열리지 않은 짧은 틈을 watchdog이 "단절"로 오판해 두 번째 connect()를 만들고,
     * 그 결과 tokenManager.accessToken()이 동시에 두 번 불려 토큰이 중복 발급(429)됐다.
     * connect() 시작 시 CAS로 true로 세팅하고, 연결 성공/실패가 확정되는 시점(whenComplete)에
     * false로 되돌린다 — 그 사이 watchdog은 재연결 시도를 건너뛴다.
     */
    private final AtomicBoolean connecting = new AtomicBoolean(false);

    /** 재연결 지수 백오프·로그 억제(운영 2일차 결함). B3에서 추출 — {@link ReconnectBackoff}. */
    private final ReconnectBackoff backoff;

    /** 마지막으로 받은 메시지(PING 포함) 시각 — 헬스 kiwoomWs·게이지 market.ws.last_message_age.seconds(실행 계획 1.7). */
    private final AtomicReference<Instant> lastMessageAt = new AtomicReference<>();

    /**
     * 연결(TCP·TLS·업그레이드) 단계 IO 상한 — Tomcat WebSocket 클라이언트 속성. 기본값(5초)과 같지만 명시한다:
     * 이 상한이 없으면 연결 future가 끝나지 않아 {@code connecting}이 true로 남고 재연결이 영구히 멈춘다.
     */
    static final Map<String, Object> CONNECT_PROPERTIES =
            Map.of("org.apache.tomcat.websocket.IO_TIMEOUT_MS", String.valueOf(Duration.ofSeconds(5).toMillis()));

    public KiwoomWebSocketClient(KiwoomProperties kiwoomProperties,
                                 TokenManager tokenManager,
                                 ApplicationEventPublisher publisher,
                                 ObjectMapper objectMapper,
                                 @Value("${autostock.ws.enabled:false}") boolean enabled,
                                 Clock clock,
                                 @Value("${autostock.ws.stale-after:180}") long staleAfterSeconds,
                                 MarketSessionService marketSession) {
        this.kiwoomProperties = kiwoomProperties;
        this.tokenManager = tokenManager;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.clock = clock;
        this.disconnection = new DisconnectionTracker(clock, staleAfterSeconds, publisher);
        this.backoff = new ReconnectBackoff(clock);
        this.marketSession = marketSession;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) {
            log.info("WS 비활성 (autostock.ws.enabled=false) — 앱키 발급 후 활성화");
            return;
        }
        if (!marketSession.isActive()) {
            // 장외 기동 — 연결하지 않는다. ACTIVE가 되는 첫 watchdog 틱에서 연결된다.
            standby.set(true);
            log.info("WS 장외 대기 — 연결은 다음 장 대응 시각({} KST)에", marketSession.wakeTime());
            return;
        }
        connect();
    }

    public void subscribe(String symbol) {
        subscribedSymbols.add(symbol);
        WebSocketSession current = session.get();
        // loggedIn 전에는 보내지 않는다 — 실측: 인증 전 REG는 100013으로 무시됨.
        // 이 시점에 못 보낸 구독은 LOGIN 응답 처리(handleTextMessage)가 일괄 재구독한다.
        if (current != null && current.isOpen() && loggedIn.get()) {
            sendRegister(current, symbol);
        }
    }

    /**
     * 단절 감시: 10초 주기. 끊긴 줄 모르고 매매 정지되는 사고 방지.
     *
     * <p>initialDelay=30_000: 기동 직후 start()의 첫 connect()가 세션을 열 시간을 준다
     * (실측 2026-09-11: initialDelay가 없으면 앱 기동과 거의 동시에 첫 watchdog 틱이 돌아
     * 아직 세션이 열리지 않은 상태를 "단절"로 보고 중복 connect()를 유발했다).
     */
    @Scheduled(fixedDelay = 10_000, initialDelay = 30_000)
    public void watchdog() {
        if (!enabled) {
            return;
        }
        if (!marketSession.isActive()) {
            enterStandby();
            return;
        }
        boolean wokeUp = standby.compareAndSet(true, false);
        if (wokeUp) {
            log.info("WS 장외 대기 해제 — 연결 시작");
        }
        WebSocketSession current = session.get();
        boolean connected = current != null && current.isOpen();
        trackDisconnection(connected);
        if (!connected) {
            if (connecting.get()) {
                // 이미 connect() 진행 중(예: start()의 최초 연결 시도) — 여기서 또 시작하면
                // 각자 tokenManager.accessToken()을 불러 토큰이 중복 발급된다(실측 원인). 건너뛴다.
                log.debug("WS 연결 시도 진행 중 — watchdog 재연결 스킵");
                return;
            }
            if (!backoff.readyToAttempt()) {
                // 지수 백오프 대기 중 — 서버가 닫힌 구간(주말 점검 등)에서 10초마다 두드리지 않는다.
                log.debug("WS 재연결 백오프 대기 중 (다음 시도 {})", backoff.nextAttemptAt());
                return;
            }
            if (!wokeUp) {
                // 대기 해제 직후의 첫 연결은 예정된 동작이다 — 단절 경고를 남기지 않는다(2026-09-30 로그 소음).
                log.warn("WS 단절 감지 — 재연결 시도");
            }
            connect();
        }
    }

    /**
     * 장외 대기 처리 — 연결이 열려 있으면 정상 종료하고, 단절 시계·백오프를 초기화한다.
     *
     * <p>매 틱 호출돼도 안전하다(멱등): 연결이 이미 닫혀 있으면 아무것도 하지 않는다. 대기 진입
     * 직전에 시작된 connect()가 대기 중에 완료돼 세션이 열리더라도 다음 틱에서 닫힌다.
     * 단절 시계를 비우는 이유는 클래스 설명 "장외 대기" 참고 — 대기 시간을 단절로 세면
     * 깨어나는 순간 180초를 넘긴 것으로 판정돼 킬스위치가 켜진다.
     */
    private void enterStandby() {
        if (standby.compareAndSet(false, true)) {
            log.info("WS 장외 대기 진입 — 연결 해제, 다음 연결 {} KST", marketSession.wakeTime());
        }
        disconnection.reset();
        backoff.reset();

        WebSocketSession current = session.get();
        if (current != null && current.isOpen()) {
            loggedIn.set(false);
            try {
                current.close(CloseStatus.NORMAL);
            } catch (Exception e) {
                log.warn("WS 대기 진입 중 연결 종료 실패 — 다음 틱에 재시도: {}", e.getMessage());
            }
        }
    }

    /** 장외 대기 중인가 — 테스트 확인용(패키지 접근). */
    boolean isStandby() {
        return standby.get();
    }

    // ── 관측(실행 계획 1.7) — 헬스·메트릭이 읽는다. 상태를 바꾸지 않는다 ───────────────────────

    /** WS 사용 여부({@code autostock.ws.enabled}). */
    public boolean isEnabled() {
        return enabled;
    }

    /** 세션이 열려 있는가. */
    public boolean isConnected() {
        WebSocketSession current = session.get();
        return current != null && current.isOpen();
    }

    /** LOGIN 응답(성공)을 받았는가 — 이 전에는 시세·체결통보 구독이 유효하지 않다. */
    public boolean isLoggedIn() {
        return loggedIn.get();
    }

    /** 마지막으로 받은 메시지(PING 포함) 시각. 받은 적 없으면 빈 값. */
    public Optional<Instant> lastMessageAt() {
        return Optional.ofNullable(lastMessageAt.get());
    }

    /**
     * 이번 틱의 연결 상태를 단절 감지에 반영한다({@link DisconnectionTracker}). 실제 재연결({@link #connect()}, 네트워크
     * 부작용)과 분리해 단위 테스트할 수 있게 둔 패키지 접근 메서드다.
     *
     * @param connected 이번 watchdog 틱에서 관측한 연결 상태(true=정상)
     */
    void trackDisconnection(boolean connected) {
        disconnection.observe(connected);
    }

    private void connect() {
        if (!connecting.compareAndSet(false, true)) {
            // 이미 진행 중인 connect() 호출이 있다 — start()와 watchdog이 거의 동시에 들어와도
            // 실제 연결 시도(및 tokenManager.accessToken() 호출)는 한 번만 일어나게 한다.
            log.debug("WS 연결 시도 이미 진행 중 — 건너뜀");
            return;
        }
        try {
            StandardWebSocketClient client = new StandardWebSocketClient();
            client.setUserProperties(CONNECT_PROPERTIES);
            client.execute(this, null, URI.create(kiwoomProperties.wsUrl()))
                    .whenComplete((s, ex) -> {
                        connecting.set(false);
                        if (ex != null) {
                            onConnectFailed(ex);
                        }
                    });
        } catch (Exception e) {
            connecting.set(false);
            onConnectFailed(e);
        }
    }

    /** 연결 실패 처리 — 백오프를 늘리고, 처음 몇 번만 스택트레이스를 남긴다({@link ReconnectBackoff}). */
    private void onConnectFailed(Throwable ex) {
        backoff.recordFailure();
        int failures = backoff.consecutiveFailures();
        long delay = backoff.currentDelaySeconds();
        if (backoff.logStackTrace()) {
            log.error("WS 연결 실패({}회 연속) — {}초 후 재시도", failures, delay, ex);
        } else {
            String cause = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
            log.warn("WS 연결 실패({}회 연속) — {}초 후 재시도: {}", failures, delay, cause);
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession newSession) throws Exception {
        session.set(newSession);
        // 연결 성공 — 백오프 초기화(다음 단절은 다시 10초부터 시작).
        backoff.reset();
        // 재연결 성공 — 단절 구간 종료. watchdog의 다음 틱을 기다리지 않고 즉시 리셋한다
        // ("재연결 성공 시 리셋" 스펙 — watchdog에서도 connected=true면 리셋하므로 이중 방어).
        disconnection.reset();
        // 새 연결은 미인증 상태에서 시작 — LOGIN만 보내고 REG는 LOGIN 응답 후로 미룬다
        // (실측: 인증 전 REG는 100013으로 무시됨, 클래스 Javadoc).
        loggedIn.set(false);
        String token = tokenManager.accessToken();
        loginToken.set(token);
        newSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
                Map.of("trnm", "LOGIN", "token", token))));
        log.info("WS 연결 — LOGIN 전송, 응답 대기 (구독 {}종목은 로그인 후 등록)", subscribedSymbols.size());
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
            log.error("구독 등록 실패: {}", StockNames.label(symbol), e);
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
        lastMessageAt.set(clock.instant());
        JsonNode root = objectMapper.readTree(message.getPayload());
        String trnm = root.path("trnm").asText();
        if ("PING".equals(trnm)) {
            current.sendMessage(message); // PING은 그대로 응답 (실측: 서버가 약 10초 간격 전송)
            return;
        }
        if ("LOGIN".equals(trnm)) {
            int returnCode = root.path("return_code").asInt(-1);
            if (returnCode == 0) {
                loggedIn.set(true);
                // 인증 완료 — 이제부터 REG가 유효하다. 구독 종목 전체 + 체결통보를 일괄 등록
                // (신규 연결·재연결 모두 이 경로 하나로 처리 = 등록 누락 방지).
                subscribedSymbols.forEach(symbol -> sendRegister(current, symbol));
                registerOrderNotice(current);
                log.info("WS 로그인 성공(sor_yn={}) — 재구독 {}종목 + 체결통보 등록",
                        root.path("sor_yn").asText(), subscribedSymbols.size());
            } else {
                // 인증 실패 — 이 연결로는 어떤 REG도 유효하지 않다. 토큰을 폐기하고 닫아서 watchdog이
                // 새 토큰으로 재연결하게 한다. 폐기하지 않으면 캐시상 유효한 같은 토큰으로 계속 재로그인해
                // 실패가 반복된다(2026-09-30 REST 8005 실측과 같은 경로). 폐기 빈도는 TokenManager가 제한한다.
                log.error("WS 로그인 실패 return_code={} msg={} — 토큰 폐기 후 연결을 닫고 재연결에 맡긴다",
                        returnCode, root.path("return_msg").asText());
                tokenManager.invalidate(loginToken.get());
                current.close();
            }
            return;
        }
        if ("REG".equals(trnm)) {
            int returnCode = root.path("return_code").asInt(-1);
            if (returnCode != 0) {
                // 실측 예: 100013 = 인증 전 REG(무시됨). 0이 아니면 그 구독은 살아있지 않다.
                log.warn("WS REG 거절 return_code={} msg={}", returnCode, root.path("return_msg").asText());
            }
            return;
        }
        if ("REAL".equals(trnm)) {
            for (JsonNode data : root.path("data")) {
                // type에 따라 MarketTick(0B) 또는 OrderNotice(00)로 변환됨. 그 외 타입은 null.
                Object event = RealMessageParser.parse(data, clock);
                if (event != null) {
                    publisher.publishEvent(event);
                }
            }
        }
    }
}
