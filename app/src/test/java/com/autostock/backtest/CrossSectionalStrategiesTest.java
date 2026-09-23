package com.autostock.backtest;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * X0/X0r 진단 베이스라인 전략의 계약 — X0는 후보 전체를 돌려주고, X0r은 같은 입력에 같은 시드면 같은
 * 결과(결정적), 다른 시드면 다른 결과(대조군으로서 분산이 있음)를 돌려준다.
 */
class CrossSectionalStrategiesTest {

    private static Map<String, double[]> histories(int n) {
        Map<String, double[]> m = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            double[] c = new double[260];
            for (int d = 0; d < c.length; d++) {
                c[d] = 100 + i + d * 0.01;
            }
            m.put(String.format("%06d", i), c);
        }
        return m;
    }

    @Test
    void X0는_후보_전체를_종목코드_순으로_돌려준다() {
        CrossSectionalStrategy s = CrossSectionalStrategies.equalWeightAll();
        List<String> picked = s.select(histories(50));

        assertEquals(50, picked.size());
        assertEquals("000000", picked.get(0));
        assertEquals("000049", picked.get(49));
        assertEquals(253, s.minHistoryDays()); // X1(252+1)과 동일 — 후보 집합을 맞춘다
    }

    @Test
    void X0r은_같은_시드면_결정적이고_다른_시드면_다른_집합이다() {
        Map<String, double[]> h = histories(200);
        List<String> a1 = CrossSectionalStrategies.randomPick(20, 1L).select(h);
        List<String> a2 = CrossSectionalStrategies.randomPick(20, 1L).select(h);
        List<String> b = CrossSectionalStrategies.randomPick(20, 2L).select(h);

        assertEquals(20, a1.size());
        assertEquals(a1, a2);
        assertNotEquals(a1, b);
        assertTrue(h.keySet().containsAll(a1));
    }

    @Test
    void X0r은_후보가_N보다_적으면_전부_돌려준다() {
        assertEquals(5, CrossSectionalStrategies.randomPick(20, 1L).select(histories(5)).size());
    }
}
