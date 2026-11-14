package com.autostock.backtest;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** ADR-15 규칙 고정(docs/research/adr15_preregistration_20261114.md 2장): w = min(1, σ후보/σ지수), 나머지 현금 0%. */
class VolatilityMatchedBenchmarkTest {

    @Test
    void 후보가_지수_절반_노출이면_w는_0_5() {
        double[] bench = noise(300, 1L);
        double[] half = new double[bench.length];
        for (int i = 0; i < bench.length; i++) {
            half[i] = 0.5 * bench[i];
        }
        assertEquals(0.5, VolatilityMatchedBenchmark.weight(half, bench), 1e-12);

        double[] series = VolatilityMatchedBenchmark.series(half, bench);
        assertArrayEquals(half, series, 1e-15);
    }

    @Test
    void 후보가_지수보다_변동성이_크면_w는_1을_넘지_않는다() {
        double[] bench = noise(300, 2L);
        double[] doubled = new double[bench.length];
        for (int i = 0; i < bench.length; i++) {
            doubled[i] = 2.0 * bench[i];
        }
        assertEquals(1.0, VolatilityMatchedBenchmark.weight(doubled, bench), 0.0);
        assertArrayEquals(bench, VolatilityMatchedBenchmark.series(doubled, bench), 0.0);
    }

    @Test
    void 지수_변동성이_0이면_w는_1() {
        double[] flat = new double[100];
        assertEquals(1.0, VolatilityMatchedBenchmark.weight(noise(100, 3L), flat), 0.0);
    }

    @Test
    void 길이가_다르면_거절() {
        assertThrows(IllegalArgumentException.class,
                () -> VolatilityMatchedBenchmark.weight(noise(10, 4L), noise(11, 5L)));
    }

    private static double[] noise(int n, long seed) {
        Random rnd = new Random(seed);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = 0.0005 + 0.01 * rnd.nextGaussian();
        }
        return out;
    }
}
