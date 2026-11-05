package com.autostock.ipo;

import java.math.BigDecimal;
import java.util.List;

/**
 * 공모주 상장일 실거래 매도가 쓰는 실전 증권사 창구(포트). 구현은 {@link KiwoomIpoLiveBroker}(키움 실전 서버).
 * 매도·잔고·미체결 셋만 있다 — 매수 경로는 없다.
 */
interface IpoLiveBroker {

    /** 지정가 매도. 결과는 접수·거부·불명 셋 중 하나이고 예외를 던지지 않는다. */
    SellResult sellLimit(String symbol, long quantity, BigDecimal limitPrice);

    /** 그 종목의 실전 매도 가능 수량(조회 실패면 예외). */
    long sellableQuantity(String symbol);

    /** 그 종목의 실전 미체결 주문 번호들(조회 실패면 예외). */
    List<String> outstandingOrderNos(String symbol);

    /** 주문 결과. */
    enum Outcome {
        /** 접수됨(주문번호 있음). */
        ACCEPTED,
        /** 증권사가 거부했다. */
        REJECTED,
        /** 응답 없음·주문번호 없음 — 접수됐을 수도 있다. 다시 보내지 않는다. */
        UNKNOWN
    }

    /** 주문 결과와 주문번호(접수 때만)·사유. */
    record SellResult(Outcome outcome, String orderNo, String reason) {
    }
}
