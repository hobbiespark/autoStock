package com.autostock.monitor;

import com.autostock.common.event.Fill;
import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.Side;
import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.Quantity;
import com.autostock.common.util.StockCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 피드 요약 문장 — 수량은 %d 포맷이라 값 객체를 그대로 넘기면 실행 중 예외가 난다(컴파일은 된다).
 */
class EventFeedTest {

    private static final Instant AT = Instant.parse("2026-09-30T01:00:00Z");

    private final EventFeed feed = new EventFeed();

    @Test
    void 주문요청_요약에_수량이_숫자로_찍힌다() {
        feed.on(new OrderRequest("20260930-C3-005930-BUY-001", "C3", new StockCode("005930"), Side.BUY,
                new Quantity(14), new BigDecimal("70000"), AT));

        assertEquals("[005930] BUY 14주 @ 70000 주문요청", feed.recent().get(0).summary());
    }

    @Test
    void 체결_요약에_수량과_주문번호가_찍힌다() {
        feed.on(new Fill("20260930-C3-005930-BUY-001", new BrokerOrderId("0119433"), new StockCode("005930"),
                Side.BUY, new Quantity(14), new BigDecimal("70000"), AT));

        assertEquals("[005930] BUY 14주 @ 70000 체결 (0119433)", feed.recent().get(0).summary());
    }
}
