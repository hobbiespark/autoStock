package com.autostock.market;

import com.autostock.common.event.MarketDataStale;
import com.autostock.kiwoom.KiwoomProperties;
import com.autostock.kiwoom.TokenManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * KiwoomWebSocketClient의 단절 감지 로직(trackDisconnection) 단위테스트.
 *
 * <p>실제 재연결(connect())은 네트워크 부작용이 있어 여기서는 호출하지 않는다 —
 * watchdog() 전체가 아니라 부작용이 없는 trackDisconnection(boolean)만 직접 검증한다
 * (KiwoomWebSocketClient 클래스의 trackDisconnection Javadoc 참고). Clock을 고정·이동시켜
 * "N초가 지났다"를 결정론적으로 검증한다(StaleOrderCancellerTest와 동일 기법).
 */
class KiwoomWebSocketClientTest {

    private static final Instant T0 = Instant.parse("2026-08-13T01:00:00Z");
    private static final long STALE_AFTER = 180L;

    private final List<Object> published = new ArrayList<>();
    private MutableClock clock;
    private KiwoomWebSocketClient client;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        TokenManager tokenManager = mock(TokenManager.class);
        // afterConnectionEstablished가 LOGIN 전문에 토큰을 담는다 — null이면 Map.of가 NPE.
        org.mockito.Mockito.when(tokenManager.accessToken()).thenReturn("test-token");
        client = new KiwoomWebSocketClient(
                mock(KiwoomProperties.class),
                tokenManager,
                published::add,
                new ObjectMapper(),
                true,           // enabled
                clock,
                STALE_AFTER);
    }

    @Test
    void 연결정상이면_이벤트가_발행되지_않는다() {
        client.trackDisconnection(true);
        assertTrue(published.isEmpty());
    }

    @Test
    void 단절이_임계치_미만이면_이벤트가_발행되지_않는다() {
        client.trackDisconnection(false); // 단절 시작
        clock.advance(STALE_AFTER - 1);
        client.trackDisconnection(false); // 아직 임계치 미달

        assertTrue(published.isEmpty());
    }

    @Test
    void 단절이_임계치를_넘으면_MarketDataStale이_한번_발행된다() {
        client.trackDisconnection(false); // 단절 시작(T0)
        clock.advance(STALE_AFTER);
        client.trackDisconnection(false); // 임계치 도달

        assertEquals(1, published.size());
        MarketDataStale event = (MarketDataStale) published.get(0);
        assertEquals(T0, event.disconnectedSince());
        assertEquals(STALE_AFTER, event.seconds());
    }

    @Test
    void 임계치_초과후_계속_단절이어도_중복_발행되지_않는다() {
        client.trackDisconnection(false);
        clock.advance(STALE_AFTER);
        client.trackDisconnection(false); // 1차 발행
        clock.advance(60);
        client.trackDisconnection(false); // 계속 단절 — 추가 발행 없어야 함

        assertEquals(1, published.size());
    }

    @Test
    void 재연결_성공후_다시_단절되면_새로_발행된다() {
        client.trackDisconnection(false);
        clock.advance(STALE_AFTER);
        client.trackDisconnection(false); // 1차 발행

        client.trackDisconnection(true); // 재연결 성공 — 리셋

        clock.advance(STALE_AFTER); // 다시 단절 시작 후 임계치 도달
        client.trackDisconnection(false);
        clock.advance(STALE_AFTER);
        client.trackDisconnection(false); // 2차 발행

        assertEquals(2, published.size());
    }

    // ── LOGIN/REG 순서 (2026-09-10 실측: 인증 전 REG는 100013으로 무시됨) ──────────

    private org.springframework.web.socket.WebSocketSession wsSession(List<String> sent) {
        var s = mock(org.springframework.web.socket.WebSocketSession.class);
        org.mockito.Mockito.when(s.isOpen()).thenReturn(true);
        try {
            org.mockito.Mockito.doAnswer(inv -> {
                sent.add(((org.springframework.web.socket.TextMessage) inv.getArgument(0)).getPayload());
                return null;
            }).when(s).sendMessage(org.mockito.ArgumentMatchers.any());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return s;
    }

    @Test
    void 연결_직후에는_LOGIN만_보내고_REG는_보내지_않는다() throws Exception {
        List<String> sent = new ArrayList<>();
        var s = wsSession(sent);
        client.subscribe("005930"); // 세션 없음 — 등록만 기억
        client.afterConnectionEstablished(s);

        assertEquals(1, sent.size());
        assertTrue(sent.get(0).contains("\"LOGIN\""));
    }

    @Test
    void LOGIN_성공_응답을_받으면_구독종목과_체결통보를_등록한다() throws Exception {
        List<String> sent = new ArrayList<>();
        var s = wsSession(sent);
        client.subscribe("005930");
        client.afterConnectionEstablished(s);
        sent.clear();

        client.handleTextMessage(s, new org.springframework.web.socket.TextMessage(
                "{\"trnm\":\"LOGIN\",\"return_code\":0,\"return_msg\":\"\",\"sor_yn\":\"Y\"}"));

        assertEquals(2, sent.size()); // 시세 REG(005930) + 체결통보 REG(grp_no 2)
        assertTrue(sent.get(0).contains("005930"));
        assertTrue(sent.get(1).contains("\"00\""));
    }

    @Test
    void LOGIN_전_subscribe는_전송하지_않고_LOGIN_후_일괄_등록된다() throws Exception {
        List<String> sent = new ArrayList<>();
        var s = wsSession(sent);
        client.afterConnectionEstablished(s);
        sent.clear();

        client.subscribe("069500"); // 아직 미인증 — 보내면 100013으로 무시되므로 보내지 않아야 함
        assertEquals(0, sent.size());

        client.handleTextMessage(s, new org.springframework.web.socket.TextMessage(
                "{\"trnm\":\"LOGIN\",\"return_code\":0}"));
        assertTrue(sent.stream().anyMatch(m -> m.contains("069500")));
    }

    @Test
    void LOGIN_실패면_세션을_닫는다() throws Exception {
        List<String> sent = new ArrayList<>();
        var s = wsSession(sent);
        client.afterConnectionEstablished(s);

        client.handleTextMessage(s, new org.springframework.web.socket.TextMessage(
                "{\"trnm\":\"LOGIN\",\"return_code\":8005,\"return_msg\":\"인증 실패\"}"));

        org.mockito.Mockito.verify(s).close();
    }

    @Test
    void PING은_같은_전문으로_에코된다() throws Exception {
        List<String> sent = new ArrayList<>();
        var s = wsSession(sent);

        client.handleTextMessage(s, new org.springframework.web.socket.TextMessage("{\"trnm\":\"PING\"}"));

        assertEquals(1, sent.size());
        assertEquals("{\"trnm\":\"PING\"}", sent.get(0));
    }

    // ── 중복 connect() 가드 (실측 2026-09-11: start()와 watchdog 첫 틱이 거의 동시에 실행되어
    //    토큰이 중복 발급(429)됐다) ──────────────────────────────────────────────

    private boolean connectingFlag() throws Exception {
        var field = KiwoomWebSocketClient.class.getDeclaredField("connecting");
        field.setAccessible(true);
        return ((java.util.concurrent.atomic.AtomicBoolean) field.get(client)).get();
    }

    private void setConnectingFlag(boolean value) throws Exception {
        var field = KiwoomWebSocketClient.class.getDeclaredField("connecting");
        field.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicBoolean) field.get(client)).set(value);
    }

    @Test
    void connect_진행중이면_watchdog은_재연결을_시도하지_않는다() throws Exception {
        // connecting=true로 미리 세팅 — 이미 진행 중인 connect() 시도가 있는 상태를 흉내낸다.
        // wsUrl()이 mock 기본값(null)이라 실제 connect()가 실행되면 URI.create(null)에서
        // NPE가 나고 catch 블록이 connecting을 false로 되돌린다 — 그러므로 watchdog 호출 후에도
        // connecting이 계속 true라면 connect()가 아예 호출되지 않았다는 뜻이다.
        setConnectingFlag(true);

        client.watchdog(); // session이 없으므로 "단절"로 판단되는 상태

        assertTrue(connectingFlag(), "connect() 진행 중일 때는 watchdog이 재연결을 건너뛰어야 한다");
    }

    @Test
    void connect_진행중이_아니면_watchdog이_재연결을_시도한다() throws Exception {
        // connecting=false(기본값) — watchdog이 connect()를 호출해야 한다. wsUrl()이 null이라
        // 실제 연결은 NPE로 즉시 실패하지만, connect()의 finally 경로(catch 블록)에서
        // connecting을 다시 false로 되돌리는 것으로 "connect()가 실제로 시도됐다"를 검증한다.
        client.watchdog();

        assertEquals(false, connectingFlag(), "connect() 시도/완료 후에는 connecting이 false로 돌아와야 한다");
    }

    /** 테스트 전용 가변 Clock — 단절 경과시간을 결정론적으로 진행시킨다. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(long seconds) {
            this.instant = this.instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
