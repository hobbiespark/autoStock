package com.autostock.execution;

import com.autostock.common.event.DayStartMarks;
import com.autostock.common.event.PositionRestored;
import com.autostock.common.util.Price;
import com.autostock.common.util.StockCode;
import com.autostock.common.util.StockNames;
import com.autostock.market.MarketCalendarService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PositionRestorerTest {

    private final BrokerPort brokerPort = mock(BrokerPort.class);
    private final List<Object> published = new ArrayList<>();

    private final MarketCalendarService calendar = mock(MarketCalendarService.class);
    /** 2026-10-14(수) 08:25 KST. */
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-13T23:25:00Z"), ZoneOffset.UTC);

    private PositionRestorer restorer(String mode) {
        return new PositionRestorer(brokerPort, published::add, mode, calendar, clock);
    }

    private BrokerBalance balanceWith(List<BrokerHolding> holdings) {
        return new BrokerBalance(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, holdings);
    }

    @Test
    void 보유마다_복원_이벤트를_발행하고_종목명을_남긴다() {
        // 원소 해석(A 접두·후보 키·형식 오류 제외)은 어댑터 몫이다 — KiwoomBrokerAdapterTest(실행 계획 1.5)
        when(brokerPort.balance()).thenReturn(balanceWith(List.of(
                new BrokerHolding(new StockCode("005930"), "삼성전자", 19, new Price(new BigDecimal("259974"))),
                new BrokerHolding(new StockCode("000660"), "", 3, new Price(new BigDecimal("200000"))))));

        restorer("LIVE").restore();

        assertEquals(List.of(new PositionRestored(new StockCode("005930"), 19, new Price(new BigDecimal("259974"))),
                new PositionRestored(new StockCode("000660"), 3, new Price(new BigDecimal("200000")))), published);
        // 잔고의 종목명(stk_nm)을 사전에 남겨 복원 로그·알림·화면이 바로 "삼성전자(005930)"로 표시한다
        assertEquals("삼성전자(005930)", StockNames.label("005930"));
    }

    @Test
    void 평단_미상이면_null로_복원한다() {
        // 예전에는 0을 넣었고, 그 값이 C3 강제 청산 지정가로 쓰이면 0원 주문이 나갔다(조각 16·19)
        when(brokerPort.balance()).thenReturn(balanceWith(List.of(
                new BrokerHolding(new StockCode("005930"), "", 19, null))));

        restorer("LIVE").restore();

        assertEquals(List.of(new PositionRestored(new StockCode("005930"), 19, null)), published);
    }

    @Test
    void SIM_모드에서는_잔고를_조회하지_않는다() {
        restorer("SIM").restore();

        assertEquals(List.of(), published);
    }

    @Test
    void 복원_이벤트에_전일_종가를_싣는다() {
        when(brokerPort.balance()).thenReturn(balanceWith(List.of(new BrokerHolding(new StockCode("005930"), "삼성전자", 192,
                new Price(new BigDecimal("259000")), new Price(new BigDecimal("241500"))))));

        restorer("LIVE").restore();

        assertEquals(List.of(new PositionRestored(new StockCode("005930"), 192, new Price(new BigDecimal("259000")),
                new Price(new BigDecimal("241500")))), published);
    }

    // ── 거래일 아침 일 손실 기준가(2026-10-13) ─────────────────────────────────────────────

    @Test
    void 거래일_아침에_잔고의_전일_종가로_기준가_이벤트를_한_번_발행한다() {
        when(calendar.isTradingDay(LocalDate.of(2026, 10, 14))).thenReturn(true);
        when(brokerPort.balance()).thenReturn(balanceWith(List.of(new BrokerHolding(new StockCode("005930"), "삼성전자", 192,
                new Price(new BigDecimal("259000")), new Price(new BigDecimal("241500"))))));
        PositionRestorer restorer = restorer("LIVE");

        restorer.markDayStart();
        restorer.markDayStart();   // 08:35 — 이미 발행했다

        assertEquals(List.of(new DayStartMarks(LocalDate.of(2026, 10, 14), List.of(new DayStartMarks.Mark(
                new StockCode("005930"), 192, new Price(new BigDecimal("259000")), new Price(new BigDecimal("241500")))))),
                published);
    }

    @Test
    void 잔고_조회가_실패하면_다음_시각에_다시_본다() {
        when(calendar.isTradingDay(LocalDate.of(2026, 10, 14))).thenReturn(true);
        when(brokerPort.balance()).thenThrow(new IllegalStateException("모의 서버 점검"))
                .thenReturn(balanceWith(List.of()));
        PositionRestorer restorer = restorer("LIVE");

        restorer.markDayStart();   // 08:25 실패
        assertEquals(List.of(), published);
        restorer.markDayStart();   // 08:35

        assertEquals(List.of(new DayStartMarks(LocalDate.of(2026, 10, 14), List.of())), published);
    }

    @Test
    void 휴장일과_SIM_모드에서는_기준가를_발행하지_않는다() {
        when(calendar.isTradingDay(LocalDate.of(2026, 10, 14))).thenReturn(false);

        restorer("LIVE").markDayStart();
        when(calendar.isTradingDay(LocalDate.of(2026, 10, 14))).thenReturn(true);
        restorer("SIM").markDayStart();

        assertEquals(List.of(), published);
        verify(brokerPort, never()).balance();
    }
}
