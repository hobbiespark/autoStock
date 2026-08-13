package com.autostock.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * {@link Clock} 빈 등록 — 시간 판단이 필요한 코드(예: execution/StaleOrderCanceller)가
 * {@code Instant.now()}를 직접 호출하지 않고 주입받은 Clock을 쓰게 하기 위함이다.
 * 테스트에서는 {@link Clock#fixed}로 갈아끼워 "시간이 흘렀다"를 결정론적으로 검증할 수 있다.
 *
 * <p>UTC 고정을 쓰는 이유 — {@link java.time.Instant}는 절대 시각이라 존(zone) 자체가
 * 결과에 영향을 주지 않는다(zone은 LocalDate/LocalDateTime 변환에만 영향). 날짜 롤오버
 * 판단처럼 "오늘이 언제 바뀌는가"가 중요한 곳은 {@link com.autostock.common.util.MarketConstants#KST}를
 * 명시적으로 쓰고, 이 Clock은 순수 경과시간(Duration) 비교용으로만 사용한다.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
