package com.autostock.monitor;

import com.autostock.common.event.Fill;
import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.Side;
import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.Price;
import com.autostock.common.util.Quantity;
import com.autostock.common.util.StockCode;
import com.autostock.common.util.StockNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 피드 요약 문장 — 수량은 %d 포맷이라 값 객체를 그대로 넘기면 실행 중 예외가 난다(컴파일은 된다).
 * 종목은 이름과 코드를 함께 쓴다(2026-10-02, aiDoc/stock-names.md).
 */
class EventFeedTest {

    private static final Instant AT = Instant.parse("2026-09-30T01:00:00Z");

    private final EventFeed feed = new EventFeed();

    @BeforeEach
    void names() {
        StockNames.learn("005930", "삼성전자", StockNames.Source.KIWOOM);
    }

    @Test
    void 주문요청_요약에_수량이_숫자로_찍힌다() {
        feed.on(new OrderRequest("20260930-C3-005930-BUY-001", "C3", new StockCode("005930"), Side.BUY,
                new Quantity(14), new Price(new BigDecimal("70000")), AT));

        assertEquals("[삼성전자(005930)] BUY 14주 @ 70000 주문요청", feed.recent().get(0).summary());
    }

    @Test
    void 체결_요약에_수량과_주문번호가_찍힌다() {
        feed.on(new Fill("20260930-C3-005930-BUY-001", new BrokerOrderId("0119433"), new StockCode("005930"),
                Side.BUY, new Quantity(14), new Price(new BigDecimal("70000")), AT));

        assertEquals("[삼성전자(005930)] BUY 14주 @ 70000 체결 (0119433)", feed.recent().get(0).summary());
    }

    @Test
    void 이름을_아직_모르는_종목도_코드와_함께_미확인으로_찍힌다() {
        feed.on(new Fill("20260930-C3-069500-BUY-001", new BrokerOrderId("0119434"), new StockCode("069500"),
                Side.BUY, new Quantity(3), new Price(new BigDecimal("108000")), AT));

        assertEquals("[종목명 미확인(069500)] BUY 3주 @ 108000 체결 (0119434)", feed.recent().get(0).summary());
    }
}
