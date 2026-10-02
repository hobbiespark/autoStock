package com.autostock.monitor;

import com.autostock.common.util.StockNames;
import com.autostock.market.MarketDataPort;
import com.autostock.risk.KillSwitch;
import com.autostock.trading.TradingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GET /api/dashboard/quote — 시세 포트 값을 QuoteView로 옮기고, 기본정보 조회가 실패해도 500 대신 빈 값으로 답한다.
 */
class DashboardControllerQuoteTest {

    private final MarketDataPort marketData = mock(MarketDataPort.class);
    private final DashboardController controller = new DashboardController(
            mock(DashboardFacade.class), mock(KillSwitch.class), mock(ApplicationEventPublisher.class),
            marketData,
            new TradingProperties(TradingProperties.Mode.SIM, Duration.ofMinutes(5)), Clock.systemUTC());

    @Test
    void 시세와_최우선호가를_옮겨_담는다() {
        when(marketData.stockQuote("005930")).thenReturn(new MarketDataPort.StockQuote("삼성전자",
                new BigDecimal("258000"), new BigDecimal("261000"), new BigDecimal("261500"), new BigDecimal("257500")));
        when(marketData.bestQuote("005930")).thenReturn(
                new MarketDataPort.BestQuote(new BigDecimal("258500"), new BigDecimal("258000")));

        DashboardController.QuoteView view = controller.quote("005930");

        assertEquals("삼성전자", view.name());
        assertEquals(new BigDecimal("258000"), view.currentPrice());
        assertEquals(new BigDecimal("261500"), view.highPrice());
        assertEquals(new BigDecimal("258500"), view.bestAsk());
        assertEquals(new BigDecimal("258000"), view.bestBid());
    }

    @Test
    void 기본정보_조회가_실패하면_빈_시세에_호가만_채운다() {
        when(marketData.stockQuote("005930")).thenThrow(new RuntimeException("[1631] 서비스 처리 중 오류"));
        when(marketData.bestQuote("005930")).thenReturn(
                new MarketDataPort.BestQuote(new BigDecimal("258500"), new BigDecimal("258000")));

        DashboardController.QuoteView view = controller.quote("005930");

        assertNull(view.currentPrice()); // 기준가 기본값이 비어 사용자가 직접 입력한다(9/18 사고 방지)
        assertNull(view.name()); // 사전에도 이름이 없다
        assertEquals(new BigDecimal("258500"), view.bestAsk());
    }

    @Test
    void 기본정보_조회가_실패해도_사전에_이름이_있으면_종목명을_채운다() {
        StockNames.learn("005930", "삼성전자", StockNames.Source.KIWOOM);
        when(marketData.stockQuote("005930")).thenThrow(new RuntimeException("[1631] 서비스 처리 중 오류"));
        when(marketData.bestQuote("005930")).thenReturn(MarketDataPort.BestQuote.EMPTY);

        DashboardController.QuoteView view = controller.quote("005930");

        assertEquals("삼성전자", view.name()); // 화면은 코드와 종목명을 함께 보여준다
        assertNull(view.currentPrice());
    }
}
