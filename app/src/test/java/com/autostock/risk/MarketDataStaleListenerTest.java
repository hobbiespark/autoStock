package com.autostock.risk;

import com.autostock.common.event.MarketDataStale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MarketDataStaleListener — 시세 단절 이벤트 수신 시 킬스위치를 작동시키는지 검증.
 */
class MarketDataStaleListenerTest {

    private KillSwitch killSwitch;
    private MarketDataStaleListener listener;

    @BeforeEach
    void setUp() {
        killSwitch = new KillSwitch(event -> { });
        listener = new MarketDataStaleListener(killSwitch);
    }

    @Test
    void 시세_단절_이벤트를_받으면_킬스위치가_작동한다() {
        listener.onMarketDataStale(new MarketDataStale(Instant.parse("2026-08-13T01:00:00Z"), 200));

        assertTrue(killSwitch.isEngaged());
    }

    @Test
    void 이미_작동_중이면_중복_이벤트가_와도_예외없이_처리된다() {
        listener.onMarketDataStale(new MarketDataStale(Instant.parse("2026-08-13T01:00:00Z"), 200));
        listener.onMarketDataStale(new MarketDataStale(Instant.parse("2026-08-13T01:05:00Z"), 500));

        assertTrue(killSwitch.isEngaged());
    }
}
