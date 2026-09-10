package com.autostock.macrointel;

/**
 * DART 주요사항보고서 중 이 배치가 감시하는 4개 유형 (PLAN.md ADR-14, 트랙 G1 — 유상증자·CB·
 * BW·EB 발행은 기존 주주 지분 희석·오버행 리스크로 장기 저성과가 실증된 이벤트다
 * (Loughran &amp; Ritter 1995)).
 *
 * <p>{@code reportNameFragment}는 DART {@code list.json}의 {@code report_nm} 필드에 포함되는
 * 한국어 유형명이다 — 실측(2026-09-11, docs/measured/dart_majorreport_list_20260911.json)
 * 결과 정정 공시는 {@code [기재정정]}/{@code [첨부정정]}/{@code [첨부추가]} 같은 접두어가
 * 붙으므로 {@code contains}로 매칭한다({@code ipo.DartClient.REPORT_NAME_FILTER}와 동일 관례).
 * "자기전환사채매도결정"·"자기전환사채만기전취득결정"(발행이 아니라 회사가 보유한 CB를
 * 매매/상환하는 결정)은 이 문자열들과 겹치지 않아 오탐되지 않음을 실측으로 확인했다.
 */
public enum DisclosureType {
    /** 유상증자 결정 — 지분 희석. */
    PAID_IN_CAPITAL_INCREASE("유상증자결정)", "유상증자 결정"),
    /** 전환사채권(CB) 발행 결정 — 전환 시 지분 희석 + 오버행. */
    CONVERTIBLE_BOND("전환사채권발행결정)", "전환사채(CB) 발행 결정"),
    /** 신주인수권부사채권(BW) 발행 결정. */
    BOND_WITH_WARRANT("신주인수권부사채권발행결정)", "신주인수권부사채(BW) 발행 결정"),
    /** 교환사채권(EB) 발행 결정. */
    EXCHANGEABLE_BOND("교환사채권발행결정)", "교환사채(EB) 발행 결정");

    private final String reportNameFragment;
    private final String label;

    DisclosureType(String reportNameFragment, String label) {
        this.reportNameFragment = reportNameFragment;
        this.label = label;
    }

    /** 사람이 읽는 한국어 라벨(텔레그램 알림 문구용). */
    public String label() {
        return label;
    }

    /** report_nm에 이 유형이 포함돼 있는지. */
    public boolean matches(String reportName) {
        return reportName != null && reportName.contains(reportNameFragment);
    }

    /** report_nm으로부터 유형을 판별한다 — 일치하는 게 없으면 null(관심 대상 아님, 스킵). */
    public static DisclosureType fromReportName(String reportName) {
        for (DisclosureType type : values()) {
            if (type.matches(reportName)) {
                return type;
            }
        }
        return null;
    }
}
