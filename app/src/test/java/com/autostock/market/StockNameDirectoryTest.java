package com.autostock.market;

import com.autostock.common.util.StockNames;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 종목명 사전 담당(aiDoc/stock-names.md) — DB 사전 적재, 모르는 종목 조회(최대 5건씩·실패 뒤 30분 대기),
 * 다른 경로로 배운 이름의 지연 저장, 7일 지난 이름의 재조회를 검증한다. 정적 사전은 테스트마다
 * {@code StockNamesResetExtension}이 비운다.
 */
class StockNameDirectoryTest {

    private static final Instant NOW = Instant.parse("2026-10-02T07:00:00Z");

    private final StockNameRepository repository = mock(StockNameRepository.class);
    private final MarketDataPort marketData = mock(MarketDataPort.class);
    private final MutableClock clock = new MutableClock(NOW);

    private StockNameDirectory directory(List<String> tradingSymbols, List<String> archiveSymbols) {
        return new StockNameDirectory(repository, marketData, clock, tradingSymbols, archiveSymbols);
    }

    private StockNameDirectory directory() {
        return directory(List.of(), List.of());
    }

    private static MarketDataPort.StockQuote quoteNamed(String name) {
        return new MarketDataPort.StockQuote(name, null, null, null, null);
    }

    private static StockNameEntity row(String symbol, String name, String source, Instant updatedAt) {
        return new StockNameEntity(symbol, name, source, updatedAt);
    }

    @Test
    void 기동하면_저장된_사전을_올리고_설정_종목_중_이름이_없는_것만_조회한다() {
        when(repository.findAll()).thenReturn(List.of(row("005930", "삼성전자", "KIWOOM", NOW.minus(Duration.ofDays(1)))));
        when(marketData.stockQuote("000660")).thenReturn(quoteNamed("SK하이닉스"));
        when(marketData.stockQuote("069500")).thenReturn(quoteNamed("KODEX 200"));
        StockNameDirectory directory = directory(List.of("005930", " 000660 ", ""), List.of("000660", "069500", "BAD"));

        directory.start();
        assertEquals("삼성전자(005930)", StockNames.label("005930")); // 재기동 직후에도 저장된 이름이 바로 보인다
        directory.lookupPending();

        verify(marketData, never()).stockQuote("005930");
        assertEquals("SK하이닉스(000660)", StockNames.label("000660"));
        assertEquals("KODEX 200(069500)", StockNames.label("069500"));
        verify(repository).upsert("000660", "SK하이닉스", "KIWOOM", NOW);
        verify(repository).upsert("069500", "KODEX 200", "KIWOOM", NOW);
        verify(repository, never()).upsert(eq("005930"), anyString(), anyString(), any());
    }

    @Test
    void 표시하다_모르는_종목을_만나면_조회해서_채우고_DB에_남긴다() {
        when(marketData.stockQuote("035420")).thenReturn(quoteNamed("NAVER"));
        StockNameDirectory directory = directory();
        directory.start();

        assertEquals("종목명 미확인(035420)", StockNames.label("035420"));
        directory.lookupPending();

        assertEquals("NAVER(035420)", StockNames.label("035420"));
        verify(repository).upsert("035420", "NAVER", "KIWOOM", NOW);
    }

    @Test
    void 조회에_실패하면_30분_동안_다시_조회하지_않는다() {
        when(marketData.stockQuote("035420")).thenThrow(new RuntimeException("[1700] 유량 초과"));
        StockNameDirectory directory = directory();
        directory.start();

        StockNames.label("035420");
        directory.lookupPending();
        StockNames.label("035420"); // 실패 직후 다시 표시해도 조회 대기열에 넣지 않는다
        directory.lookupPending();
        verify(marketData, times(1)).stockQuote("035420");

        clock.advance(Duration.ofMinutes(31));
        StockNames.label("035420");
        directory.lookupPending();
        verify(marketData, times(2)).stockQuote("035420");
        assertEquals("종목명 미확인(035420)", StockNames.label("035420"));
    }

    @Test
    void 응답에_종목명이_없으면_실패로_보고_저장하지_않는다() {
        when(marketData.stockQuote("035420")).thenReturn(MarketDataPort.StockQuote.EMPTY);
        StockNameDirectory directory = directory();
        directory.start();

        StockNames.label("035420");
        directory.lookupPending();
        StockNames.label("035420");
        directory.lookupPending();

        verify(marketData, times(1)).stockQuote("035420");
        assertFalse(StockNames.isKnown("035420"));
        verify(repository, never()).upsert(anyString(), anyString(), anyString(), any());
    }

