package com.autostock.config;

import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.Price;
import com.autostock.common.util.Quantity;
import com.autostock.common.util.StockCode;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Function;

/**
 * 값 객체(common.util)를 JSON 스칼라로 주고받는다 — 이벤트 필드를 원시 타입에서 값 객체로 바꿔도 event_store에
 * 쌓이는 JSON 형식이 그대로이게 한다. common은 순수 자바로 두기 위해 Jackson 애너테이션 대신 여기서 등록한다
 * (Boot가 JacksonModule 빈을 JsonMapper에 자동 등록한다 — Jackson 3, VALUNIVO 494 U5).
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    @Bean
    JacksonModule valueObjectModule() {
        SimpleModule module = new SimpleModule("autostock-value-objects");
        module.addSerializer(StockCode.class, stringSerializer(StockCode::value));
        module.addDeserializer(StockCode.class, stringDeserializer(StockCode::new));
        module.addSerializer(BrokerOrderId.class, stringSerializer(BrokerOrderId::value));
        module.addDeserializer(BrokerOrderId.class, stringDeserializer(BrokerOrderId::new));
        module.addSerializer(Quantity.class, new ValueSerializer<>() {
            @Override
            public void serialize(Quantity value, JsonGenerator gen, SerializationContext ctxt) {
                gen.writeNumber(value.value());
            }
        });
        module.addDeserializer(Quantity.class, new ValueDeserializer<>() {
            @Override
            public Quantity deserialize(JsonParser p, DeserializationContext ctxt) {
                return new Quantity(p.getValueAsLong());
            }
        });
        // Price: 기존 BigDecimal 필드와 같은 숫자 JSON(값을 받은 그대로 — 자릿수 보존)
        module.addSerializer(Price.class, new ValueSerializer<>() {
            @Override
            public void serialize(Price value, JsonGenerator gen, SerializationContext ctxt) {
                gen.writeNumber(value.value());
            }
        });
        module.addDeserializer(Price.class, new ValueDeserializer<>() {
            @Override
            public Price deserialize(JsonParser p, DeserializationContext ctxt) {
                return new Price(p.getDecimalValue());
            }
        });
        return module;
    }

    private static <T> ValueSerializer<T> stringSerializer(Function<T, String> toValue) {
        return new ValueSerializer<>() {
            @Override
            public void serialize(T value, JsonGenerator gen, SerializationContext ctxt) {
                gen.writeString(toValue.apply(value));
            }
        };
    }

    private static <T> ValueDeserializer<T> stringDeserializer(Function<String, T> fromValue) {
        return new ValueDeserializer<>() {
            @Override
            public T deserialize(JsonParser p, DeserializationContext ctxt) {
                return fromValue.apply(p.getValueAsString());
            }
        };
    }
}
