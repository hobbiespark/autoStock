package com.autostock.market;

import com.autostock.common.event.MarketTick;
import com.autostock.common.event.OrderNotice;
import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.MarketConstants;
import com.autostock.common.util.Price;
import com.autostock.common.util.StockCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * RealMessageParser 단위테스트 — 실제 WS 연결 없이 REAL data[] 원소 샘플 JSON으로 검증.
 *
 * <p>"실측 전문 재생" 테스트들은 docs/measured/ws_probe_20260911_intraday.txt에서 캡처한
 * 005930 모의투자 장중 WS 응답을 마스킹 없이 그대로 사용한다(값 원본 그대로).
 */
class RealMessageParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 시세체결_0B_는_MarketTick으로_변환() throws Exception {
        JsonNode data = mapper.readTree("""
                {"type":"0B","item":"005930","values":{"10":"+70100","15":"12345"}}
                """);

        Object result = RealMessageParser.parse(data, Clock.systemUTC());

        MarketTick tick = assertInstanceOf(MarketTick.class, result);
        assertEquals(new StockCode("005930"), tick.symbol());
        assertEquals(new Price(new BigDecimal("70100")), tick.price());
        assertEquals(12345L, tick.volume());
        assertEquals(MarketTick.Source.LIVE, tick.source());
    }

    @Test
    void 종목코드_형식이_틀린_시세는_null() throws Exception {
        for (String item : new String[]{"", "5930", "005930_AL"}) {
            JsonNode data = mapper.readTree("""
                    {"type":"0B","item":"%s","values":{"10":"+70100","15":"1"}}
                    """.formatted(item));

            assertNull(RealMessageParser.parse(data, Clock.systemUTC()), item);
        }
    }

    @Test
    void 체결시간은_UTC와_날짜가_다른_시각에도_KST_당일_기준으로_변환된다() throws Exception {
        // UTC 09-29 23:30 = KST 09-30 08:30 — "오늘"을 UTC로 잡으면 하루 어긋난다(§9 날짜 경계)
        Clock clock = Clock.fixed(Instant.parse("2026-09-29T23:30:00Z"), java.time.ZoneOffset.UTC);
        JsonNode data = mapper.readTree("""
                {"type":"0B","item":"005930","values":{"10":"+70100","15":"1","20":"090000"}}
                """);

        MarketTick tick = assertInstanceOf(MarketTick.class, RealMessageParser.parse(data, clock));

        assertEquals(Instant.parse("2026-09-30T00:00:00Z"), tick.timestamp()); // KST 09-30 09:00
    }

    @Test
    void 체결시간이_없으면_주입된_시계의_현재_시각을_쓴다() throws Exception {
        Instant now = Instant.parse("2026-09-30T01:02:03Z");
        JsonNode data = mapper.readTree("""
                {"type":"0B","item":"005930","values":{"10":"+70100","15":"1"}}
                """);

        MarketTick tick = assertInstanceOf(MarketTick.class,
                RealMessageParser.parse(data, Clock.fixed(now, java.time.ZoneOffset.UTC)));

        assertEquals(now, tick.timestamp());
    }

    @Test
    void 주문체결통보_00_은_OrderNotice로_변환() throws Exception {
        JsonNode data = mapper.readTree("""
                {"type":"00","item":"","values":{
                    "9203":"0000123","9001":"005930","913":"체결",
                    "900":"10","911":"10","910":"+70100","906":"2"
                }}
                """);

        Object result = RealMessageParser.parse(data, Clock.systemUTC());

        OrderNotice notice = assertInstanceOf(OrderNotice.class, result);
        assertEquals(new BrokerOrderId("0000123"), notice.brokerOrderId());
        assertEquals(new StockCode("005930"), notice.symbol());
        assertEquals("체결", notice.status());
        assertEquals(10L, notice.filledQuantity());
        assertEquals(new Price(new BigDecimal("70100")), notice.fillPrice());
        assertEquals("00", notice.rawType());
    }

    @Test
    void 주문번호_없는_통보는_null() throws Exception {
        JsonNode data = mapper.readTree("""
                {"type":"00","item":"","values":{"913":"접수"}}
                """);

        assertNull(RealMessageParser.parse(data, Clock.systemUTC()));
    }

    @Test
    void 주문번호_형식이_틀린_통보는_null() throws Exception {
        JsonNode data = mapper.readTree("""
                {"type":"00","item":"","values":{"9203":"01 19","9001":"005930","913":"체결"}}
                """);

        assertNull(RealMessageParser.parse(data, Clock.systemUTC()));
    }

    @Test
    void 주문번호_앞뒤_공백은_벗긴다() throws Exception {
        JsonNode data = mapper.readTree("""
                {"type":"00","item":"","values":{"9203":" 0119433 ","9001":"005930","913":"접수"}}
                """);

        OrderNotice notice = assertInstanceOf(OrderNotice.class, RealMessageParser.parse(data, Clock.systemUTC()));
        assertEquals(new BrokerOrderId("0119433"), notice.brokerOrderId());
    }

    @Test
    void 종목코드가_비거나_형식이_틀려도_통보는_살리고_종목만_null() throws Exception {
        // 체결 통보를 잃으면 포지션이 틀어진다 — 소비자(OrderNoticeHandler)는 DB 주문의 종목코드를 쓴다
        for (String symbol : new String[]{"", "A5930"}) {
            JsonNode data = mapper.readTree("""
                    {"type":"00","item":"","values":{"9203":"0119433","9001":"%s","913":"체결","911":"1","910":"258000"}}
                    """.formatted(symbol));

            OrderNotice notice = assertInstanceOf(OrderNotice.class, RealMessageParser.parse(data, Clock.systemUTC()));
            assertNull(notice.symbol(), symbol);
            assertEquals(1L, notice.filledQuantity());
        }
    }

    @Test
    void 가격이_0인_시세는_버린다() throws Exception {
        for (String price : new String[]{"0", "+0", "-0"}) {
            JsonNode data = mapper.readTree("""
                    {"type":"0B","item":"005930","values":{"10":"%s","15":"1"}}
                    """.formatted(price));

            assertNull(RealMessageParser.parse(data, Clock.systemUTC()), price);
        }
    }

    @Test
    void 체결가가_0이거나_형식이_틀린_통보는_살리고_체결가만_null() throws Exception {
        // 체결 통보를 잃으면 포지션·손익이 틀어진다 — 체결가가 없으면 OrderNoticeHandler가 지정가로 근사한다(조각 17)
        for (String price : new String[]{"0", "+0", "abc"}) {
            JsonNode data = mapper.readTree("""
                    {"type":"00","values":{"9203":"0119433","9001":"005930","913":"체결","911":"1","910":"%s","902":"0"}}
                    """.formatted(price));

            OrderNotice notice = assertInstanceOf(OrderNotice.class, RealMessageParser.parse(data, Clock.systemUTC()), price);
            assertNull(notice.fillPrice(), price);
            assertEquals(1L, notice.filledQuantity(), price);
        }
    }

    @Test
    void 알수없는_type은_null() throws Exception {
        JsonNode data = mapper.readTree("""
                {"type":"XX","item":"005930","values":{}}
                """);

        assertNull(RealMessageParser.parse(data, Clock.systemUTC()));
    }

    // ── 실측 전문 재생 (docs/measured/ws_probe_20260911_intraday.txt, 마스킹 없음) ──

    @Test
    void 실측_전문_재생_0B_시세체결() throws Exception {
        // 원본 11:56:18.761 수신분(005930) — 매도 주도 체결(FID 15 = "-19")
        JsonNode data = mapper.readTree("""
                {"values":{"20":"115618","10":"-257750","11":"-11250","12":"-4.18","27":"-258000","28":"-257500","15":"-19","13":"7198927","14":"1860588","16":"-258000","17":"-261000","18":"-257000","25":"5","26":"-15318148","29":"-4167722708811","30":"-31.97","31":"0.12","32":"595","228":"89.62","311":"15068783","290":"2","691":"0","567":"000000","568":"000000","851":"10379","1890":"090016","1891":"053039","1892":"114556","1030":"3645181","1031":"3266718","1032":"-47.26","1071":"63318","1072":"96320","1313":"-4897","1315":"-19","1316":" 0","1314":"-19","1497":"103100","1498":"82480","620":"258453","732":"1294","852":"1035","9081":"KRX"},"type":"0B","name":"주식체결","item":"005930"}
                """);

        Object result = RealMessageParser.parse(data, Clock.systemUTC());

        MarketTick tick = assertInstanceOf(MarketTick.class, result);
        assertEquals(new StockCode("005930"), tick.symbol());
        assertEquals(new Price(new BigDecimal("257750")), tick.price());
        assertEquals(19L, tick.volume()); // FID 15 "-19" → 부호 벗긴 크기
        Instant expectedTimestamp = LocalDate.now(MarketConstants.KST)
                .atTime(11, 56, 18).atZone(MarketConstants.KST).toInstant();
        assertEquals(expectedTimestamp, tick.timestamp()); // FID 20 "115618" → 당일 KST 11:56:18
    }

    @Test
    void 실측_전문_재생_매수_접수() throws Exception {
        // 원본 11:56:40.163 수신분 — 시장가 매수 1주 접수 통보
        JsonNode data = mapper.readTree("""
                {"values":{"9201":"8132527211","9203":"0119433","9205":"","9001":"005930","912":"JJ","913":"접수","302":"삼성전자","900":"1","901":"0","902":"1","903":"0","904":"0000000","905":"+매수","906":"시장가","907":"2","908":"115639","909":"","910":"","911":"","10":"-258000","27":"-258000","28":"-257500","914":"","915":"","938":"0","939":"0","919":"0","920":"","921":"6801005","922":"00","923":"","10010":" 269000","2134":"1","2135":"KRX","2136":"N"},"type":"00","name":"주문체결","item":"005930"}
                """);

        Object result = RealMessageParser.parse(data, Clock.systemUTC());

        OrderNotice notice = assertInstanceOf(OrderNotice.class, result);
        assertEquals(new BrokerOrderId("0119433"), notice.brokerOrderId());
        assertEquals(new StockCode("005930"), notice.symbol());
        assertEquals("접수", notice.status());
        assertEquals(0L, notice.filledQuantity()); // FID 911 빈 문자열 → 0
        assertNull(notice.fillPrice()); // FID 910 빈 문자열 → null
        assertEquals(1L, notice.remainingQuantity()); // FID 902 = "1"
    }

    @Test
    void 실측_전문_재생_매수_체결() throws Exception {
        // 원본 11:56:40.462 수신분 — 위 접수 통보에 이어지는 매수 1주 체결 통보
        JsonNode data = mapper.readTree("""
                {"values":{"9201":"8132527211","9203":"0119433","9205":"","9001":"005930","912":"JJ","913":"체결","302":"삼성전자","900":"1","901":"0","902":"0","903":"258000","904":"0000000","905":"+매수","906":"시장가","907":"2","908":"115640","909":"847463","910":"258000","911":"1","10":"-258000","27":"-258000","28":"-257500","914":"258000","915":"1","938":"900","939":"0","919":"0","920":"","921":"6801005","922":"00","923":"","10010":" 269000","2134":"1","2135":"KRX","2136":"N"},"type":"00","name":"주문체결","item":"005930"}
                """);

        Object result = RealMessageParser.parse(data, Clock.systemUTC());

        OrderNotice notice = assertInstanceOf(OrderNotice.class, result);
        assertEquals(new BrokerOrderId("0119433"), notice.brokerOrderId());
        assertEquals("체결", notice.status());
        assertEquals(1L, notice.filledQuantity());
        assertEquals(new Price(new BigDecimal("258000")), notice.fillPrice());
        assertEquals(0L, notice.remainingQuantity()); // FID 902 = "0" → 완전 소진
    }

    @Test
    void 실측_전문_재생_매도_접수() throws Exception {
        // 원본 11:57:08.465 수신분 — 시장가 매도 1주 접수 통보
        JsonNode data = mapper.readTree("""
                {"values":{"9201":"8132527211","9203":"0119574","9205":"","9001":"005930","912":"JJ","913":"접수","302":"삼성전자","900":"1","901":"0","902":"1","903":"0","904":"0000000","905":"-매도","906":"시장가","907":"1","908":"115707","909":"","910":"","911":"","10":"-257750","27":"-258000","28":"-257500","914":"","915":"","938":"0","939":"0","919":"0","920":"","921":"7501001","922":"00","923":"","10010":" 269000","2134":"1","2135":"KRX","2136":"N"},"type":"00","name":"주문체결","item":"005930"}
                """);

        Object result = RealMessageParser.parse(data, Clock.systemUTC());

        OrderNotice notice = assertInstanceOf(OrderNotice.class, result);
        assertEquals(new BrokerOrderId("0119574"), notice.brokerOrderId());
        assertEquals("접수", notice.status());
        assertEquals(1L, notice.remainingQuantity());
    }

    @Test
    void 실측_전문_재생_매도_체결() throws Exception {
        // 원본 11:57:08.563 수신분 — 위 접수 통보에 이어지는 매도 1주 체결 통보(수수료 938="900")
        JsonNode data = mapper.readTree("""
                {"values":{"9201":"8132527211","9203":"0119574","9205":"","9001":"005930","912":"JJ","913":"체결","302":"삼성전자","900":"1","901":"0","902":"0","903":"258000","904":"0000000","905":"-매도","906":"시장가","907":"1","908":"115708","909":"848966","910":"258000","911":"1","10":"-258000","27":"-258000","28":"-257500","914":"258000","915":"1","938":"900","939":"516","919":"0","920":"","921":"7501001","922":"00","923":"","10010":" 269000","2134":"1","2135":"KRX","2136":"N"},"type":"00","name":"주문체결","item":"005930"}
                """);

        Object result = RealMessageParser.parse(data, Clock.systemUTC());

        OrderNotice notice = assertInstanceOf(OrderNotice.class, result);
        assertEquals(new BrokerOrderId("0119574"), notice.brokerOrderId());
        assertEquals("체결", notice.status());
        assertEquals(1L, notice.filledQuantity());
        assertEquals(new Price(new BigDecimal("258000")), notice.fillPrice());
        assertEquals(0L, notice.remainingQuantity());
    }
}
