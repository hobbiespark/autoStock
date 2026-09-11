package com.autostock.execution;

import com.autostock.risk.RiskProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BrokerEquitySource — 60초 캐시, 실패 시 마지막 성공값/paperEquity 폴백을 검증한다.
 * Clock을 고정·이동시켜 "60초가 지났다"를 결정론적으로 검증한다(StaleOrderCancellerTest와 동일 기법).
 */
class BrokerEquitySourceTest {

    private static final Instant T0 = Instant.parse("2026-08-13T01:00:00Z");

    private BrokerPort brokerPort;
    private RiskProperties riskProperties;

    @BeforeEach
    void setUp() {
        brokerPort = mock(BrokerPort.class);
        riskProperties = new RiskProperties(0.10, 5, -0.03, 0.05, -0.02, 30,
                10_000_000, 0.00015, 0.0015, false);
    }

    private BrokerBalance balanceOf(String amount) {
        // 실측 확정(2026-09-11): equity는 추정예탁자산(prsm_dpst_aset_amt) 기준 — 4번째 인자.
        return new BrokerBalance(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal(amount), List.of());
    }

    @Test
    void 정상_조회시_브로커_잔고값을_반환한다() {
        when(brokerPort.balance()).thenReturn(balanceOf("50000000"));
        BrokerEquitySource source = new BrokerEquitySource(brokerPort, riskProperties, Clock.fixed(T0, ZoneOffset.UTC));

        assertEquals(0, new BigDecimal("50000000").compareTo(source.equity()));
    }

    @Test
    void 캐시_유효기간_60초_이내_재호출은_캐시된_값을_반환하고_브로커를_다시_호출하지_않는다() {
        when(brokerPort.balance()).thenReturn(balanceOf("50000000"));
        MutableClock clock = new MutableClock(T0);
        BrokerEquitySource source = new BrokerEquitySource(brokerPort, riskProperties, clock);

        source.equity();
        clock.advance(30); // 30초 경과 — 아직 캐시 유효
        source.equity();

        verify(brokerPort, times(1)).balance();
    }

    @Test
    void 캐시_유효기간_60초_경과후_재호출하면_다시_브로커를_조회한다() {
        when(brokerPort.balance()).thenReturn(balanceOf("50000000")).thenReturn(balanceOf("51000000"));
        MutableClock clock = new MutableClock(T0);
        BrokerEquitySource source = new BrokerEquitySource(brokerPort, riskProperties, clock);

        assertEquals(0, new BigDecimal("50000000").compareTo(source.equity()));
        clock.advance(61); // 60초 초과 — 캐시 만료
        assertEquals(0, new BigDecimal("51000000").compareTo(source.equity()));

        verify(brokerPort, times(2)).balance();
    }

    @Test
    void 조회_실패시_마지막_성공값으로_폴백한다() {
        when(brokerPort.balance())
                .thenReturn(balanceOf("50000000"))
                .thenThrow(new RuntimeException("네트워크 오류"));
        MutableClock clock = new MutableClock(T0);
        BrokerEquitySource source = new BrokerEquitySource(brokerPort, riskProperties, clock);

        assertEquals(0, new BigDecimal("50000000").compareTo(source.equity())); // 1차 성공
        clock.advance(61); // 캐시 만료시켜 재조회 유도
        assertEquals(0, new BigDecimal("50000000").compareTo(source.equity())); // 실패 → 마지막 성공값 폴백
    }

    @Test
    void 첫_조회부터_실패하면_paperEquity로_폴백한다() {
        when(brokerPort.balance()).thenThrow(new RuntimeException("네트워크 오류"));
        BrokerEquitySource source = new BrokerEquitySource(brokerPort, riskProperties, Clock.fixed(T0, ZoneOffset.UTC));

        assertEquals(0, BigDecimal.valueOf(riskProperties.paperEquity()).compareTo(source.equity()));
    }

    /** 테스트 전용 가변 Clock — 캐시 TTL 경과를 결정론적으로 검증하기 위해 시각을 앞으로 이동시킨다. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(long seconds) {
            this.instant = this.instant.plusSeconds(seconds);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
