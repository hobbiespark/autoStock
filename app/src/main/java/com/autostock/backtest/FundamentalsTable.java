package com.autostock.backtest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 트랙 F의 사업보고서 재무 표 — {@code data/fscore/fs2_{회계연도}.csv}(scripts/fetch_fscore_financials.py v2).
 *
 * <p><b>시점 규칙(룩어헤드 방지, ADR-15 사전 선언 4장):</b> 형성일 d에 쓰는 회계연도는 d가 4월 이후면 d.year−1,
 * 1~3월이면 d.year−2. 사업보고서 법정 제출기한(결산 후 90일, 3월 말)이 지나야 그 해 숫자를 안다고 본다.
 * 값이 없는 계정은 NaN이다.
 */
public final class FundamentalsTable {

    /** 한 회사·한 회계연도의 사업보고서 숫자(원). 당기(cur)·전기(prev)는 같은 보고서의 두 열이다. */
    public record Fundamentals(double assets, double assetsPrev, double liab, double liabPrev,
                               double ca, double caPrev, double cl, double clPrev,
                               double cash, double ppe, double capital, double capitalPrev,
                               double rev, double revPrev, double gp, double gpPrev,
                               double op, double ni, double niPrev, double cfo) {

        /** 유동자산·유동부채를 보고하지 않은 회사 = 금융업(은행·보험·증권의 IFRS 재무상태표 특징)으로 본다. */
        public boolean looksFinancial() {
            return Double.isNaN(ca) || Double.isNaN(cl);
        }

        /**
         * Piotroski(2000) F-Score 9항목. 자료가 없거나 분모가 0 이하인 항목은 0점(보수적). 자산은 기말값.
         */
        public int fScore() {
            int s = 0;
            double roa = ratio(ni, assets);
            double roaPrev = ratio(niPrev, assetsPrev);
            s += point(roa > 0);
            s += point(ratio(cfo, assets) > 0);
            s += point(roa - roaPrev > 0);
            s += point(cfo > ni);
            s += point(ratio(liab, assets) < ratio(liabPrev, assetsPrev));
            s += point(ratio(ca, cl) > ratio(caPrev, clPrev));
            s += point(capital <= capitalPrev);
            s += point(ratio(gp, rev) > ratio(gpPrev, revPrev));
            s += point(ratio(rev, assets) > ratio(revPrev, assetsPrev));
            return s;
        }

        private static int point(boolean condition) {
            return condition ? 1 : 0; // NaN 비교는 모두 false → 0점
        }

        private static double ratio(double a, double b) {
            return b > 0 ? a / b : Double.NaN;
        }
    }

    private final Map<Integer, Map<String, Fundamentals>> byYear;

    public FundamentalsTable(Map<Integer, Map<String, Fundamentals>> byYear) {
        this.byYear = new TreeMap<>(byYear);
    }

    /** 형성일 d에 쓸 회계연도(4월 이후 d.year−1, 1~3월 d.year−2). */
    public static int fiscalYearFor(LocalDate formationDate) {
        return formationDate.getMonthValue() >= 4 ? formationDate.getYear() - 1 : formationDate.getYear() - 2;
    }

    /** 형성일 기준으로 쓸 수 있는 그 종목의 재무. 없으면 null. */
    public Fundamentals asOf(LocalDate formationDate, String stockCode) {
        Map<String, Fundamentals> year = byYear.get(fiscalYearFor(formationDate));
        return year == null ? null : year.get(stockCode);
    }

    public Map<Integer, Integer> rowCountByYear() {
        Map<Integer, Integer> out = new TreeMap<>();
        byYear.forEach((y, m) -> out.put(y, m.size()));
        return out;
    }

    /** {@code fs2_{year}.csv} 전부를 읽는다. v1({@code fs_{year}.csv}, 계정 매칭 결함)은 읽지 않는다. */
    public static FundamentalsTable fromCsvDirectory(Path dir) {
        Pattern name = Pattern.compile("fs2_(\\d{4})\\.csv");
        Map<Integer, Map<String, Fundamentals>> byYear = new HashMap<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "fs2_*.csv")) {
            for (Path file : files) {
                Matcher m = name.matcher(file.getFileName().toString());
                if (!m.matches()) {
                    continue;
                }
                byYear.put(Integer.parseInt(m.group(1)), readYear(file));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new FundamentalsTable(byYear);
    }

    private static Map<String, Fundamentals> readYear(Path file) throws IOException {
        Map<String, Fundamentals> out = new HashMap<>();
        try (BufferedReader r = Files.newBufferedReader(file)) {
            String header = r.readLine();
            if (header == null) {
                return out;
            }
            String[] cols = header.split(",", -1);
            Map<String, Integer> idx = new HashMap<>();
            for (int i = 0; i < cols.length; i++) {
                idx.put(cols[i].trim(), i);
            }
            String line;
            while ((line = r.readLine()) != null) {
                String[] v = line.split(",", -1);
                String stock = v[idx.get("stock_code")].trim();
                if (stock.isEmpty()) {
                    continue;
                }
                out.put(stock, new Fundamentals(
                        num(v, idx, "assets_cur"), num(v, idx, "assets_prev"),
                        num(v, idx, "liab_cur"), num(v, idx, "liab_prev"),
                        num(v, idx, "ca_cur"), num(v, idx, "ca_prev"),
                        num(v, idx, "cl_cur"), num(v, idx, "cl_prev"),
                        num(v, idx, "cash_cur"), num(v, idx, "ppe_cur"),
                        num(v, idx, "capital_cur"), num(v, idx, "capital_prev"),
                        num(v, idx, "rev_cur"), num(v, idx, "rev_prev"),
                        num(v, idx, "gp_cur"), num(v, idx, "gp_prev"),
                        num(v, idx, "op_cur"), num(v, idx, "ni_cur"), num(v, idx, "ni_prev"),
                        num(v, idx, "cfo_cur")));
            }
        }
        return out;
    }

    private static double num(String[] v, Map<String, Integer> idx, String col) {
        Integer i = idx.get(col);
        if (i == null || i >= v.length || v[i].isBlank()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(v[i].trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
