package com.autostock.market;

import com.autostock.common.event.Candle;
import com.autostock.common.event.ListingDayPrices;
import com.autostock.common.event.RecentListings;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NewListingWatcher — 거래일 하루 한 번 최근 상장 종목만 추려 발행하고, 실패하면 다음 시각에 다시 한다(2026-10-13, 공모주 상장일 자동 확인).
 */
class NewListingWatcherTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 2026-10-15(목) 08:05·08:40 KST. */
    private static final LocalDateTime THURSDAY_0805 = LocalDateTime.of(2026, 10, 15, 8, 5);
    private static final LocalDateTime THURSDAY_0840 = LocalDateTime.of(2026, 10, 15, 8, 40);

    private final MarketDataPort marketData = mock(MarketDataPort.class);
    private final MarketCalendarService calendar = mock(MarketCalendarService.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

    private NewListingWatcher watcher(boolean enabled, LocalDateTime kst) {
        Clock clock = Clock.fixed(kst.atZone(KST).toInstant(), ZoneOffset.UTC);
        return new NewListingWatcher(marketData, calendar, publisher, enabled, clock);
    }

    private static MarketDataPort.ListedStock stock(String code, String name, LocalDate listedOn) {
        return new MarketDataPort.ListedStock(code, name, listedOn, "코스닥");
    }

    @Test
    void 상장일이_최근인_종목만_추려_발행하고_그날은_다시_하지_않는다() {
        when(calendar.isTradingDay(LocalDate.of(2026, 10, 15))).thenReturn(true);
        when(marketData.listedStocks(MarketDataPort.ListingMarket.KOSPI))
                .thenReturn(List.of(stock("005930", "삼성전자", LocalDate.of(1975, 6, 11))));
        when(marketData.listedStocks(MarketDataPort.ListingMarket.KOSDAQ)).thenReturn(List.of(
                stock("250030", "진코스텍", LocalDate.of(2026, 10, 15)),
                stock("123450", "지난달상장", LocalDate.of(2026, 9, 1)),
                stock("123460", "날짜모름", null),
                stock("123470", "열흘전상장", LocalDate.of(2026, 10, 5))));
        NewListingWatcher watcher = watcher(true, THURSDAY_0840);

        assertEquals(2, watcher.watch("정기"));
        assertEquals(-1, watcher.watch("재시도"), "08:40 뒤에 성공했으면 그날은 다시 부르지 않는다");

        ArgumentCaptor<RecentListings> captor = ArgumentCaptor.forClass(RecentListings.class);
        verify(publisher, times(1)).publishEvent(captor.capture());
        assertEquals(LocalDate.of(2026, 10, 15), captor.getValue().day());
        assertEquals(List.of("250030", "123470"),
                captor.getValue().listings().stream().map(RecentListings.Listing::stockCode).toList(), "최근 상장부터");
        verify(marketData, times(1)).listedStocks(MarketDataPort.ListingMarket.KOSDAQ);
    }

    @Test
    void 장전_동시호가_전_성공은_08시40분에_한_번_더_본다() {
        when(calendar.isTradingDay(any())).thenReturn(true);
        when(marketData.listedStocks(any())).thenReturn(List.of());
        NewListingWatcher watcher = watcher(true, THURSDAY_0805);

        assertEquals(0, watcher.watch("정기"));
        assertEquals(0, watcher.watch("정기"));
        verify(marketData, times(2)).listedStocks(MarketDataPort.ListingMarket.KOSDAQ);
        verify(publisher, never()).publishEvent(any());   // 최근 상장 종목이 없으면 발행하지 않는다
    }

    @Test
    void 실패하면_그날_성공으로_치지_않고_다음_시각에_다시_한다() {
        when(calendar.isTradingDay(any())).thenReturn(true);
        when(marketData.listedStocks(MarketDataPort.ListingMarket.KOSPI))
                .thenThrow(new IllegalStateException("키움 API 응답 없음 [ka10099] 15초 내 응답 없음"))
                .thenReturn(List.of());
        when(marketData.listedStocks(MarketDataPort.ListingMarket.KOSDAQ))
                .thenReturn(List.of(stock("250030", "진코스텍", LocalDate.of(2026, 10, 15))));
        NewListingWatcher watcher = watcher(true, THURSDAY_0840);

        assertEquals(-1, watcher.watch("정기"));
        assertEquals(1, watcher.watch("재시도"));
        assertEquals(-1, watcher.watch("재시도"));
        verify(publisher, times(1)).publishEvent(any(RecentListings.class));
    }

    @Test
    void 모의투자_서버가_지원하지_않으면_그날은_그만둔다() {
        when(calendar.isTradingDay(any())).thenReturn(true);
        when(marketData.listedStocks(any()))
                .thenThrow(new IllegalStateException("키움 API 논리 오류 [ka10099] 모의투자에서 지원하지 않는 API 입니다.[8104]"));
        NewListingWatcher watcher = watcher(true, THURSDAY_0805);

        assertEquals(-1, watcher.watch("정기"));
        assertEquals(-1, watcher.watch("정기"));
        verify(marketData, times(1)).listedStocks(any());
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void 꺼져_있거나_휴장일이면_부르지_않는다() {
        when(calendar.isTradingDay(any())).thenReturn(false);

        assertEquals(-1, watcher(false, THURSDAY_0805).watch("정기"));
        assertEquals(-1, watcher(true, THURSDAY_0805).watch("정기"));
        verify(marketData, never()).listedStocks(any());
    }

    @Test
    void 기동_따라잡기는_08시_전이나_20시_뒤에는_하지_않는다() throws Exception {
        when(calendar.isTradingDay(any())).thenReturn(true);
        when(marketData.listedStocks(any())).thenReturn(List.of());

        watcher(true, LocalDateTime.of(2026, 10, 15, 7, 13)).catchUpOnStartup();
        watcher(true, LocalDateTime.of(2026, 10, 15, 21, 0)).catchUpOnStartup();
        Thread.sleep(200);   // 따라잡기는 가상 스레드에서 돈다 — 돌았다면 이 사이에 불렀다

        verify(marketData, never()).listedStocks(any());
    }

    // ── 상장일 시세(2026-10-15, 공모주 청약 추천의 시장 분위기 재료) ───────────────────────────────

    private static Candle candle(String code, LocalDate date, String open, String close) {
        return new Candle(code, date, new BigDecimal(open), new BigDecimal(open), new BigDecimal(close), new BigDecimal(close), 1_000);
    }

    @Test
    void 장_마감_뒤_최근_상장_공모주의_상장일_시가_종가를_발행하고_ETF와_스팩은_뺀다() {
        LocalDate today = LocalDate.of(2026, 10, 15);
        when(calendar.isTradingDay(any())).thenReturn(true);
        when(marketData.listedStocks(MarketDataPort.ListingMarket.KOSPI)).thenReturn(List.of(
                new MarketDataPort.ListedStock("0241D0", "KODEX 미국AI에이전트액티브", LocalDate.of(2026, 10, 13), "ETF")));
        when(marketData.listedStocks(MarketDataPort.ListingMarket.KOSDAQ)).thenReturn(List.of(
                stock("179880", "멜콘", today),
                stock("468670", "브릴스", LocalDate.of(2026, 10, 8)),
                stock("468671", "보름전상장", LocalDate.of(2026, 10, 1)),
                stock("499990", "한화플러스제6호스팩", LocalDate.of(2026, 10, 14)),
                stock("123480", "다음주상장", LocalDate.of(2026, 10, 20))));
        // 키움 가격은 등락 부호가 붙어 온다(-15780) — 절댓값으로
        when(marketData.dailyCandles(eq("179880"), eq(today))).thenReturn(List.of(
                candle("179880", today, "+27400", "-16700")));
        when(marketData.dailyCandles(eq("468670"), eq(today))).thenReturn(List.of(
                candle("468670", LocalDate.of(2026, 10, 8), "61800", "31100"),
                candle("468670", LocalDate.of(2026, 10, 9), "30000", "29000")));
        NewListingWatcher watcher = watcher(true, LocalDateTime.of(2026, 10, 15, 15, 40));

        assertEquals(2, watcher.recordListingDayPrices("정기"));
        assertEquals(-1, watcher.recordListingDayPrices("정기"), "그날은 한 번");

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher, times(2)).publishEvent(captor.capture());   // 아침 목록(먼저 받음) + 상장일 시세
        ListingDayPrices prices = (ListingDayPrices) captor.getAllValues().get(1);
        assertEquals(List.of("179880", "468670"), prices.entries().stream().map(ListingDayPrices.Entry::stockCode).toList());
        assertEquals(new BigDecimal("27400"), prices.entries().get(0).open());
        assertEquals(new BigDecimal("16700"), prices.entries().get(0).close());
        assertEquals(new BigDecimal("31100"), prices.entries().get(1).close(), "상장일 행을 고른다(그 뒤 행이 아니라)");
        verify(marketData, never()).dailyCandles(eq("0241D0"), any());
        verify(marketData, never()).dailyCandles(eq("499990"), any());
        verify(marketData, never()).dailyCandles(eq("123480"), any());
        verify(marketData, never()).dailyCandles(eq("468671"), any());   // 상장일 시세는 최근 10일만(놓친 날 메우기)
    }

    @Test
    void 상장일_시세_조회가_실패하면_다음_거래일에_다시_본다() {
        LocalDate today = LocalDate.of(2026, 10, 15);
        when(calendar.isTradingDay(any())).thenReturn(true);
        when(marketData.listedStocks(MarketDataPort.ListingMarket.KOSPI)).thenReturn(List.of());
        when(marketData.listedStocks(MarketDataPort.ListingMarket.KOSDAQ)).thenReturn(List.of(stock("179880", "멜콘", today)));
        when(marketData.dailyCandles(eq("179880"), any())).thenThrow(new IllegalStateException("키움 1631"));
        NewListingWatcher watcher = watcher(true, LocalDateTime.of(2026, 10, 15, 15, 40));

        assertEquals(0, watcher.recordListingDayPrices("정기"));
        assertEquals(0, watcher.recordListingDayPrices("재시도"), "실패한 날은 마친 것으로 치지 않는다");
    }
}
