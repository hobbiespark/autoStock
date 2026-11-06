package com.autostock.monitor;

import com.autostock.monitor.view.PerformancePeriodView;
import com.autostock.portfolio.StrategyBook;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 전략 변경 전후 비교(2026-11-06) — 구간 경계, 지금 구간, 운용 자금 대비·거래일당·승률·거래당 수익률. */
class PerformancePeriodsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);

    private static final List<PerformanceProperties.Period> PERIODS = new PerformanceProperties(null, List.of(
            new PerformanceProperties.Period(LocalDate.of(2026, 10, 31), "200만 원·3분할", 2_000_000),
            new PerformanceProperties.Period(LocalDate.of(2026, 10, 27), "운용 자금 500만 원", 5_000_000),
            new PerformanceProperties.Period(LocalDate.of(2026, 11, 20), "아직 안 온 변경", 2_000_000))).periods();

    @Test
    void 구간을_시작일_순서로_나누고_아직_시작하지_않은_구간은_뺀다() {
        List<LocalDate[]> asked = new ArrayList<>();
        List<PerformancePeriodView> out = PerformancePeriods.compare(PERIODS, TODAY, (from, to) -> {
            asked.add(new LocalDate[]{from, to});
            return List.of();
        }, (from, to) -> 0);

        assertEquals(2, out.size());
        assertEquals("운용 자금 500만 원", out.get(0).label());
        assertEquals(LocalDate.of(2026, 10, 30), out.get(0).to(), "다음 구간 전날까지");
        assertFalse(out.get(0).current());
        assertEquals(LocalDate.of(2026, 11, 6), out.get(1).to(), "다음 구간이 아직 안 왔으면 오늘까지 — 지금 구간");
        assertTrue(out.get(1).current());
        assertEquals(2, asked.size());
    }

    @Test
    void 운용_자금이_달라도_비율로_비교한다() {
        List<PerformancePeriodView> out = PerformancePeriods.compare(PERIODS, TODAY, (from, to) -> from.getDayOfMonth() == 27
                        ? List.of(new StrategyBook.PeriodStat("ON-CLOSE", 4, 1, new BigDecimal("-10000"), new BigDecimal("1000000")))
                        : List.of(new StrategyBook.PeriodStat("ON-CLOSE", 2, 1, new BigDecimal("-2000"), new BigDecimal("400000")),
                                new StrategyBook.PeriodStat("VB-DAY", 2, 2, new BigDecimal("6000"), new BigDecimal("200000"))),
                (from, to) -> from.getDayOfMonth() == 27 ? 4 : 5);

        PerformancePeriodView before = out.get(0);
        assertEquals(0, new BigDecimal("-0.200").compareTo(before.capitalReturnPct()), "−1만 ÷ 500만");
        assertEquals(0, new BigDecimal("-0.050").compareTo(before.dailyAvgReturnPct()), "÷ 4거래일");
        assertEquals(0, new BigDecimal("-1.000").compareTo(before.tradeReturnPct()), "−1만 ÷ 원가 100만");
        assertEquals(0, new BigDecimal("25.000").compareTo(before.winRatePct()));

        PerformancePeriodView after = out.get(1);
        assertEquals(4, after.exits());
        assertEquals(3, after.wins());
        assertEquals(0, new BigDecimal("4000").compareTo(after.realizedPnl()));
        assertEquals(0, new BigDecimal("0.200").compareTo(after.capitalReturnPct()), "+4천 ÷ 200만");
        assertEquals(0, new BigDecimal("0.040").compareTo(after.dailyAvgReturnPct()));
        assertEquals(0, new BigDecimal("0.667").compareTo(after.tradeReturnPct()), "+4천 ÷ 원가 60만");
        assertEquals(2, after.strategies().size());
        assertEquals(0, new BigDecimal("3.000").compareTo(after.strategies().get(1).tradeReturnPct()), "VB-DAY 6천 ÷ 20만");
    }

    @Test
    void 운용_자금이나_청산이_없으면_비율을_비운다() {
        List<PerformanceProperties.Period> periods = List.of(
                new PerformanceProperties.Period(LocalDate.of(2026, 11, 1), "운용 자금 모름", 0));
        PerformancePeriodView only = PerformancePeriods.compare(periods, TODAY, (f, t) -> List.of(), (f, t) -> 0).get(0);
        assertNull(only.capitalReturnPct());
        assertNull(only.dailyAvgReturnPct());
        assertNull(only.winRatePct());
        assertNull(only.tradeReturnPct());
        assertEquals(0, BigDecimal.ZERO.compareTo(only.realizedPnl()));
    }

    @Test
    void 실제_application_yml의_구간이_설정으로_읽힌다() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml")).stream()
                .limit(1)   // 첫 문서(공통 기본값) — 프로필 문서는 performance를 바꾸지 않는다
                .forEach(source -> env.getPropertySources().addLast(source));
        PerformanceProperties bound = Binder.get(env).bind("monitor.performance", PerformanceProperties.class).get();

        assertEquals(LocalDate.of(2026, 10, 23), bound.since());
        assertEquals(4, bound.periods().size());
        assertEquals(LocalDate.of(2026, 10, 16), bound.periods().get(0).since());
        assertEquals(486_000_000L, bound.periods().get(0).capitalKrw());
        assertEquals(LocalDate.of(2026, 10, 31), bound.periods().get(3).since());
        assertEquals(2_000_000L, bound.periods().get(3).capitalKrw());
        assertTrue(bound.periods().get(3).label().startsWith("200만 원"));
    }

    @Test
    void 구간_설정을_검사한다() {
        assertThrows(IllegalArgumentException.class, () -> new PerformanceProperties.Period(null, "x", 0));
        assertThrows(IllegalArgumentException.class, () -> new PerformanceProperties.Period(TODAY, " ", 0));
        assertThrows(IllegalArgumentException.class, () -> new PerformanceProperties.Period(TODAY, "x", -1));
        assertTrue(new PerformanceProperties(null).periods().isEmpty());
    }
}
