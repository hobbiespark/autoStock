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
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.modulith.events.core.EventSerializer;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boot 4·Modulith 2 아래에서도 이벤트·MVC JSON을 Jackson 2(값 객체 모듈 {@link JacksonConfig})로 쓰는지 고정한다
 * (VALUNIVO 494 U4 과도기). Boot 4는 둘 다 Jackson 3를 먼저 고르므로 application.yml의 두 설정이 빠지면
 * event_publication·REST 응답에서 종목코드 같은 값 객체가 문자열이 아니라 객체로 바뀐다. U5(Jackson 3)에서 이 시험을 바꾼다.
 */
@SuppressWarnings("removal")
class Jackson2BridgeTest {

    private static final String JACKSON3_EVENTS =
            "org.springframework.modulith.events.jackson.JacksonEventSerializationConfiguration";
    private static final String JACKSON2_EVENTS =
            "org.springframework.modulith.events.jackson2.Jackson2EventSerializationConfiguration";

    private static final Fill FILL = new Fill("20260930-C3-005930-BUY-001", new BrokerOrderId("0119433"),
            new StockCode("005930"), Side.BUY, new Quantity(1), new Price(new BigDecimal("258000")),
            Instant.parse("2026-09-30T01:00:00Z"));

    private final ApplicationContextRunner yml = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void application_yml이_Jackson3_이벤트_직렬화_자동설정을_제외한다() throws ClassNotFoundException {
        Class.forName(JACKSON3_EVENTS);   // 판 올림으로 이름이 바뀌면 여기서 먼저 깨진다(없는 이름 제외는 기동 실패)
        yml.run(context -> assertThat(context.getEnvironment().getProperty("spring.autoconfigure.exclude"))
                .contains(JACKSON3_EVENTS));
    }

    @Test
    void 제외_뒤_남는_Jackson2_이벤트_직렬화는_값_객체를_예전처럼_스칼라로_쓰고_되읽는다() throws Exception {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(Jackson2AutoConfiguration.class, Class.forName(JACKSON2_EVENTS)))
                .withUserConfiguration(JacksonConfig.class)
                .run(context -> {
                    EventSerializer serializer = context.getBean(EventSerializer.class);
                    String json = String.valueOf(serializer.serialize(FILL));

                    assertThat(json).contains("\"symbol\":\"005930\"").contains("\"brokerOrderId\":\"0119433\"");
                    assertThat(serializer.deserialize(json, Fill.class)).isEqualTo(FILL);
                });
    }

    @Test
    void 음성대조_제외하지_않으면_Jackson3_직렬화가_선택돼_값_객체가_객체로_바뀐다() throws Exception {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, Jackson2AutoConfiguration.class,
                        Class.forName(JACKSON3_EVENTS), Class.forName(JACKSON2_EVENTS)))
                .withUserConfiguration(JacksonConfig.class)
                .run(context -> assertThat(String.valueOf(context.getBean(EventSerializer.class).serialize(FILL)))
                        .doesNotContain("\"symbol\":\"005930\""));
    }

    // Boot 4는 변환기 자체가 아니라 변환기를 등록하는 customizer를 빈으로 둔다(빈 이름은 @Bean 메서드 이름)
    private static final String JACKSON2_MVC = "jackson2HttpMessageConvertersCustomizer";
    private static final String JACKSON3_MVC = "jacksonJsonHttpMessageConvertersCustomizer";

    private static WebApplicationContextRunner mvc() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, Jackson2AutoConfiguration.class,
                        HttpMessageConvertersAutoConfiguration.class))
                .withUserConfiguration(JacksonConfig.class);
    }

    @Test
    void MVC_JSON_변환기는_application_yml대로_Jackson2다() {
        mvc().withInitializer(new ConfigDataApplicationContextInitializer()).run(context -> {
            assertThat(context).hasBean(JACKSON2_MVC);
            assertThat(context).doesNotHaveBean(JACKSON3_MVC);
        });
    }

    @Test
    void 음성대조_설정이_없으면_MVC는_Jackson3_변환기를_쓴다() {
        mvc().run(context -> {
            assertThat(context).hasBean(JACKSON3_MVC);
            assertThat(context).doesNotHaveBean(JACKSON2_MVC);
        });
    }
}
