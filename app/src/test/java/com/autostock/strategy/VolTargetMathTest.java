package com.autostock.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * VolTargetMath 검증 — backtest.VolatilityTargetingStrategyTest와 동일한 수작업 유도값을
 * 순수 함수 단위에서 재확인한다.
 *
 * <h2>수작업 검증값 유도 — ±2% 교대 수익률 20개</h2>
 * 가격을 1.02, 0.98을 번갈아 곱하면 일간수익률이 정확히 +0.02, -0.02가 교대로 20개
 * 나온다(10개씩). 평균=0, 표준편차=0.02, realizedVol = 0.02 × sqrt(252) ≈ 0.31749016,
 * fraction = min(1, 0.20 / 0.31749016) ≈ 0.629940788348712.
 */
class VolTargetMathTest {

    /** 21개 종가(20개 수익률) — magnitude만큼 교대로 등락하는 목록을 만든다. */
    private List<BigDecimal> alternating(double magnitude) {
        List<BigDecimal> closes = new ArrayList<>();
        BigDecimal price = new BigDecimal("10000");
        closes.add(price);
        BigDecimal up = BigDecimal.valueOf(1 + magnitude);
        BigDecimal down = BigDecimal.valueOf(1 - magnitude);
        for (int i = 1; i <= 20; i++) {
            price = price.multiply(i % 2 == 1 ? up : down);
            closes.add(price);
        }
        return closes;
    }

    @Test
    void 이십봉_수익률_표준편차로부터_fraction을_계산한다_수작업값_비교() {
        double fraction = VolTargetMath.fraction(alternating(0.02), 0.20);
        assertEquals(0.629940788348712, fraction, 1e-6);
    }

    @Test
    void 관측치가_이십일개_미만이면_fraction은_1_0이다() {
        List<BigDecimal> few = alternating(0.02).subList(0, 10); // 10개(9개 수익률) — 21개 미만
        double fraction = VolTargetMath.fraction(few, 0.20);
        assertEquals(1.0, fraction, 1e-12);
    }

    @Test
    void null_목록은_fraction_1_0() {
        assertEquals(1.0, VolTargetMath.fraction(null, 0.20), 1e-12);
    }

    @Test
    void 실현변동성이_목표보다_낮으면_fraction은_1_0으로_캡된다() {
        double fraction = VolTargetMath.fraction(alternating(0.001), 0.20);
        assertEquals(1.0, fraction, 1e-9);
    }

    @Test
    void 변동이_전혀_없으면_realizedVol_0이라_fraction은_1_0이다() {
        List<BigDecimal> flat = new ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            flat.add(new BigDecimal("10000"));
        }
        assertEquals(1.0, VolTargetMath.fraction(flat, 0.20), 1e-12);
    }

    @Test
    void 목록_맨_앞의_추가_과거_데이터는_판단에_영향을_주지_않는다() {
        List<BigDecimal> withExtra = new ArrayList<>();
        withExtra.add(new BigDecimal("55555"));
        withExtra.add(new BigDecimal("66666"));
        withExtra.addAll(alternating(0.02));

        double fraction = VolTargetMath.fraction(withExtra, 0.20);
        assertEquals(0.629940788348712, fraction, 1e-6, "마지막 21개만 사용하므로 앞쪽 여분 데이터는 영향 없어야 함");
    }
}
