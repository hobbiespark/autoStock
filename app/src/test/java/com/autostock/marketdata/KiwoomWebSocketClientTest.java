package com.autostock.marketdata;

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
        client = new KiwoomWebSocketClient(
                mock(KiwoomProperties.class),
                mock(TokenManager.class),
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
