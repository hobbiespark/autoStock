package com.autostock.config;

import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.Quantity;
import com.autostock.common.util.StockCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 값 객체는 JSON에서 스칼라(문자열·숫자)로 오간다 — 이벤트 필드를 원시 타입에서 값 객체로 바꿔도
 * event_store에 쌓이는 JSON 형식이 그대로여야 한다(ARCHITECTURE.md 3절 단계 도입).
 */
class ValueObjectJsonTest {

    record Sample(StockCode symbol, BrokerOrderId brokerOrderId, Quantity quantity) {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(JacksonConfig.class);

    @Test
    void 스프링_ObjectMapper가_값_객체를_스칼라로_직렬화한다() {
        runner.run(context -> {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);

            String json = mapper.writeValueAsString(
                    new Sample(new StockCode("005930"), new BrokerOrderId("0119433"), new Quantity(10)));

            assertEquals("{\"symbol\":\"005930\",\"brokerOrderId\":\"0119433\",\"quantity\":10}", json);
        });
    }

    @Test
    void OrderRequest_이벤트_JSON의_종목코드는_예전처럼_문자열이다() {
        // event_store(EventAuditListener)에 쌓이는 형식 — StockCode 도입 전과 같아야 스키마 v1을 유지한다
        runner.run(context -> {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);

            String json = mapper.writeValueAsString(new com.autostock.common.event.OrderRequest(
                    "20260930-C3-005930-BUY-001", "C3", new StockCode("005930"),
                    com.autostock.common.event.Side.BUY, 1, new java.math.BigDecimal("258000"),
                    java.time.Instant.parse("2026-09-30T01:00:00Z")));

            org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"symbol\":\"005930\""), json);
        });
    }

    @Test
    void Signal_이벤트_JSON의_종목코드는_예전처럼_문자열이고_과거_JSON도_읽힌다() {
        runner.run(context -> {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);

            String json = mapper.writeValueAsString(new com.autostock.common.event.Signal(
                    "C3", new StockCode("005930"), com.autostock.common.event.Side.BUY,
                    new java.math.BigDecimal("258000"), 1.0, java.time.Instant.parse("2026-09-30T01:00:00Z")));
            org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"symbol\":\"005930\""), json);

            // StockCode 도입 전(v1, fixedQuantity 없음)에 쌓인 형식
            com.autostock.common.event.Signal old = mapper.readValue(
                    "{\"strategyId\":\"C3\",\"symbol\":\"005930\",\"side\":\"BUY\",\"refPrice\":258000,"
                            + "\"confidence\":1.0,\"timestamp\":\"2026-09-30T01:00:00Z\"}",
                    com.autostock.common.event.Signal.class);
            assertEquals(new StockCode("005930"), old.symbol());
        });
    }

    @Test
    void 스칼라_JSON을_값_객체로_읽는다() {
        runner.run(context -> {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);

            Sample sample = mapper.readValue(
                    "{\"symbol\":\"005930\",\"brokerOrderId\":\"0119433\",\"quantity\":10}", Sample.class);

            assertEquals(new StockCode("005930"), sample.symbol());
            assertEquals(new BrokerOrderId("0119433"), sample.brokerOrderId());
            assertEquals(new Quantity(10), sample.quantity());
        });
    }

    @Test
    void 형식이_틀린_값은_읽을_때_거부한다() {
        runner.run(context -> {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);

            assertThrows(Exception.class, () -> mapper.readValue(
                    "{\"symbol\":\"5930\",\"brokerOrderId\":\"1\",\"quantity\":1}", Sample.class));
            assertThrows(Exception.class, () -> mapper.readValue(
                    "{\"symbol\":\"005930\",\"brokerOrderId\":\"1\",\"quantity\":0}", Sample.class));
        });
    }
}
