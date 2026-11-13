package com.autostock.fx;

/**
 * 적립 대상 통화. 환율 단위는 은행 앱 표시와 같다 — 달러는 원/1달러, 엔은 원/100엔(설계 3장).
 *
 * <p>ECOS 항목코드는 통계표 {@code 731Y001}(주요국 통화의 대원화 환율, 일별)의 것이다. 실측 2026-11-13(VALUNIVO 869, ECOS 공개 시험 키로
 * {@code StatisticItemList} 조회): {@code 0000001} "원/미국달러(매매기준율)", {@code 0000002} "원/일본엔(100엔)".
 */
public enum FxCurrency {
    USD("원/1달러", "0000001"),
    JPY("원/100엔", "0000002");

    /** ECOS 통계표 — 주요국 통화의 대원화 환율(일별). */
    public static final String ECOS_STAT_CODE = "731Y001";

    private final String unit;
    private final String ecosItemCode;

    FxCurrency(String unit, String ecosItemCode) {
        this.unit = unit;
        this.ecosItemCode = ecosItemCode;
    }

    /** 사람에게 보이는 환율 단위. */
    public String unit() {
        return unit;
    }

    /** ECOS {@code 731Y001}의 항목코드. */
    public String ecosItemCode() {
        return ecosItemCode;
    }
}
