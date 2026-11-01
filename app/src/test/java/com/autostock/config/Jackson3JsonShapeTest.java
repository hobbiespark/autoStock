package com.autostock.config;

import com.autostock.common.event.Fill;
import com.autostock.common.event.Side;
import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.Price;
import com.autostock.common.util.Quantity;
import com.autostock.common.util.StockCode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.modulith.events.core.EventSerializer;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Jackson 3로 옮긴 뒤에도 이벤트(event_publication·event_store)·MVC 응답의 JSON 모양이 U4(Jackson 2 과도기)까지와
 * 같은지 고정한다(VALUNIVO 494 U5). 값 객체 모듈({@link JacksonConfig})이 Boot의 JsonMapper에 붙어야 종목코드 같은
 * 값 객체가 문자열·숫자 스칼라로 남는다.
 */
class Jackson3JsonShapeTest {

    private static final String JACKSON3_EVENTS =
            "org.springframework.modulith.events.jackson.JacksonEventSerializationConfiguration";

    private static final Fill FILL = new Fill("20260930-C3-005930-BUY-001", new BrokerOrderId("0119433"),
            new StockCode("005930"), Side.BUY, new Quantity(1), new Price(new BigDecimal("258000")),
            Instant.parse("2026-09-30T01:00:00Z"));

    /** U4까지(Jackson 2) 쓰던 모양 — 필드 순서는 record 선언 순서, 시각은 ISO-8601 문자열 */
    private static final String FILL_JSON = "{\"orderIdempotencyKey\":\"20260930-C3-005930-BUY-001\","
            + "\"brokerOrderId\":\"0119433\",\"symbol\":\"005930\",\"side\":\"BUY\",\"filledQuantity\":1,"
            + "\"fillPrice\":258000,\"timestamp\":\"2026-09-30T01:00:00Z\"}";

    @Test
    void application_yml에_Jackson2_과도기_설정이_없다() {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer()).run(context -> {
            var env = context.getEnvironment();
            assertThat(env.getProperty("spring.autoconfigure.exclude")).isNull();
            assertThat(env.getProperty("spring.http.converters.preferred-json-mapper")).isNull();
            assertThat(env.getProperty("spring.jackson2.time-zone")).isNull();
            assertThat(env.getProperty("spring.jackson.time-zone")).isEqualTo("UTC");
        });
    }

    @Test
    void Jackson2_자동설정_모듈이_클래스패스에_없다() {
        // spring-boot-jackson2가 다시 들어오면 MVC 변환기 선택이 갈릴 수 있어 막는다
        assertThatThrownBy(() -> Class.forName("org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void Modulith_Jackson3_이벤트_직렬화가_U4와_같은_JSON을_쓰고_되읽는다() throws Exception {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, Class.forName(JACKSON3_EVENTS)))
                .withUserConfiguration(JacksonConfig.class)
                .run(context -> {
                    EventSerializer serializer = context.getBean(EventSerializer.class);
                    String json = String.valueOf(serializer.serialize(FILL));

                    assertThat(json).isEqualTo(FILL_JSON);
                    assertThat(serializer.deserialize(json, Fill.class)).isEqualTo(FILL);
                });
    }

    @Test
    void 음성대조_값_객체_모듈이_없으면_종목코드가_스칼라가_아니다() throws Exception {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, Class.forName(JACKSON3_EVENTS)))
                .run(context -> assertThat(String.valueOf(context.getBean(EventSerializer.class).serialize(FILL)))
                        .doesNotContain("\"symbol\":\"005930\""));
    }

    // Boot 4는 변환기 자체가 아니라 변환기를 등록하는 customizer를 빈으로 둔다(빈 이름은 @Bean 메서드 이름)
    private static final String JACKSON3_MVC = "jacksonJsonHttpMessageConvertersCustomizer";

    @Test
    void MVC_JSON_변환기는_Jackson3이고_값_객체를_U4와_같은_모양으로_쓴다() {
        new WebApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                        HttpMessageConvertersAutoConfiguration.class))
                .withUserConfiguration(JacksonConfig.class)
                .run(context -> {
                    assertThat(context).hasBean(JACKSON3_MVC);
                    assertThat(context.getBean(JsonMapper.class).writeValueAsString(FILL)).isEqualTo(FILL_JSON);
                });
    }
}
