package com.autostock.risk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class KrxTickSizeTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            // 실측 거부 케이스(RC4003): 20만 원 이상 500원 단위
            "259250, 259500",
            "259000, 259000",
            "259249, 259000",
            // 가격대별 눈금
            "1999, 1999",
            "2003, 2005",
            "12345, 12350",
            "12344, 12340",
            "33333, 33350",
            "123456, 123500",
            "700123, 700000",
            "700500, 701000",
            // 눈금 경계 통과
            "199950, 200000",
            "199949, 199900",
            "499999, 500000",
            // 소수 입력(체결가 평균 등)
            "259000.0000, 259000",
            "258750.5, 259000",
    })
    void align(String input, String expected) {
        assertThat(KrxTickSize.align(new BigDecimal(input))).isEqualByComparingTo(new BigDecimal(expected));
    }

    @Test
    void alignedResultIsAlwaysAligned() {
        for (long p = 1; p < 1_200_000; p += 7) {
            BigDecimal aligned = KrxTickSize.align(BigDecimal.valueOf(p));
            assertThat(KrxTickSize.isAligned(aligned)).as("price %d → %s", p, aligned).isTrue();
        }
    }

    @Test
    void nullAndNonPositivePassThrough() {
        assertThat(KrxTickSize.align(null)).isNull();
        assertThat(KrxTickSize.align(BigDecimal.ZERO)).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
