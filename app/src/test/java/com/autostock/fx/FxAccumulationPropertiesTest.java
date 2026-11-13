package com.autostock.fx;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 외화 알림 시작일(VALUNIVO 877) — 12-01부터 감시·월초·월말이 돈다. */
class FxAccumulationPropertiesTest {

    private static final LocalDate DEC_1 = LocalDate.of(2026, 12, 1);

    @Test
    void 시작일_전날은_돌지_않고_당일부터_돈다() {
        FxAccumulationProperties p = new FxAccumulationProperties(true, 130_000, 20_000, 20_000, 15, DEC_1);

        assertFalse(p.activeOn(DEC_1.minusDays(1)));
        assertTrue(p.activeOn(DEC_1));
        assertTrue(p.activeOn(DEC_1.plusMonths(1)));
    }

    @Test
    void 시작일이_없으면_켜자마자_돌고_꺼져_있으면_시작일과_무관하게_돌지_않는다() {
        assertTrue(new FxAccumulationProperties(true, 130_000, 20_000, 20_000, 15, null).activeOn(DEC_1.minusDays(30)));
        assertFalse(new FxAccumulationProperties(false, 130_000, 20_000, 20_000, 15, DEC_1).activeOn(DEC_1));
    }

    @Test
    void application_yml_모양의_값이_날짜로_묶인다() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "fx.accumulation.alerts-enabled", "true",
                "fx.accumulation.active-from", "2026-12-01")));

        FxAccumulationProperties bound = binder.bind("fx.accumulation", FxAccumulationProperties.class).get();

        assertTrue(bound.alertsEnabled());
        assertEquals(DEC_1, bound.activeFrom());
        assertEquals(130_000, bound.monthlyCapKrw(), "나머지는 기본값");
        assertEquals(15, bound.reviewStartDay());
    }

    @Test
    void 시작일을_안_주면_비어_있다() {
        FxAccumulationProperties bound = new Binder(new MapConfigurationPropertySource(Map.of(
                "fx.accumulation.alerts-enabled", "false"))).bind("fx.accumulation", FxAccumulationProperties.class).get();

        assertNull(bound.activeFrom());
        assertFalse(bound.alertsEnabled());
    }
}
