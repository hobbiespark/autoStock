package com.autostock.backtest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 트랙 F 규칙 고정(docs/research/adr15_preregistration_20261114.md 4장) — 시점 규칙·F-Score·순위 규칙. */
class FactorStrategiesTest {

    private static final double NA = Double.NaN;

    @Test
    void 시점규칙_4월부터_전년_1에서3월은_전전년() {
        assertEquals(2014, FundamentalsTable.fiscalYearFor(LocalDate.of(2016, 3, 31)));
        assertEquals(2014, FundamentalsTable.fiscalYearFor(LocalDate.of(2016, 1, 29)));
        assertEquals(2015, FundamentalsTable.fiscalYearFor(LocalDate.of(2016, 4, 1)));
        assertEquals(2025, FundamentalsTable.fiscalYearFor(LocalDate.of(2026, 8, 31)));
    }

    @Test
    void FScore_9항목과_결측은_0점() {
        FundamentalsTable.Fundamentals perfect = f(100, 100, 30, 40, 50, 40, 20, 20, 5, 30, 10, 10,
                120, 100, 60, 45, 20, 10, 5, 15);
        assertEquals(9, perfect.fScore());

        FundamentalsTable.Fundamentals missing = f(100, NA, NA, NA, NA, NA, NA, NA, NA, NA, NA, NA,
                NA, NA, NA, NA, NA, 10, NA, NA);
        assertEquals(1, missing.fScore(), "ROA>0만 계산 가능, 나머지는 0점");
    }

    @Test
    void 유동항목이_없으면_금융업으로_본다() {
        assertTrue(f(100, 100, 90, 90, NA, NA, NA, NA, 5, 1, 10, 10, 10, 10, NA, NA, 3, 2, 2, 1).looksFinancial());
    }

    @Test
    void 마법공식은_EY순위와_ROC순위의_합이_작은_순() {
        // A: EY 높음·ROC 낮음, B: 둘 다 중간, C: EY 낮음·ROC 높음, D: 둘 다 최하
        Map<String, FundamentalsTable.Fundamentals> fy2015 = new LinkedHashMap<>();
        fy2015.put("A", operating(30, 100, 0, 1000));   // EY 30/1000, ROC 30/1000
        fy2015.put("B", operating(20, 100, 0, 100));    // EY 20/1000, ROC 20/100
        fy2015.put("C", operating(10, 100, 0, 20));     // EY 10/1000, ROC 10/20
        fy2015.put("D", operating(1, 100, 0, 1000));    // EY 1/1000,  ROC 1/1000
        fy2015.put("FIN", f(100, 100, 90, 90, NA, NA, NA, NA, 5, 1, 10, 10, 10, 10, NA, NA, 50, 2, 2, 1));
        FundamentalsTable table = new FundamentalsTable(Map.of(2015, fy2015));
        Map<String, Double> caps = Map.of("A", 900.0, "B", 900.0, "C", 900.0, "D", 900.0, "FIN", 10.0);

        List<String> picked = FactorStrategies.magicFormula(table, 3)
                .select(ctx(LocalDate.of(2016, 5, 31), caps), histories("A", "B", "C", "D", "FIN"));
        // 순위합: A 1+3=4, B 2+2=4, C 3+1=4, D 4+4=8 → 동점은 코드순, FIN은 금융업 제외
        assertEquals(List.of("A", "B", "C"), picked);
    }

    @Test
    void GPA는_매출총이익_나누기_자산_상위() {
        Map<String, FundamentalsTable.Fundamentals> fy = new LinkedHashMap<>();
        fy.put("LOW", withGp(10, 100));
        fy.put("HIGH", withGp(40, 100));
        fy.put("NOGP", withGp(NA, 100));
        FundamentalsTable table = new FundamentalsTable(Map.of(2015, fy));
        List<String> picked = FactorStrategies.grossProfitability(table, 5)
                .select(ctx(LocalDate.of(2016, 6, 30), Map.of()), histories("LOW", "HIGH", "NOGP"));
        assertEquals(List.of("HIGH", "LOW"), picked);
    }

