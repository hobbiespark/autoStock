package com.autostock.ipo;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 상장 직후 유통가능물량 읽기 — 2025-04~2026-10 공모주 85곳 투자설명서·증권신고서에서 본 표현 그대로(문장은 원문에서 잘라 옴).
 */
class FloatSharesParserTest {

    private static FloatSharesParser.FloatShares parse(String text) {
        return FloatSharesParser.parse(text).orElseThrow(() -> new AssertionError("못 읽음: " + text));
    }

    @Test
    void 시점별_유통가능_주식수_표의_상장일_행() {
        // 덕양에너젠 [기재정정]투자설명서
        FloatSharesParser.FloatShares fs = parse("[상장 후 유통가능 주식수 현황] (기준일 : 증권신고서 제출일 현재) (단위 : 주, %) "
                + "구분 주식수 유통가능 주식수 비율 상장일 유통가능 7,944,478 32.05% 상장 후 1개월 뒤 유통가능 7,950,433 32.07% "
                + "상장 후 2개월 뒤 유통가능 7,950,433 32.07%");
        assertEquals(new BigDecimal("0.3205"), fs.ratio());
        assertEquals(7_944_478L, fs.floatShares());
        assertEquals(24_787_763L, fs.listedShares(), "상장예정주식수 문장이 없으면 유통 주식수 ÷ 비율");
    }

    @Test
    void 주식수_괄호_비율_뒤_상장_직후() {
        // 엠에스바이오·마키나락스·아이엠바이오로직스(괄호 안 "공모 후")·폴레드("공모 후 기준 약")
        assertEquals(new BigDecimal("0.2333"), parse("상기 의무보유 수량을 제외한 주식수 6,347,310주(23.33%)는 상장 직후 시장에서 "
                + "유통가능한 물량입니다.").ratio());
        assertEquals(new BigDecimal("0.1031"), parse("상기의 의무보유 수량을 제외한 주식수 1,522,883주(공모 후 10.31% )는 상장 직후 "
                + "시장에서 유통가능한 물량에 해당합니다.").ratio());
        assertEquals(new BigDecimal("0.2794"), parse("상기 의무보유 수량을 제외한 주식 7,036,504주(공모 후 기준 약 27.94%)는 상장 직후 "
                + "시장에서 유통가능한 물량에 해당합니다.").ratio());
        assertEquals(new BigDecimal("0.3527"), parse("주식수 4,851,103주 (공모 후 35.27%) 는 상장 직후 시장에서 유통가능한 물량").ratio());
        assertEquals(new BigDecimal("0.2202"), parse("상기의 의무보유물량을 제외한 주식수 8,482,164주(상장 후 지분율 22.02%)는 상장 직후 "
                + "시장에서 유통가능한 물량으로").ratio());
    }

    @Test
    void 주식수_뒤_상장예정주식수_기준_비율() {
        // 삼양컴텍·세미파이브
        FloatSharesParser.FloatShares fs = parse("상기의 의무보유 수량을 제외한 주식수 15,306,040주는 상장 직후 시장에서 유통가능한 "
                + "물량이며, 상장예정주식수 기준으로 37.14% 에 해당합니다.");
        assertEquals(new BigDecimal("0.3714"), fs.ratio());
        assertEquals(15_306_040L, fs.floatShares());
    }

    @Test
    void 상장예정주식수_중_비율에_해당하는_주식수() {
        // 디티에스·브릴스 — 상장예정주식수를 함께 읽는다(시가총액 = 상장예정주식수 × 확정가)
        FloatSharesParser.FloatShares dts = parse("금번 공모예정주식을 포함한 당사의 상장예정주식수 14,859,445주 중 약 15.15%에 해당하는 "
                + "2,250,787주는 상장 직후 유통가능물량입니다.");
        assertEquals(new BigDecimal("0.1515"), dts.ratio());
        assertEquals(14_859_445L, dts.listedShares());
        FloatSharesParser.FloatShares brils = parse("당사의 상장예정주식수(금번 공모주식 및 의무인수분 포함) 11,363,649주 중 26.42%에 해당하는 "
                + "3,002,063주는 상장 직후 유통가능 물량에 해당 하며");
        assertEquals(new BigDecimal("0.2642"), brils.ratio());
        assertEquals(11_363_649L, brils.listedShares());
    }

    @Test
    void 비율만_적힌_문장() {
        // 엘리스그룹 — 공모가 산정 근거 문단
        FloatSharesParser.FloatShares fs = parse("(2) 상장 직후 유통가능주식 비율이 20.77% 로 제한적인 수준인 점 등을 반영하였습니다.");
        assertEquals(new BigDecimal("0.2077"), fs.ratio());
        assertNull(fs.floatShares());
    }

    @Test
    void 정정_전_문장이_섞여도_본문에_많이_나온_비율을_쓴다() {
        // 니어스랩 [기재정정]투자설명서 — 앞쪽 정정 표에 "정정 전" 37.19%, 본문(요약·투자위험)에 37.15%가 두 번
        String text = "(주10) 정정 전 가. 상장 이후 유통물량 출회에 따른 위험 ... 제외한 주식수 2,146,145주(37.19%) 는 상장 직후 시장에서 "
                + "유통가능한 물량에 해당 합니다. ... 상기의 의무보유 수량을 제외한 주식수 2,146,145주( 37.15% ) 는 상장 직후 시장에서 "
                + "유통가능한 물량에 해당합니다. ... 상기의 의무보유 수량을 제외한 주식수 2,146,145주( 37.15% ) 는 상장 직후 시장에서 "
                + "유통가능한 물량에 해당합니다.";
        assertEquals(new BigDecimal("0.3715"), parse(text).ratio());
    }

    @Test
    void 문장이_없거나_비율이_말이_안_되면_빈_값() {
        assertTrue(FloatSharesParser.parse("유통가능 물량에 관한 별도 기재 없음").isEmpty());
        assertTrue(FloatSharesParser.parse("").isEmpty());
        assertTrue(FloatSharesParser.parse(null).isEmpty());
        assertTrue(FloatSharesParser.parse("주식수 100주(0.5%)는 상장 직후 시장에서 유통가능한 물량").isEmpty(), "1% 미만은 버린다");
    }
}
