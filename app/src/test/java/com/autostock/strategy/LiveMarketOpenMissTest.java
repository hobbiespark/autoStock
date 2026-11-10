package com.autostock.strategy;

import com.autostock.kiwoom.KiwoomApiException;
import com.autostock.market.KiwoomDailyChartService;
import com.autostock.market.MarketCalendarService;
import com.autostock.market.MarketDataPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** VALUNIVO 793 L1: 시가가 빠질 때 사유를 남기고, 받으면 지운다. 시가 값·캐시 동작은 그대로다. */
class LiveMarketOpenMissTest {

    private final MarketDataPort marketData = mock(MarketDataPort.class);

    @SuppressWarnings("unchecked")
    private final LiveMarket market = new LiveMarket(mock(KiwoomDailyChartService.class), marketData,
            mock(ObjectProvider.class), mock(MarketCalendarService.class),
            Clock.fixed(Instant.parse("2026-11-10T00:00:05Z"), ZoneId.of("UTC")));

    @Test
    void openBlankWithCurrentPriceIsReportedAsOpenOnly() {
        when(marketData.stockQuote("005930")).thenReturn(
                new MarketDataPort.StockQuote("삼성전자", new BigDecimal("61000"), null, null, null));

        assertThat(market.todayOpen("005930")).isNull();
        assertThat(market.openMissReason("005930")).isEqualTo(LiveMarket.MISS_OPEN_BLANK);
    }

    @Test
    void wholeQuoteBlankIsReportedSeparately() {
        when(marketData.stockQuote("000660")).thenReturn(MarketDataPort.StockQuote.EMPTY);

        assertThat(market.todayOpen("000660")).isNull();
        assertThat(market.openMissReason("000660")).isEqualTo(LiveMarket.MISS_QUOTE_BLANK);
    }

    @Test
    void exceptionReasonKeepsOnlyTheClassName() {
        when(marketData.stockQuote("000660")).thenThrow(new KiwoomApiException("키움 API 오류 [ka10001] 1700"));

        assertThat(market.todayOpen("000660")).isNull();
        assertThat(market.openMissReason("000660")).isEqualTo("조회 실패(KiwoomApiException)");
    }

    @Test
    void laterSuccessClearsTheReasonAndCachesTheOpen() {
        when(marketData.stockQuote("005930"))
                .thenReturn(MarketDataPort.StockQuote.EMPTY)
                .thenReturn(new MarketDataPort.StockQuote("삼성전자", new BigDecimal("61000"), new BigDecimal("60500"), null, null));

        assertThat(market.todayOpen("005930")).isNull();
        assertThat(market.todayOpen("005930")).isEqualByComparingTo("60500");
        assertThat(market.openMissReason("005930")).isNull();
        assertThat(market.knownOpen("005930")).isEqualByComparingTo("60500");
    }

    @Test
    void neverQueriedSymbolHasNoReason() {
        assertThat(market.openMissReason("035720")).isNull();
    }
}
