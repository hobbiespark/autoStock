package com.autostock.ipo;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 실제 Spring 조립 경로(@Autowired 생성자)로 실거래 매도 빈을 만든다(RM-52·RM-54) — 키 있음+스위치 켬이면 주문 가능,
 * 키 없음이면 앱은 뜨고 기능만 꺼진다. 키 값은 합성 시험 값이고 네트워크는 쓰지 않는다(토큰은 첫 호출 때 받는다).
 */
class IpoLiveSellContextTest {

    @Configuration
    @EnableConfigurationProperties(IpoLiveSellProperties.class)
    static class Props {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Props.class)
            .withBean(IpoDealRepository.class, () -> mock(IpoDealRepository.class))
            .withBean(WebClient.Builder.class, WebClient::builder)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(IpoLiveSellService.class);

    @Test
    void 키와_스위치가_있으면_주문_가능으로_뜬다() {
        runner.withPropertyValues("ipo.live-sell.enabled=true", "ipo.live-sell.app-key=test-live-key",
                        "ipo.live-sell.app-secret=test-live-secret")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(IpoLiveSellService.class).ordersEnabled()).isTrue();
                });
    }

    @Test
    void 키가_없으면_기동은_되고_기능만_꺼진다() {
        runner.withPropertyValues("ipo.live-sell.enabled=true", "ipo.live-sell.app-key=", "ipo.live-sell.app-secret=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    IpoLiveSellService service = context.getBean(IpoLiveSellService.class);
                    assertThat(service.ordersEnabled()).isFalse();
                    assertThat(service.stateLabel()).contains("실전 키 없음");
                });
    }

    @Test
    void 기본값은_스위치_꺼짐이다() {
        runner.withPropertyValues("ipo.live-sell.app-key=test-live-key", "ipo.live-sell.app-secret=test-live-secret")
                .run(context -> {
                    assertThat(context.getBean(IpoLiveSellProperties.class).enabled()).isFalse();
                    assertThat(context.getBean(IpoLiveSellService.class).ordersEnabled()).isFalse();
                });
    }
}
