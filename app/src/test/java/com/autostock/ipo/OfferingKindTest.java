package com.autostock.ipo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 공모주와 상장사 유상증자 가르기 — 2026-10-02 실측(estkRs.json 38개 회사, 공시 원본 14건) 값으로 확인한다
 * (aiDoc/ipo-demand-forecast.md).
 */
class OfferingKindTest {

    @Test
    void 주요정보의_모집방법이_일반공모이고_배정기준일과_주요사항보고서가_없으면_공모주다() {
        // 진코스텍·네오사피엔스·엘리스그룹 등: slmthn=일반공모, asstd·rpt_rcpn="-"(→ null)
        assertEquals(OfferingKind.IPO, OfferingKind.fromOfferingInfo("일반공모", null, null));
    }

    @Test
    void 주주배정이_들어가거나_주요사항보고서가_있으면_유상증자다() {
        // 뷰노·삼성바이오로직스·큐리언트·한울반도체: 주주배정후 실권주 일반공모, 이렘·아이에이·SK디앤디: 주주배정
        assertEquals(OfferingKind.RIGHTS,
                OfferingKind.fromOfferingInfo("주주배정후 실권주 일반공모", "20260929000430", "2026년 10월 20일"));
        assertEquals(OfferingKind.RIGHTS, OfferingKind.fromOfferingInfo("주주배정", "20260923000500", "2026년 10월 13일"));
        // 일반공모라도 유상증자결정 주요사항보고서가 있으면 이미 상장한 회사의 증자다
        assertEquals(OfferingKind.RIGHTS, OfferingKind.fromOfferingInfo("일반공모", "20260101000001", null));
    }

    @Test
    void 모집방법이_없으면_판정하지_않는다() {
        assertNull(OfferingKind.fromOfferingInfo(null, null, null));
        assertNull(OfferingKind.fromOfferingInfo("기타", null, null));
    }

    @Test
    void 공시_본문으로_가른다() {
        assertEquals(OfferingKind.RIGHTS,
                OfferingKind.fromDocumentText(DartDocument.plainText(DartFixtures.xml("20260914000188.zip")))); // 툴젠
        assertEquals(OfferingKind.IPO,
                OfferingKind.fromDocumentText(DartDocument.plainText(DartFixtures.xml("20260909000307.zip")))); // 네오사피엔스
        assertNull(OfferingKind.fromDocumentText("정정사유 : 기재사항 정정"));
    }
}
