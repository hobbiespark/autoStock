package com.autostock.common.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockNamesTest {

    @BeforeEach
    @AfterEach
    void reset() {
        StockNames.resetForTest();
    }

    @Test
    void 이름을_알면_이름과_코드를_함께_표시한다() {
        StockNames.learn("005930", "삼성전자", StockNames.Source.KIWOOM);

        assertEquals("삼성전자(005930)", StockNames.label("005930"));
        assertEquals("삼성전자(005930)", StockNames.label(new StockCode("005930")));
        assertEquals("삼성전자", StockNames.nameOf("005930").orElseThrow());
    }

    @Test
    void 이름을_모르면_미확인으로_표시하고_조회를_요청한다() {
        List<String> misses = new ArrayList<>();
        StockNames.onMiss(misses::add);

        assertEquals("종목명 미확인(069500)", StockNames.label("069500"));
        assertTrue(StockNames.nameOf("069500").isEmpty());
        assertEquals(List.of("069500", "069500"), misses);
    }

    @Test
    void 종목코드가_아니면_받은_값을_그대로_두고_조회하지_않는다() {
        List<String> misses = new ArrayList<>();
        StockNames.onMiss(misses::add);

        assertEquals("", StockNames.label((String) null));
        assertEquals("", StockNames.label("  "));
        assertEquals("A005930", StockNames.label("A005930"));
        assertEquals("", StockNames.label((StockCode) null));
        assertTrue(misses.isEmpty());
    }

    @Test
    void 키움_이름은_DART_이름이_덮지_못하고_DART_이름은_키움_이름이_덮는다() {
        assertTrue(StockNames.learn("035420", "네이버", StockNames.Source.DART));
        assertTrue(StockNames.learn("035420", "NAVER", StockNames.Source.KIWOOM));
        assertFalse(StockNames.learn("035420", "네이버", StockNames.Source.DART));

        assertEquals("NAVER(035420)", StockNames.label("035420"));
    }

    @Test
    void 같은_출처의_같은_이름은_변화가_아니고_새_이름은_변화다() {
        List<String> saved = new ArrayList<>();
        StockNames.onLearn((code, name, source) -> saved.add(code + "=" + name + "/" + source));

        assertTrue(StockNames.learn("000660", "SK하이닉스", StockNames.Source.KIWOOM));
        assertFalse(StockNames.learn("000660", " SK하이닉스 ", StockNames.Source.KIWOOM));
        assertTrue(StockNames.learn("000660", "SK하이닉스우", StockNames.Source.KIWOOM));

        assertEquals(List.of("000660=SK하이닉스/KIWOOM", "000660=SK하이닉스우/KIWOOM"), saved);
    }

    @Test
    void DB에서_올린_이름은_저장_청취자에_알리지_않는다() {
        List<String> saved = new ArrayList<>();
        StockNames.onLearn((code, name, source) -> saved.add(code));

        StockNames.preload("005930", "삼성전자", StockNames.Source.KIWOOM);

        assertEquals("삼성전자(005930)", StockNames.label("005930"));
        assertTrue(saved.isEmpty());
    }

    @Test
    void 쓸_수_없는_이름은_배우지_않는다() {
        assertFalse(StockNames.learn("005930", " ", StockNames.Source.KIWOOM));
        assertFalse(StockNames.learn("005930", null, StockNames.Source.KIWOOM));
        assertFalse(StockNames.learn("A005930", "삼성전자", StockNames.Source.KIWOOM));
        assertFalse(StockNames.learn("005930", "가".repeat(101), StockNames.Source.KIWOOM));
        assertFalse(StockNames.isKnown("005930"));
    }

    @Test
    void 호출자가_가진_이름은_사전에_없을_때만_쓰고_DART_출처로_남긴다() {
        assertEquals("삼성전자(005930)", StockNames.label("005930", "삼성전자"));
        assertTrue(StockNames.isKnown("005930"));

        StockNames.learn("005930", "삼성전자", StockNames.Source.KIWOOM);
        assertEquals("삼성전자(005930)", StockNames.label("005930", "삼성전자(주)"));
        assertEquals("종목명 미확인(000100)", StockNames.label("000100", " "));
    }

    @Test
    void 청취자_예외는_표시와_학습을_막지_않는다() {
        StockNames.onMiss(code -> {
            throw new IllegalStateException("조회 실패");
        });
        StockNames.onLearn((code, name, source) -> {
            throw new IllegalStateException("저장 실패");
        });

        assertEquals("종목명 미확인(005930)", StockNames.label("005930"));
        assertTrue(StockNames.learn("005930", "삼성전자", StockNames.Source.KIWOOM));
        assertEquals("삼성전자(005930)", StockNames.label("005930"));
    }
}
