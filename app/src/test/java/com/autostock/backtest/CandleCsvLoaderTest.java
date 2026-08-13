package com.autostock.backtest;

import com.autostock.common.event.Candle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CandleCsvLoaderTest {

    private final CandleCsvLoader loader = new CandleCsvLoader();

    @Test
    void CSV를_읽어_종목코드는_파일명에서_가져온다(@TempDir Path dir) throws IOException {
        Path csv = dir.resolve("005930.csv");
        Files.writeString(csv, """
                date,open,high,low,close,volume
                2026-01-02,70000,71000,69500,70500,1000000
                2026-01-05,70500,72000,70200,71800,1200000
                """);

        List<Candle> candles = loader.load(csv);

        assertEquals(2, candles.size());
        assertEquals("005930", candles.get(0).symbol());
        assertEquals(LocalDate.of(2026, 1, 2), candles.get(0).date());
        assertEquals(new BigDecimal("70000"), candles.get(0).open());
        assertEquals(new BigDecimal("70500"), candles.get(0).close());
        assertEquals(1000000L, candles.get(0).volume());
        assertEquals(new BigDecimal("71800"), candles.get(1).close());
    }
}
