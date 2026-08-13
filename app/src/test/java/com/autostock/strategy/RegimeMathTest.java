package com.autostock.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RegimeMath 검증 — backtest.MarketRegimeTest와 동일한 수작업 시나리오를 순수 함수
 * 단위에서 재확인한다(200봉 미만 중립, SMA200/전일종가 비교 판정).
 */
class RegimeMathTest {

    private List<BigDecimal> flat200Then(String lastClose) {
        List<BigDecimal> closes = new ArrayList<>(Collections.nCopies(199, new BigDecimal("10000")));
        closes.add(new BigDecimal(lastClose));
        return closes;
    }

    @Test
    void 이백개_미만이면_중립_ON() {
        List<BigDecimal> closes = new ArrayList<>(Collections.nCopies(199, new BigDecimal("10000")));
        assertTrue(RegimeMath.isOn(closes), "200개 미만이면 판단 근거 부족으로 ON(중립)이어야 함");
    }

    @Test
    void 빈_목록도_중립_ON() {
        assertTrue(RegimeMath.isOn(List.of()));
    }

    @Test
    void 전일종가가_SMA200_초과면_ON() {
        // 199개 10000 + 마지막 11000 → SMA200 = (199*10000 + 11000)/200 = 10005, 전일종가=11000 > 10005
        List<BigDecimal> closes = flat200Then("11000");
        assertTrue(RegimeMath.isOn(closes), "전일 종가(11000) > SMA200(10005)이므로 ON이어야 함");
    }

    @Test
    void 전일종가가_SMA200_이하면_OFF() {
        // 199개 10000 + 마지막 9000 → SMA200 = (199*10000 + 9000)/200 = 9995, 전일종가=9000 < 9995
        List<BigDecimal> closes = flat200Then("9000");
        assertFalse(RegimeMath.isOn(closes), "전일 종가(9000) < SMA200(9995)이므로 OFF여야 함");
    }

    @Test
    void 정확히_이백개일때도_판정된다() {
        List<BigDecimal> closes = flat200Then("11000");
        assertTrue(closes.size() == 200);
        assertTrue(RegimeMath.isOn(closes));
    }

    @Test
    void 목록_맨_앞의_추가_과거_데이터는_판단에_영향을_주지_않는다() {
        // 마지막 200개만 사용하므로, 앞쪽에 더 오래된 데이터가 얼마나 있든 결과는 같아야 한다.
        List<BigDecimal> withExtra = new ArrayList<>(Collections.nCopies(50, new BigDecimal("99999")));
        withExtra.addAll(flat200Then("11000"));
        assertTrue(RegimeMath.isOn(withExtra), "맨 앞 여분 데이터와 무관하게 마지막 200개 기준으로 ON이어야 함");
    }
}