    @Test
    void X3는_FScore로_거른_뒤_X1과_같은_모멘텀() {
        Map<String, FundamentalsTable.Fundamentals> fy = new LinkedHashMap<>();
        FundamentalsTable.Fundamentals good = f(100, 100, 30, 40, 50, 40, 20, 20, 5, 30, 10, 10,
                120, 100, 60, 45, 20, 10, 5, 15);
        FundamentalsTable.Fundamentals bad = f(100, 100, 50, 40, 40, 50, 20, 20, 5, 30, 12, 10,
                100, 120, 40, 60, -5, -10, 5, -15);
        fy.put("G1", good);
        fy.put("G2", good);
        fy.put("BAD", bad);
        FundamentalsTable table = new FundamentalsTable(Map.of(2015, fy));
        Map<String, double[]> h = new LinkedHashMap<>();
        h.put("G1", trend(1.0005));
        h.put("G2", trend(1.0010));
        h.put("BAD", trend(1.0050));   // 모멘텀은 가장 강하지만 F-Score 미달
        List<String> picked = FactorStrategies.fScoreMomentum(table, 7, 20).select(ctx(LocalDate.of(2016, 7, 29), Map.of()), h);
        assertEquals(List.of("G2", "G1"), picked);
        assertEquals(CrossSectionalStrategies.momentum12_1(20).select(Map.of("G1", h.get("G1"), "G2", h.get("G2"))), picked);
    }

    @Test
    void 맥락_없이_부르면_거절() {
        FundamentalsTable empty = new FundamentalsTable(Map.of());
        assertThrows(IllegalStateException.class, () -> FactorStrategies.grossProfitability(empty, 5).select(Map.of()));
    }

    @Test
    void CSV는_fs2만_읽고_빈칸은_NaN(@TempDir Path dir) throws IOException {
        String header = "corp_code,stock_code,fs_div,assets_cur,assets_prev,liab_cur,liab_prev,ca_cur,ca_prev,cl_cur,cl_prev,"
                + "cash_cur,cash_prev,ppe_cur,ppe_prev,capital_cur,capital_prev,rev_cur,rev_prev,gp_cur,gp_prev,"
                + "op_cur,op_prev,ni_cur,ni_prev,cfo_cur,cfo_prev\n";
        Files.writeString(dir.resolve("fs2_2015.csv"), header + "001,005930,CFS,100,90,40,30,50,45,20,25,5,4,30,28,10,10,120,100,60,50,20,15,10,8,,\n");
        Files.writeString(dir.resolve("fs_2015.csv"), "anything\n");
        FundamentalsTable table = FundamentalsTable.fromCsvDirectory(dir);
        FundamentalsTable.Fundamentals got = table.asOf(LocalDate.of(2016, 4, 29), "005930");
        assertEquals(40, got.liab(), 0.0);
        assertTrue(Double.isNaN(got.cfo()));
        assertNull(table.asOf(LocalDate.of(2016, 3, 31), "005930"), "3월 형성은 2014 회계연도 — 없음");
        assertEquals(Map.of(2015, 1), table.rowCountByYear());
    }

    // ── 시험 자료 ──

    private static FundamentalsTable.Fundamentals f(double assets, double assetsPrev, double liab, double liabPrev,
                                                     double ca, double caPrev, double cl, double clPrev,
                                                     double cash, double ppe, double capital, double capitalPrev,
                                                     double rev, double revPrev, double gp, double gpPrev,
                                                     double op, double ni, double niPrev, double cfo) {
        return new FundamentalsTable.Fundamentals(assets, assetsPrev, liab, liabPrev, ca, caPrev, cl, clPrev,
                cash, ppe, capital, capitalPrev, rev, revPrev, gp, gpPrev, op, ni, niPrev, cfo);
    }

    /** EV = 900 + 부채 100 − 현금 0 = 1000, 투하자본 = (유동 nwc 0) + 유형자산. */
    private static FundamentalsTable.Fundamentals operating(double op, double liab, double cash, double ppe) {
        return f(1000, 1000, liab, liab, 50, 50, 50, 50, cash, ppe, 10, 10, 500, 500, 100, 100, op, 1, 1, 1);
    }

    private static FundamentalsTable.Fundamentals withGp(double gp, double assets) {
        return f(assets, assets, 10, 10, 20, 20, 10, 10, 1, 5, 10, 10, 100, 100, gp, gp, 5, 3, 3, 2);
    }

    private static CrossSectionalStrategy.FormationContext ctx(LocalDate d, Map<String, Double> caps) {
        return new CrossSectionalStrategy.FormationContext(d, caps);
    }

    private static Map<String, double[]> histories(String... symbols) {
        Map<String, double[]> out = new LinkedHashMap<>();
        for (String s : symbols) {
            out.put(s, trend(1.0));
        }
        return out;
    }

    private static double[] trend(double dailyGrowth) {
        double[] c = new double[253];
        c[0] = 100;
        for (int i = 1; i < c.length; i++) {
            c[i] = c[i - 1] * dailyGrowth;
        }
        return c;
    }
}
