package com.autostock.marketdata;

import com.autostock.common.event.MarketTick;
import com.autostock.common.event.OrderNotice;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * RealMessageParser 단위테스트 — 실제 WS 연결 없이 REAL data[] 원소 샘플 JSON으로 검증.
 * 샘플 값은 scripts/ws_probe.py 실측 전까지는 문서 기반 추정치다.
 */
class RealMessageParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 시세체결_0B_는_MarketTick으로_변환() throws Exception {
        JsonNode data = mapper.readTree("""
                {"type":"0B","item":"005930","values":{"10":"+70100","15":"12345"}}
                """);

        Object result = RealMessageParser.parse(data);

        MarketTick tick = assertInstanceOf(MarketTick.class, result);
        assertEquals("005930", tick.symbol());
        assertEquals(new BigDecimal("70100"), tick.price());
        assertEquals(12345L, tick.volume());
        assertEquals(MarketTick.Source.LIVE, tick.source());
    }

    @Test
    void 주문체결통보_00_은_OrderNotice로_변환() throws Exception {
        JsonNode data = mapper.readTree("""
                {"type":"00","item":"","values":{
                    "9203":"0000123","9001":"005930","913":"체결",
                    "900":"10","911":"10","910":"+70100","906":"2"
                }}
                """);

        Object result = RealMessageParser.parse(data);

        OrderNotice notice = assertInstanceOf(OrderNotice.class, result);
        assertEquals("0000123", notice.brokerOrderId());
        assertEquals("005930", notice.symbol());
        assertEquals("체결", notice.status());
        assertEquals(10L, notice.filledQuantity());
        assertEquals(new BigDecimal("70100"), notice.fillPrice());
        assertEquals("00", notice.rawType());
    }

    @Test
    void 주문번호_없는_통보는_null() throws Exception {
        JsonNode data = mapper.readTree("""
                {"type":"00","item":"","values":{"913":"접수"}}
                """);

        assertNull(RealMessageParser.parse(data));
    }

    @Test
    void 알수없는_type은_null() throws Exception {
        JsonNode data = mapper.readTree("""
                {"type":"XX","item":"005930","values":{}}
                """);

        assertNull(RealMessageParser.parse(data));
    }
}
