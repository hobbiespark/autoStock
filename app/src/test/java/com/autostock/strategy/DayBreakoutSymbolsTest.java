package com.autostock.strategy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** VB-DAY 감시 종목 다시 선언(VALUNIVO 918) — 운영은 기본값을 쓴다(start_autostock.bat은 켜기만 한다). */
class DayBreakoutSymbolsTest {

    @Test
    void 기본_감시_종목은_11월_20일_다시_선언한_20종목이다() {
        DayBreakoutProperties bound = Binder.get(new StandardEnvironment())
                .bindOrCreate("strategy.vb", DayBreakoutProperties.class);

        List<String> symbols = bound.symbols();
        assertEquals(20, symbols.size());
        assertEquals(20, symbols.stream().distinct().count(), "중복 없음");
        assertEquals("005930", symbols.get(0), "시총 순서 그대로 — 1위 삼성전자 유지");
        assertTrue(symbols.containsAll(List.of("105560", "034020", "000270", "055550", "086790")), "1주 20만 원 이하 기존 종목 유지");
        assertFalse(symbols.contains("000660"), "SK하이닉스(1주 111만 원)는 뺀다");
        assertFalse(symbols.contains("068270"), "셀트리온(11-20 상한 초과로 거부된 종목)은 뺀다");
        assertEquals("033780", symbols.get(19), "20번째 KT&G(시총 43위)");
    }
}
