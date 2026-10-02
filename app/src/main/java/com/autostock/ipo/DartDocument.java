package com.autostock.ipo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DART 공시 원본(OpenDART {@code document.xml}이 주는 dart4.xsd 형식 XML) 읽기 — 표({@code TABLE})는 행·열 격자로,
 * 표 사이의 문단({@code P·TITLE·SPAN})은 글로, 문서 순서대로 꺼낸다(2026-10-02, aiDoc/ipo-demand-forecast.md).
 *
 * <p>정규식 기반의 관대한 읽기다. 공시 XML은 주관사·회사가 만든 표가 섞여 엄격한 XML 파서가 거부할 수 있고,
 * 여기서 필요한 것은 표의 글자뿐이다. 셀의 {@code COLSPAN}·{@code ROWSPAN}은 펼쳐서 같은 글자를 채운다 —
 * 머리글 "합계"가 세 칸(건수·수량·신청가격)을 덮으면 세 열 모두 "합계"가 된다.
 *
 * <p>한계: 표 안의 표(중첩 TABLE)는 바깥 표가 안쪽 표의 끝에서 잘린다. 수요예측 결과 표는 중첩되지 않는다(실측 9건).
 */
final class DartDocument {

    private static final int FLAGS = Pattern.DOTALL | Pattern.CASE_INSENSITIVE;
    private static final Pattern TABLE = Pattern.compile("<TABLE\\b[^>]*>(.*?)</TABLE>", FLAGS);
    private static final Pattern PARAGRAPH = Pattern.compile("<(P|TITLE|SPAN)\\b[^>]*>(.*?)</\\1>", FLAGS);
    private static final Pattern ROW = Pattern.compile("<TR\\b[^>]*>(.*?)</TR>", FLAGS);
    private static final Pattern CELL = Pattern.compile("<(TD|TH|TE|TU)\\b([^>]*)>(.*?)</\\1>", FLAGS);
    private static final Pattern COLSPAN = Pattern.compile("COLSPAN\\s*=\\s*\"(\\d+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROWSPAN = Pattern.compile("ROWSPAN\\s*=\\s*\"(\\d+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern BREAK = Pattern.compile("<BR\\s*/?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9A-Fa-f]+);");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    /** 한 셀이 펼쳐질 수 있는 최대 칸 — 깨진 속성값이 거대한 격자를 만들지 않게 한다. */
    private static final int MAX_SPAN = 64;

    /** 문서 순서대로 늘어놓은 조각 — 문단 또는 표. */
    sealed interface Block permits Paragraph, Table {
    }

    /** 표 밖의 문단 한 개(태그를 걷어낸 글). */
    record Paragraph(String text) implements Block {
    }

    /** 표 한 개 — 행마다 펼친 셀 글자. 행 길이는 서로 다를 수 있다. */
    record Table(List<List<String>> rows) implements Block {
    }

    private DartDocument() {
    }

    /** 문서를 문단과 표로 나눈다. 빈 문단은 버린다. */
    static List<Block> blocks(String xml) {
        List<Block> blocks = new ArrayList<>();
        if (xml == null || xml.isEmpty()) {
            return blocks;
        }
        Matcher table = TABLE.matcher(xml);
        int position = 0;
        while (table.find()) {
            addParagraphs(xml.substring(position, table.start()), blocks);
            blocks.add(new Table(grid(table.group(1))));
            position = table.end();
        }
        addParagraphs(xml.substring(position), blocks);
        return blocks;
    }

    /** 태그를 걷어낸 본문 전체 — 공모 유형을 핵심어로 가를 때 쓴다. */
    static String plainText(String xml) {
        return xml == null ? "" : text(xml);
    }

    /** 공백을 모두 지운 글 — "합 계"와 "합계", "15일 확약"과 "15일확약"을 같게 본다. */
    static String compact(String s) {
        return s == null ? "" : SPACES.matcher(s).replaceAll("");
    }

    private static void addParagraphs(String segment, List<Block> blocks) {
        Matcher paragraph = PARAGRAPH.matcher(segment);
        while (paragraph.find()) {
            String text = text(paragraph.group(2));
            if (!text.isEmpty()) {
                blocks.add(new Paragraph(text));
            }
        }
    }

    private static List<List<String>> grid(String tableBody) {
        List<List<String>> rows = new ArrayList<>();
        Map<Long, String> carried = new HashMap<>(); // (행, 열) → 위 행의 ROWSPAN이 내려준 글자
        Matcher row = ROW.matcher(tableBody);
        int r = 0;
        while (row.find()) {
            List<String> cells = new ArrayList<>();
            Matcher cell = CELL.matcher(row.group(1));
            int c = 0;
            boolean hasCell = cell.find();
            while (hasCell || carried.containsKey(key(r, c))) {
                String fromAbove = carried.remove(key(r, c));
                if (fromAbove != null) {
                    cells.add(fromAbove);
                    c++;
                    continue;
                }
                String text = text(cell.group(3));
                int colspan = span(COLSPAN, cell.group(2));
                int rowspan = span(ROWSPAN, cell.group(2));
                for (int k = 0; k < colspan; k++) {
                    cells.add(text);
                    for (int down = 1; down < rowspan; down++) {
                        carried.put(key(r + down, c + k), text);
                    }
                }
                c += colspan;
                hasCell = cell.find();
            }
            rows.add(cells);
            r++;
        }
        return rows;
    }

    private static long key(int row, int column) {
        return ((long) row << 32) | (column & 0xffffffffL);
    }

    private static int span(Pattern attribute, String attributes) {
        Matcher m = attribute.matcher(attributes);
        if (!m.find()) {
            return 1;
        }
        try {
            return Math.max(1, Math.min(MAX_SPAN, Integer.parseInt(m.group(1))));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String text(String fragment) {
        String s = BREAK.matcher(fragment).replaceAll(" ");
        s = TAG.matcher(s).replaceAll("");
        s = unescape(s);
        return SPACES.matcher(s.replace('\u00a0', ' ')).replaceAll(" ").trim();
    }

    private static String unescape(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        Matcher m = NUMERIC_ENTITY.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String replacement;
            try {
                int codePoint = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
                replacement = new String(Character.toChars(codePoint));
            } catch (IllegalArgumentException e) {
                replacement = m.group();
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString()
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }
}
