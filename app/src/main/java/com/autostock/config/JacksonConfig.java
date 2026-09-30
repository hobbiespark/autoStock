package com.autostock.config;

import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.Quantity;
import com.autostock.common.util.StockCode;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.function.Function;

/**
 * 값 객체(common.util)를 JSON 스칼라로 주고받는다 — 이벤트 필드를 원시 타입에서 값 객체로 바꿔도 event_store에
 * 쌓이는 JSON 형식이 그대로이게 한다. common은 순수 자바로 두기 위해 Jackson 애너테이션 대신 여기서 등록한다
 * (Boot가 Module 빈을 ObjectMapper에 자동 등록한다).
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    @Bean
    Module valueObjectModule() {
        SimpleModule module = new SimpleModule("autostock-value-objects");
        module.addSerializer(StockCode.class, stringSerializer(StockCode::value));
        module.addDeserializer(StockCode.class, stringDeserializer(StockCode::new));
        module.addSerializer(BrokerOrderId.class, stringSerializer(BrokerOrderId::value));
        module.addDeserializer(BrokerOrderId.class, stringDeserializer(BrokerOrderId::new));
        module.addSerializer(Quantity.class, new JsonSerializer<>() {
            @Override
            public void serialize(Quantity value, JsonGenerator gen, SerializerProvider provider) throws IOException {
                gen.writeNumber(value.value());
            }
        });
        module.addDeserializer(Quantity.class, new JsonDeserializer<>() {
            @Override
            public Quantity deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                return new Quantity(p.getValueAsLong());
            }
        });
        return module;
    }

    private static <T> JsonSerializer<T> stringSerializer(Function<T, String> toValue) {
        return new JsonSerializer<>() {
            @Override
            public void serialize(T value, JsonGenerator gen, SerializerProvider provider) throws IOException {
                gen.writeString(toValue.apply(value));
            }
        };
    }

    private static <T> JsonDeserializer<T> stringDeserializer(Function<String, T> fromValue) {
        return new JsonDeserializer<>() {
            @Override
            public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                return fromValue.apply(p.getValueAsString());
            }
        };
    }
}
