package com.autostock.market;

import com.autostock.common.event.RecentListings;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
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
}
