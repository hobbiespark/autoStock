package com.autostock.ipo;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [발행조건확정] 수요예측 결과 읽기 — 실제 공시 원본(DartFixtures)으로 기관경쟁률·의무보유확약비율(수량 기준)을 확인한다
 * (aiDoc/ipo-demand-forecast.md). 기대값은 공시 표의 숫자를 손으로 옮긴 것이다.
 */
class DemandForecastParserTest {

    @Test
    void 네오사피엔스_경쟁률과_수량_기준_확약비율을_읽는다() {
        // (가) 경쟁률 합계 219.56, (다) 합계 열: 15일 확약 29,569,000 / 합계 329,341,000 = 8.98%
        DemandForecastParser.DemandForecast f = parse("20260909000307.zip");

        assertEquals(new BigDecimal("219.56"), f.competitionRate());
        assertEquals(new BigDecimal("0.0898"), f.lockupCommitRate());
        assertEquals(29_569_000L, f.committedQuantity());
        assertEquals(329_341_000L, f.totalQuantity());
    }

    @Test
    void 브릴스_값은_언론_보도와_같다() {
        // 언론: 기관 경쟁률 1187.74대 1, 의무보유확약 비율 21.75%(2026-09-16 한국데이터경제신문)
        // 확약 합 9,029,000+5,471,000+36,284,000+135,191,000 = 185,975,000 / 855,171,000
        DemandForecastParser.DemandForecast f = parse("20260916000234-demand-forecast.xml");

        assertEquals(new BigDecimal("1187.74"), f.competitionRate());
        assertEquals(new BigDecimal("0.2175"), f.lockupCommitRate());
        assertEquals(185_975_000L, f.committedQuantity());
        assertEquals(855_171_000L, f.totalQuantity());
    }

    @Test
    void 스팩처럼_둘째_확약_표에_구분_열이_없으면_첫_표의_행_순서를_따른다() {
        // 둘째 표 행: 미확약 / 15일 / 1개월 / 3개월(1건 5,000주) / 6개월 / 합계 — 합계 열 "수량" 머리글 없음(건수·수량·신청가격 순서)
        DemandForecastParser.DemandForecast f = parse("20260909000276.zip");

        assertEquals(new BigDecimal("1270.51"), f.competitionRate());
        assertEquals(5_000L, f.committedQuantity());
        assertEquals(6_670_172_000L, f.totalQuantity());
        assertEquals(new BigDecimal("0.0000"), f.lockupCommitRate());
    }

    @Test
    void 합계_행_이름이_계여도_읽는다() {
        // 진코스텍: 6개월 4,992,000 + 3개월 17,169,000 + 1개월 6,064,000 + 15일 8,865,000 = 37,090,000 / 701,380,000
        DemandForecastParser.DemandForecast f = parse("20260928000402.zip");

        assertEquals(new BigDecimal("1097.62"), f.competitionRate());
        assertEquals(37_090_000L, f.committedQuantity());
        assertEquals(701_380_000L, f.totalQuantity());
        assertEquals(new BigDecimal("0.0529"), f.lockupCommitRate());
    }

    @Test
    void 상장사_유상증자_확정_신고서에는_수요예측_결과가_없다() {
        assertTrue(DemandForecastParser.parse(DartFixtures.xml("20260914000188.zip")).isEmpty());
    }

    @Test
    void 수요예측_표가_없는_기재정정_확정_신고서는_빈_값이다() {
        assertTrue(DemandForecastParser.parse(DartFixtures.xml("20261001000586.zip")).isEmpty());
    }

    @Test
    void 확약_표_검산이_어긋나면_확약비율은_비우고_경쟁률만_돌려준다() {
        // 합계 수량을 1주 바꾸면 확약 합 + 미확약 ≠ 합계 — 틀린 확약비율로 권고하지 않는다
        String xml = DartFixtures.xml("20260909000307.zip").replace("329,341,000", "329,341,001");
        Optional<DemandForecastParser.DemandForecast> f = DemandForecastParser.parse(xml);

        assertTrue(f.isPresent());
        assertEquals(new BigDecimal("219.56"), f.get().competitionRate());
        assertNull(f.get().lockupCommitRate());
    }

    @Test
    void 빈_문서는_빈_값이다() {
        assertTrue(DemandForecastParser.parse("").isEmpty());
        assertTrue(DemandForecastParser.parse(null).isEmpty());
    }

    private static DemandForecastParser.DemandForecast parse(String fixture) {
        return DemandForecastParser.parse(DartFixtures.xml(fixture)).orElseThrow();
    }
}
