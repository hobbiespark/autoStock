package com.autostock.kiwoom;

/**
 * TR(api-id) 중앙 관리 — 오타로 인한 엉뚱한 TR 실행 방지 (PLAN 3절).
 * 키움 REST API 문서의 api-id를 여기에만 정의한다. 문자열 직접 사용 금지.
 */
public enum TrId {

    // ===== 인증 =====
    TOKEN_ISSUE("au10001", "접근토큰 발급"),

    // ===== 조회 =====
    ACCOUNT_BALANCE("kt00018", "계좌평가잔고내역"),
    STOCK_PRICE("ka10001", "주식기본정보/현재가"),
    DAILY_CHART("ka10081", "일봉차트"),
    MINUTE_CHART("ka10080", "분봉차트"),
    OUTSTANDING_ORDERS("ka10075", "미체결"),
    STOCK_ORDERBOOK("ka10004", "주식호가"),

    // ===== 주문 =====
    ORDER_BUY("kt10000", "주식 매수주문"),
    ORDER_SELL("kt10001", "주식 매도주문"),
    ORDER_MODIFY("kt10002", "주식 정정주문"),
    ORDER_CANCEL("kt10003", "주식 취소주문");

    private final String apiId;
    private final String description;

    TrId(String apiId, String description) {
        this.apiId = apiId;
        this.description = description;
    }

    public String apiId() {
        return apiId;
    }

    public String description() {
        return description;
    }
}
