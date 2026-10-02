package com.autostock.ipo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * [발행조건확정]증권신고서(지분증권)의 「(13) 수요예측 결과」에서 기관경쟁률과 의무보유확약비율을 읽는다
 * (2026-10-02 사용자 요구 "기관경쟁률·의무보유확약비율 자동 입력", aiDoc/ipo-demand-forecast.md).
 *
 * <ul>
 *   <li><b>기관경쟁률</b> — 「(가) 수요예측 참여 내역」 표의 "경쟁률" 행, "합계" 열. 기관 배정 주식수 대비 신청 수량의
 *       단순경쟁률로, 언론이 "기관 경쟁률 N대 1"로 옮기는 값이다.</li>
 *   <li><b>의무보유확약비율(수량 기준, 사용자 결정 2026-10-02)</b> — 「(다) 의무보유확약기간별 수요예측 참여내역」의
 *       "합계" 열 "수량": (6개월·3개월·1개월·15일 등 확약 행의 합) ÷ 합계 행. 언론의 "의무보유확약 비율 N%"와 같다
 *       (브릴스 21.75% 대조, 2026-10-02).</li>
 * </ul>
 *
 * <p>실측 형식(2026-09~10 공시 9건): 확약 표는 국내 기관(열 22개 또는 16개)과 외국 기관+합계(열 10개 또는 16개) 두 표로
 * 나뉜다. 둘째 표에 "구분" 열이 없는 경우(스팩)는 첫 표의 행 순서를 따른다. 합계 행 이름은 "합계"·"합 계"·"계"가 섞인다.
 *
 * <p>검산: 확약 합 + 미확약 = 합계, 그리고 확약 표의 합계 수량 = 참여 내역 표의 합계 수량. 하나라도 어긋나면 확약비율을
 * 내지 않는다 — 틀린 지표로 권고를 내는 것보다 비워 두고 수동 입력을 기다리는 편이 낫다.
 */
final class DemandForecastParser {

    private static final Logger log = LoggerFactory.getLogger(DemandForecastParser.class);

    private static final Pattern PERIOD_ROW = Pattern.compile("^\\d+(개월|일)확약$");
    private static final Pattern NUMBER = Pattern.compile("^[0-9][0-9,]*(\\.[0-9]+)?$");
    private static final Pattern DASH = Pattern.compile("^[-−–]$");
    /** 확약 머리글 뒤에서 확약 표를 찾는 범위(조각 수) — 단위 표·주석 표가 사이에 낀다. */
    private static final int LOCKUP_SEARCH_BLOCKS = 12;

    /**
     * 읽은 결과. 둘 중 하나만 읽힐 수 있다(검산 실패 등).
     *
     * @param competitionRate   기관경쟁률(N:1의 N), 못 읽으면 null
     * @param lockupCommitRate  의무보유확약비율(수량 기준, 0~1, 소수 넷째 자리), 못 읽으면 null
     * @param committedQuantity 확약 신청 수량 합(주) — 확약비율을 못 읽으면 0
     * @param totalQuantity     전체 신청 수량(주) — 확약비율을 못 읽으면 0
     */
    record DemandForecast(BigDecimal competitionRate, BigDecimal lockupCommitRate,
                          long committedQuantity, long totalQuantity) {
    }

    private enum RowKind { COMMITTED, UNCOMMITTED, TOTAL }

    private record Participation(BigDecimal competitionRate, Long totalQuantity) {
    }

    private record LockupTable(boolean labeled, List<List<String>> headers, List<RowKind> kinds, List<List<String>> data) {
    }

    private DemandForecastParser() {
    }

    /** 문서에 수요예측 결과가 없거나(유상증자·기재정정 등) 둘 다 못 읽으면 빈 값. */
    static Optional<DemandForecast> parse(String xml) {
        List<DartDocument.Block> blocks = DartDocument.blocks(xml);
        Participation participation = participation(blocks);
        long[] lockup = lockup(blocks);

        BigDecimal lockupRate = null;
        long committed = 0;
        long total = 0;
        if (lockup != null) {
            Long participationTotal = participation == null ? null : participation.totalQuantity();
            if (participationTotal != null && participationTotal != lockup[1]) {
                log.warn("수요예측 확약 표 합계({}주)가 참여 내역 합계({}주)와 다름 — 확약비율을 비워 둔다", lockup[1], participationTotal);
            } else {
                committed = lockup[0];
                total = lockup[1];
                lockupRate = BigDecimal.valueOf(committed).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
            }
        }
        BigDecimal competition = participation == null ? null : participation.competitionRate();
        if (competition == null && lockupRate == null) {
            return Optional.empty();
        }
        return Optional.of(new DemandForecast(competition, lockupRate, committed, total));
    }

