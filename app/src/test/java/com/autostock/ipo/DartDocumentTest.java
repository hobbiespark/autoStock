package com.autostock.ipo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** 공시 원본 XML 읽기 — 문단·표 순서, COLSPAN·ROWSPAN 펼치기, 글자 정리. */
class DartDocumentTest {

    @Test
    void 문단과_표를_문서_순서대로_꺼낸다() {
        String xml = """
                <BODY><P>(가) 수요예측 참여 내역</P>
                <TABLE><TBODY><TR><TD>구분</TD><TD>합계</TD></TR></TBODY></TABLE>
                <P USERMARK="B">(다) 의무보유확약기간별 <SPAN>수요예측</SPAN> 참여내역</P></BODY>
                """;

        List<DartDocument.Block> blocks = DartDocument.blocks(xml);

        assertEquals(3, blocks.size());
        assertEquals("(가) 수요예측 참여 내역", ((DartDocument.Paragraph) blocks.get(0)).text());
        assertInstanceOf(DartDocument.Table.class, blocks.get(1));
        assertEquals("(다) 의무보유확약기간별 수요예측 참여내역", ((DartDocument.Paragraph) blocks.get(2)).text());
    }

    @Test
    void COLSPAN과_ROWSPAN을_펼쳐_같은_글자를_채운다() {
        String xml = """
                <TABLE><THEAD>
                <TR><TH ROWSPAN="2">구분</TH><TH COLSPAN="3">합 계</TH></TR>
                <TR><TH>건수</TH><TH>수량</TH><TH>신청가격</TH></TR>
                </THEAD><TBODY>
                <TR><TD>15일 확약</TD><TD>46</TD><TD>29,569,000</TD><TD>13,974</TD></TR>
                </TBODY></TABLE>
                """;

        List<List<String>> rows = ((DartDocument.Table) DartDocument.blocks(xml).get(0)).rows();

        assertEquals(List.of("구분", "합 계", "합 계", "합 계"), rows.get(0));
        assertEquals(List.of("구분", "건수", "수량", "신청가격"), rows.get(1));
        assertEquals(List.of("15일 확약", "46", "29,569,000", "13,974"), rows.get(2));
    }

    @Test
    void 태그와_문자_참조를_걷고_공백을_하나로_줄인다() {
        String xml = "<TABLE><TR><TD>연기금,<BR/>은행 &amp; 보험&nbsp;&#40;고유&#x29;</TD></TR></TABLE>";

        List<List<String>> rows = ((DartDocument.Table) DartDocument.blocks(xml).get(0)).rows();

        assertEquals("연기금, 은행 & 보험 (고유)", rows.get(0).get(0));
        assertEquals("합계", DartDocument.compact(" 합 \n계 "));
    }
}
