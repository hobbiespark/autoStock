package com.autostock.ipo;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OfferingPriceParser — 실제 [발행조건확정] 원본(2026-09~10, {@link DartFixtures})에서 확정 공모가·희망 밴드를 읽는다(2026-10-13).
 */
class OfferingPriceParserTest {

    private static Optional<OfferingPriceParser.OfferingPrice> parse(String fixture) {
        return OfferingPriceParser.parse(DartDocument.plainText(DartFixtures.xml(fixture)));
    }

    @Test
    void 공모주_확정가와_원래_희망_밴드를_읽는다_밴드_아래로_확정된_경우() {
        OfferingPriceParser.OfferingPrice price = parse("20260909000307.zip").orElseThrow();   // 네오사피엔스

        assertEquals(new BigDecimal("10000"), price.finalPrice());
        assertEquals(new BigDecimal("13800"), price.bandLow());
        assertEquals(new BigDecimal("15800"), price.bandHigh());
        assertEquals("하단 미만", price.positionInBand());
    }

    @Test
    void 밴드_문장이_없는_확정_신고서는_확정가만_읽는다() {
        OfferingPriceParser.OfferingPrice price = parse("20260928000402.zip").orElseThrow();   // 진코스텍 둘째 [발행조건확정]

        assertEquals(new BigDecimal("23500"), price.finalPrice());
        assertNull(price.bandLow());
        assertNull(price.positionInBand());
    }

    @Test
    void 스팩의_고정_공모가를_읽는다() {
        assertEquals(new BigDecimal("2000"), parse("20260909000276.zip").orElseThrow().finalPrice());   // 한국제17호스팩
    }

    @Test
    void 확정가_문장이_없으면_빈_값이다() {
        assertTrue(parse("20260914000188.zip").isEmpty(), "툴젠 — 상장사 유상증자");
        assertTrue(parse("20261001000586.zip").isEmpty(), "진코스텍 10/1 — 기재사항 정정만");
        assertTrue(OfferingPriceParser.parse(null).isEmpty());
    }

    @Test
    void 확정가가_밴드의_어디인지_말한다() {
        BigDecimal low = new BigDecimal("16500");
        BigDecimal high = new BigDecimal("19500");
        assertEquals("상단", new OfferingPriceParser.OfferingPrice(high, low, high).positionInBand());
        assertEquals("상단 초과", new OfferingPriceParser.OfferingPrice(new BigDecimal("21000"), low, high).positionInBand());
        assertEquals("밴드 안", new OfferingPriceParser.OfferingPrice(new BigDecimal("18000"), low, high).positionInBand());
        assertEquals("하단", new OfferingPriceParser.OfferingPrice(low, low, high).positionInBand());
    }

    @Test
    void 문장_형식이_조금_달라도_읽고_이상한_밴드는_버린다() {
        OfferingPriceParser.OfferingPrice price = OfferingPriceParser.parse(
                "공모희망가액인 1,000원 ~ 9,000원 중 최저가액 기준 … 주당 확정 공모가액을 5,500원으로 결정하였습니다").orElseThrow();

        assertEquals(new BigDecimal("5500"), price.finalPrice());
        assertNull(price.bandLow(), "상단이 하단의 3배를 넘으면 잘못 읽은 것으로 본다");
    }
}
