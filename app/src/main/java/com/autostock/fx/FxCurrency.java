package com.autostock.fx;

/**
 * 적립 대상 통화. 환율 단위는 은행 앱 표시와 같다 — 달러는 원/1달러, 엔은 원/100엔(설계 3장).
 */
public enum FxCurrency {
    USD("원/1달러"),
    JPY("원/100엔");

    private final String unit;

    FxCurrency(String unit) {
        this.unit = unit;
    }

    /** 사람에게 보이는 환율 단위. */
    public String unit() {
        return unit;
    }
}
