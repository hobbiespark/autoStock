package com.autostock.ipo;

/**
 * 증권신고서(지분증권) 공모의 종류 — 신규 상장 공모(공모주)인지, 이미 상장한 회사의 유상증자인지
 * (2026-10-02, aiDoc/ipo-demand-forecast.md).
 *
 * <p>배경: DART 발행공시 목록(list.json)의 "증권신고서(지분증권)"에는 공모주와 상장사 유상증자가 섞여 있어, 삼성바이오로직스·
 * 뷰노·한울반도체 같은 유상증자가 공모주 딜로 들어와 화면·청약 알림에 나왔다. 목록의 {@code corp_cls}(시장 구분)는 조회
 * 시점 값이라 막 상장한 공모주도 K(코스닥)로 나와 쓸 수 없다(실측 2026-10-02: 네오사피엔스·브릴스 등).
 *
 * <p>판정 근거(실측 2026-10-02, 38개 회사):
 * <ul>
 *   <li>증권신고서 주요정보(estkRs.json) — 공모주는 모집방법 {@code slmthn}이 "일반공모"이고 배정기준일 {@code asstd}·
 *       주요사항보고서 접수번호 {@code rpt_rcpn}이 비어 있다. 유상증자는 모집방법에 "주주배정"(주주배정후 실권주 일반공모 등)이
 *       들어가고 배정기준일·주요사항보고서(유상증자결정)가 있다.</li>
 *   <li>주요정보가 비는 경우(회사에 따라 조회되지 않음) 공시 본문 — 유상증자는 "신주배정기준일"·"구주주"가 나오고,
 *       공모주는 "상장예비심사"·"신규상장"·"수요예측"이 나온다.</li>
 * </ul>
 */
public enum OfferingKind {

    /** 신규 상장 공모(공모주) — 코넥스 기업의 이전상장 공모, 스팩 포함. */
    IPO,

    /** 상장사 유상증자(주주배정 등) — 공모주 화면·권고·알림 대상이 아니다. */
    RIGHTS;

    /** 주요정보(estkRs.json)로 판정 — 모집방법이 없으면 판정하지 않는다(null). */
    static OfferingKind fromOfferingInfo(String offeringMethod, String majorReportRceptNo, String allotmentBaseDate) {
        if (offeringMethod == null || offeringMethod.isBlank()) {
            return null;
        }
        if (offeringMethod.contains("주주") || majorReportRceptNo != null || allotmentBaseDate != null) {
            return RIGHTS;
        }
        return offeringMethod.contains("일반공모") ? IPO : null;
    }

    /** 공시 본문으로 판정 — 어느 쪽 핵심어도 없으면(짧은 기재정정 등) 판정하지 않는다(null). */
    static OfferingKind fromDocumentText(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        if (text.contains("신주배정기준일") || text.contains("구주주")) {
            return RIGHTS;
        }
        if (text.contains("상장예비심사") || text.contains("신규상장") || text.contains("수요예측")) {
            return IPO;
        }
        return null;
    }
}