    /** 「(가) 수요예측 참여 내역」 — "경쟁률" 행이 있는 첫 표의 합계 열. */
    private static Participation participation(List<DartDocument.Block> blocks) {
        for (DartDocument.Block block : blocks) {
            if (!(block instanceof DartDocument.Table table)) {
                continue;
            }
            List<List<String>> rows = nonEmpty(table.rows());
            int firstData = -1;
            List<String> rateRow = null;
            List<String> quantityRow = null;
            for (int i = 0; i < rows.size(); i++) {
                String label = DartDocument.compact(rows.get(i).get(0));
                boolean isRate = label.startsWith("경쟁률");
                if (firstData < 0 && (isRate || label.equals("건수") || label.equals("수량"))) {
                    firstData = i;
                }
                if (isRate && rateRow == null) {
                    rateRow = rows.get(i);
                } else if (label.equals("수량") && quantityRow == null) {
                    quantityRow = rows.get(i);
                }
            }
            if (rateRow == null) {
                continue;
            }
            int totalColumn = lastColumnNamed(rows.subList(0, firstData), "합계", 1);
            BigDecimal rate = number(cellAt(rateRow, totalColumn));
            if (rate == null || rate.signum() <= 0) {
                continue;
            }
            BigDecimal quantity = quantityRow == null ? null : number(cellAt(quantityRow, totalColumn));
            return new Participation(rate.setScale(2, RoundingMode.HALF_UP),
                    quantity == null || quantity.signum() <= 0 ? null : quantity.longValue());
        }
        return null;
    }

