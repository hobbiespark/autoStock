package com.autostock.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MinuteBarArchiver — 오늘 행만 CSV에 적재하고, 다시 실행해도 같은 시각을 중복 적재하지 않는다(멱등).
 */
class MinuteBarArchiverTest {

    @TempDir
    Path dir;

    private final MarketDataPort port = mock(MarketDataPort.class);

    private static MarketDataPort.MinuteBar bar(LocalDateTime time, String close, long volume, long acc) {
        BigDecimal price = new BigDecimal(close);
        return new MarketDataPort.MinuteBar(time, price, price, price, price, volume, acc);
    }

    private MinuteBarArchiver archiver() {
        return new MinuteBarArchiver(port, true, "005930", dir.toString(), Clock.systemUTC());
    }

    @Test
    void 오늘_행만_시간순으로_적재하고_재실행은_멱등이다() throws IOException {
        when(port.minuteBars("005930")).thenReturn(List.of(
                bar(LocalDateTime.of(2026, 9, 30, 9, 2), "258100", 20, 50),
                bar(LocalDateTime.of(2026, 9, 30, 9, 1), "258000", 30, 30),
                bar(LocalDateTime.of(2026, 9, 29, 15, 30), "257000", 10, 999)));
        MinuteBarArchiver archiver = archiver();

        assertEquals(2, archiver.archiveSymbol("005930", "20260930"));
        assertEquals(0, archiver.archiveSymbol("005930", "20260930"));

        List<String> lines = Files.readAllLines(dir.resolve("005930.csv"), StandardCharsets.UTF_8);
        assertEquals(List.of(
                "time,open,high,low,close,volume,acc_volume",
                "20260930090100,258000,258000,258000,258000,30,30",
                "20260930090200,258100,258100,258100,258100,20,50"), lines);
    }
}
