package com.autostock.monitor;

import com.autostock.common.event.BrokerAuthFailure;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 키움 인증 실패 긴급 알림(Phase 0.6) — 원인 안내 문구, 같은 코드 10분 억제.
 */
class BrokerAuthAlertListenerTest {

    private static final Instant T0 = Instant.parse("2026-10-06T00:00:00Z");

    private final List<String> sent = new ArrayList<>();
    private final List<NoticeLevel> levels = new ArrayList<>();
    private final Notifier notifier = (level, message) -> {
        levels.add(level);
        sent.add(message);
    };
    private final MovableClock clock = new MovableClock(T0);
    private final BrokerAuthAlertListener listener = new BrokerAuthAlertListener(notifier, clock);

    private static BrokerAuthFailure failure(String code) {
        return new BrokerAuthFailure(code, "인증에 실패했습니다[" + code + ":…]", "mockapi.kiwoom.com", T0);
    }

    @Test
    void 인증_실패는_원인_안내와_함께_긴급으로_보낸다() {
        listener.onBrokerAuthFailure(failure("8010"));

        assertEquals(List.of(NoticeLevel.CRITICAL), levels);
        String message = sent.get(0);
        assertTrue(message.contains("키움 인증 실패 [8010] (mockapi.kiwoom.com)"), message);
        assertTrue(message.contains("허용 IP 목록에 등록"), message);
        assertTrue(message.contains("openapi.kiwoom.com"), message);
    }

    @Test
    void 같은_코드는_10분_안에_한번만_보내고_지나면_다시_보낸다() {
        listener.onBrokerAuthFailure(failure("8001"));
        clock.advance(Duration.ofMinutes(9));
        listener.onBrokerAuthFailure(failure("8001"));
        assertEquals(1, sent.size());

        clock.advance(Duration.ofMinutes(1));
        listener.onBrokerAuthFailure(failure("8001"));
        assertEquals(2, sent.size());
    }

    @Test
    void 다른_코드는_따로_보낸다() {
        listener.onBrokerAuthFailure(failure("8001"));
        listener.onBrokerAuthFailure(failure("8040"));

        assertEquals(2, sent.size());
    }

    @Test
    void 안내가_없는_코드도_기본_문구로_보낸다() {
        listener.onBrokerAuthFailure(failure("8999"));

        assertTrue(sent.get(0).contains("키움 오류코드 표 확인"));
    }

    /** 테스트 전용 가변 시계. */
    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