    /** 「(다) 의무보유확약기간별 수요예측 참여내역」 — {확약 합, 합계}. 못 읽으면 null. */
    private static long[] lockup(List<DartDocument.Block> blocks) {
        int heading = -1;
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i) instanceof DartDocument.Paragraph p && DartDocument.compact(p.text()).contains("의무보유확약기간별")) {
                heading = i;
                break;
            }
        }
        if (heading < 0) {
            return null;
        }
        List<LockupTable> tables = new ArrayList<>();
        List<RowKind> labels = null;
        for (int i = heading + 1; i < Math.min(blocks.size(), heading + 1 + LOCKUP_SEARCH_BLOCKS); i++) {
            DartDocument.Block block = blocks.get(i);
            if (block instanceof DartDocument.Paragraph p) {
                if (endsLockupSection(DartDocument.compact(p.text()))) {
                    break;
                }
                continue;
            }
            List<List<String>> rows = nonEmpty(((DartDocument.Table) block).rows());
            LockupTable labeled = labeledTable(rows);
            if (labeled != null) {
                labels = labeled.kinds();
                tables.add(labeled);
            } else if (labels != null) {
                LockupTable continued = continuationTable(rows, labels);
                if (continued != null) {
                    tables.add(continued);
                }
            }
        }
        if (tables.isEmpty()) {
            return null;
        }
        Map<RowKind, Long> quantity = totalColumnQuantity(tables);
        if (quantity == null) {
            quantity = summedQuantity(tables);
        }
        if (quantity == null) {
            log.warn("수요예측 확약 표에서 수량 열을 찾지 못함 — 확약비율을 비워 둔다");
            return null;
        }
        long committed = quantity.getOrDefault(RowKind.COMMITTED, 0L);
        long uncommitted = quantity.getOrDefault(RowKind.UNCOMMITTED, 0L);
        long total = quantity.getOrDefault(RowKind.TOTAL, committed + uncommitted);
        if (committed + uncommitted != total || total <= 0) {
            log.warn("수요예측 확약 표 검산 실패(확약 {} + 미확약 {} ≠ 합계 {}) — 확약비율을 비워 둔다", committed, uncommitted, total);
            return null;
        }
        return new long[] {committed, total};
    }

    private static boolean endsLockupSection(String paragraph) {
        return paragraph.startsWith("(라)") || paragraph.startsWith("(마)")
                || paragraph.contains("확정공모가액의결정") || paragraph.contains("주당확정");
    }

    /** "구분" 열에 확약 기간이 적힌 표. */
    private static LockupTable labeledTable(List<List<String>> rows) {
        List<RowKind> kinds = new ArrayList<>();
        List<List<String>> data = new ArrayList<>();
        int firstData = -1;
        for (int i = 0; i < rows.size(); i++) {
            RowKind kind = rowKind(rows.get(i).get(0));
            if (kind != null) {
                if (firstData < 0) {
                    firstData = i;
                }
                kinds.add(kind);
                data.add(rows.get(i));
            }
        }
        return firstData < 0 ? null : new LockupTable(true, rows.subList(0, firstData), kinds, data);
    }

    /** "구분" 열 없이 숫자만 이어지는 둘째 표 — 앞 표의 행 순서를 그대로 따른다(실측: 스팩 공시). */
    private static LockupTable continuationTable(List<List<String>> rows, List<RowKind> labels) {
        List<List<String>> data = new ArrayList<>();
        int firstData = -1;
        for (int i = 0; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.size() >= 2 && row.stream().allMatch(DemandForecastParser::isNumberCell)) {
                if (firstData < 0) {
                    firstData = i;
                }
                data.add(row);
            }
        }
        if (data.size() != labels.size()) {
            return null;
        }
        return new LockupTable(false, rows.subList(0, firstData), labels, data);
    }

    /** 머리글이 "합계"인 열 묶음의 "수량" 열 — 없으면 null. */
    private static Map<RowKind, Long> totalColumnQuantity(List<LockupTable> tables) {
        for (LockupTable table : tables) {
            int width = table.data().stream().mapToInt(List::size).max().orElse(0);
            int first = table.labeled() ? 1 : 0;
            List<Integer> group = new ArrayList<>();
            for (int c = first; c < width; c++) {
                if (headerPath(table.headers(), c).contains("합계")) {
                    group.add(c);
                }
            }
            if (group.isEmpty()) {
                continue;
            }
            Integer quantityColumn = group.stream().filter(c -> headerPath(table.headers(), c).contains("수량"))
                    .findFirst().orElse(group.size() >= 2 ? group.get(1) : null); // 머리글이 없으면 건수·수량·신청가격 순서
            if (quantityColumn == null) {
                continue;
            }
            return quantities(table, List.of(quantityColumn));
        }
        return null;
    }

    /** 합계 열이 없으면 모든 표의 "수량" 열을 더한다. */
    private static Map<RowKind, Long> summedQuantity(List<LockupTable> tables) {
        Map<RowKind, Long> sum = new EnumMap<>(RowKind.class);
        for (LockupTable table : tables) {
            int width = table.data().stream().mapToInt(List::size).max().orElse(0);
            List<Integer> columns = new ArrayList<>();
            for (int c = table.labeled() ? 1 : 0; c < width; c++) {
                if (headerPath(table.headers(), c).endsWith("수량")) {
                    columns.add(c);
                }
            }
            if (columns.isEmpty()) {
                return null;
            }
            Map<RowKind, Long> part = quantities(table, columns);
            if (part == null) {
                return null;
            }
            part.forEach((kind, q) -> sum.merge(kind, q, Long::sum));
        }
        return sum;
    }

    private static Map<RowKind, Long> quantities(LockupTable table, List<Integer> columns) {
        Map<RowKind, Long> result = new EnumMap<>(RowKind.class);
        for (int i = 0; i < table.data().size(); i++) {
            for (int column : columns) {
                BigDecimal value = number(cellAt(table.data().get(i), column));
                if (value == null) {
                    return null;
                }
                result.merge(table.kinds().get(i), value.longValue(), Long::sum);
            }
        }
        return result;
    }

    private static RowKind rowKind(String cell) {
        String label = DartDocument.compact(cell);
        if (PERIOD_ROW.matcher(label).matches()) {
            return RowKind.COMMITTED;
        }
        if (label.equals("미확약")) {
            return RowKind.UNCOMMITTED;
        }
        if (label.equals("합계") || label.equals("계") || label.equals("총계")) {
            return RowKind.TOTAL;
        }
        return null;
    }

    private static String headerPath(List<List<String>> headers, int column) {
        StringBuilder path = new StringBuilder();
        for (List<String> header : headers) {
            if (column < header.size()) {
                path.append(DartDocument.compact(header.get(column)));
            }
        }
        return path.toString();
    }

    private static int lastColumnNamed(List<List<String>> headers, String name, int fromColumn) {
        int found = -1;
        for (List<String> header : headers) {
            for (int c = fromColumn; c < header.size(); c++) {
                if (DartDocument.compact(header.get(c)).equals(name)) {
                    found = Math.max(found, c);
                }
            }
        }
        return found;
    }

    /** 열 번호가 없거나(-1) 행이 짧으면 마지막 칸. */
    private static String cellAt(List<String> row, int column) {
        return column >= 0 && column < row.size() ? row.get(column) : row.get(row.size() - 1);
    }

    private static boolean isNumberCell(String cell) {
        String s = DartDocument.compact(cell);
        return s.isEmpty() || DASH.matcher(s).matches() || NUMBER.matcher(s).matches();
    }

    /** "1,270.51" → 1270.51, "-"·빈칸 → 0, "1,270.51:1" → 1270.51, 숫자가 아니면 null. */
    private static BigDecimal number(String cell) {
        String s = DartDocument.compact(cell);
        int colon = s.indexOf(':');
        if (colon >= 0) {
            s = s.substring(0, colon);
        }
        if (s.isEmpty() || DASH.matcher(s).matches()) {
            return BigDecimal.ZERO;
        }
        if (!NUMBER.matcher(s).matches()) {
            return null;
        }
        return new BigDecimal(s.replace(",", ""));
    }

    private static List<List<String>> nonEmpty(List<List<String>> rows) {
        return rows.stream().filter(r -> !r.isEmpty()).toList();
    }
}