    @Test
    void 한_번에_최대_5건만_조회하고_나머지는_다음_주기에_조회한다() {
        when(marketData.stockQuote(anyString())).thenReturn(quoteNamed("테스트종목"));
        StockNameDirectory directory = directory();
        directory.start();
        for (int i = 1; i <= 7; i++) {
            StockNames.label("10000" + i);
        }

        directory.lookupPending();
        verify(marketData, times(StockNameDirectory.MAX_LOOKUPS_PER_RUN)).stockQuote(anyString());

        directory.lookupPending();
        verify(marketData, times(7)).stockQuote(anyString());
    }

    @Test
    void 다른_경로로_배운_이름은_호출_스레드가_아니라_다음_주기에_DB에_남긴다() {
        StockNameDirectory directory = directory();
        directory.start();

        StockNames.learn("005930", "삼성전자", StockNames.Source.KIWOOM); // 예: 시세 어댑터·잔고 복원
        verify(repository, never()).upsert(anyString(), anyString(), anyString(), any());

        directory.lookupPending();
        verify(repository).upsert("005930", "삼성전자", "KIWOOM", NOW);
    }

    @Test
    void 저장에_실패해도_메모리_사전은_그대로_쓴다() {
        doThrow(new RuntimeException("DB 연결 끊김")).when(repository).upsert(anyString(), anyString(), anyString(), any());
        StockNameDirectory directory = directory();
        directory.start();

        StockNames.learn("000660", "SK하이닉스", StockNames.Source.KIWOOM);
        directory.lookupPending(); // 예외를 밖으로 던지지 않는다

        assertEquals("SK하이닉스(000660)", StockNames.label("000660"));
    }

    @Test
    void 저장된_지_7일이_지난_이름은_기동_때_다시_조회하고_공시_기업명은_키움_이름으로_바꾼다() {
        when(repository.findAll()).thenReturn(List.of(
                row("005930", "삼성전자", "KIWOOM", NOW.minus(Duration.ofDays(8))),
                row("000660", "에스케이하이닉스(주)", "DART", NOW.minus(Duration.ofDays(8)))));
        when(marketData.stockQuote("005930")).thenReturn(quoteNamed("삼성전자"));
        when(marketData.stockQuote("000660")).thenReturn(quoteNamed("SK하이닉스"));
        StockNameDirectory directory = directory();

        directory.start();
        directory.lookupPending();

        verify(repository).upsert("005930", "삼성전자", "KIWOOM", NOW); // 이름이 그대로여도 확인 시각을 갱신한다
        verify(repository).upsert("000660", "SK하이닉스", "KIWOOM", NOW);
        assertEquals("SK하이닉스(000660)", StockNames.label("000660"));
    }

    @Test
    void 평일_아침_갱신은_7일_지난_행을_이름이_있어도_다시_조회한다() {
        StockNameDirectory directory = directory();
        directory.start();
        StockNames.preload("005930", "삼성전자", StockNames.Source.KIWOOM);
        when(repository.findByUpdatedAtBefore(NOW.minus(StockNameDirectory.REFRESH_AFTER)))
                .thenReturn(List.of(row("005930", "삼성전자", "KIWOOM", NOW.minus(Duration.ofDays(9)))));
        when(marketData.stockQuote("005930")).thenReturn(quoteNamed("삼성전자"));

        directory.refreshStale();
        directory.lookupPending();

        verify(marketData).stockQuote("005930");
        verify(repository).upsert("005930", "삼성전자", "KIWOOM", NOW);
    }

    @Test
    void 종료하면_청취자를_풀고_남은_이름을_저장한다() {
        StockNameDirectory directory = directory();
        directory.start();
        StockNames.learn("005930", "삼성전자", StockNames.Source.KIWOOM);

        directory.stop();
        verify(repository).upsert("005930", "삼성전자", "KIWOOM", NOW);

        StockNames.learn("000660", "SK하이닉스", StockNames.Source.KIWOOM);
        StockNames.label("035420");
        directory.lookupPending();
        verify(repository, never()).upsert(eq("000660"), anyString(), anyString(), any());
        verify(marketData, never()).stockQuote(anyString());
    }

    /** 테스트에서 시각을 진행시키는 가변 시계. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
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
            return now;
        }
    }
}
