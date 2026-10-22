package com.autostock.market;

import com.autostock.common.event.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 판단용 일봉 — 오늘 진행 중인 봉을 뺀다(2026-10-16 실측: ka10081은 장중에도 오늘 봉을 맨 앞에 준다).
 */
class KiwoomDailyChartServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);

    private static Candle bar(LocalDate day, String close) {
        BigDecimal c = new BigDecimal(close);
        return new Candle("069500", day, c, c, c, c, 1L);
    }

    @Test
    void 완료된_봉만_돌려준다_오늘_봉은_뺀다() {
        MarketDataPort port = mock(MarketDataPort.class);
        // 10/16 09:40 실측 모양: 10/15 종가 87,025, 10/16 진행 중 89,505
        when(port.dailyCandles("069500", TODAY)).thenReturn(List.of(
                bar(LocalDate.of(2026, 10, 14), "94805"), bar(LocalDate.of(2026, 10, 15), "87025"), bar(TODAY, "89505")));
        KiwoomDailyChartService service = new KiwoomDailyChartService(port);

        List<Candle> completed = service.fetchCompletedDaily("069500", TODAY, 2);

        assertEquals(2, completed.size());
        assertEquals(LocalDate.of(2026, 10, 15), completed.get(1).date());
        assertEquals(0, new BigDecimal("87025").compareTo(completed.get(1).close()), "마지막 봉 = 어제 종가");
        assertEquals(3, service.fetchDaily("069500", TODAY, 2).size(), "장 마감 뒤 쓰는 조회는 그대로");
    }

    @Test
    void 장전에는_오늘_봉이_없어도_그대로() {
        MarketDataPort port = mock(MarketDataPort.class);
        when(port.dailyCandles("069500", TODAY)).thenReturn(List.of(bar(LocalDate.of(2026, 10, 15), "87025")));
        assertEquals(1, new KiwoomDailyChartService(port).fetchCompletedDaily("069500", TODAY, 1).size());
    }
}
