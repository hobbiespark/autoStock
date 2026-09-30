package com.autostock.monitor;

import com.autostock.market.MarketSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * 외부 dead-man switch 핑(Phase 0.7, D-09 — aiDoc/heartbeat-telegram.md). 장 대응 시간(ACTIVE) 동안 1분마다
 * {@code monitor.heartbeat.url}(Healthchecks.io 체크의 ping URL)에 GET을 보낸다. 핑이 끊기면 Healthchecks가
 * 텔레그램으로 알린다 — PC 절전·재부팅·앱 사망처럼 <b>앱 스스로는 알릴 수 없는</b> 장애를 잡는 유일한 경로다
 * (앱 내부 텔레그램 알림은 앱이 죽으면 함께 죽는다).
 *
 * <ul>
 *   <li>URL이 비어 있으면 아무것도 하지 않는다(기본 — 사용자가 체크를 만들고 .env에 MONITOR_HEARTBEAT_URL을 넣어야 켜진다).</li>
 *   <li>장외 대기(STANDBY — 장외 시간·주말·휴장일)에는 보내지 않는다. Healthchecks 체크는 평일 장중 cron으로 두고,
 *       휴장일은 체크를 일시정지한다(다음 핑이 오면 자동 재개).</li>
 *   <li>본문 없음 — 외부로 나가는 정보는 "살아 있다"는 사실뿐이다. URL 자체(체크 UUID)는 로그에 남기지 않는다.</li>
 *   <li>실패는 WARN 한 줄, 재시도 없음 — 다음 주기(1분)가 곧 재시도다. {@code fixedDelay}라 절전 복귀 때 밀린 회차가
 *       몰려 실행되지 않는다(StaleOrderCanceller와 같은 이유).</li>
 * </ul>
 */
@Component
public class HeartbeatPinger {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatPinger.class);

    /** 핑 1회 상한 — 외부 서비스가 느려도 스케줄 스레드를 오래 붙잡지 않는다. */
    static final Duration PING_TIMEOUT = Duration.ofSeconds(10);

    private final WebClient webClient;
    private final String url;
    private final MarketSessionService marketSession;

    public HeartbeatPinger(WebClient.Builder builder,
                           @Value("${monitor.heartbeat.url:}") String url,
                           MarketSessionService marketSession) {
        this.webClient = builder.build();
        this.url = url == null ? "" : url.trim();
        this.marketSession = marketSession;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void ping() {
        if (url.isEmpty() || !marketSession.isActive()) {
            return;
        }
        try {
            webClient.get().uri(url).retrieve().toBodilessEntity().block(PING_TIMEOUT);
        } catch (Exception e) {
            log.warn("외부 heartbeat 핑 실패 — 다음 주기(1분)에 다시 보낸다: {}", e.getClass().getSimpleName());
        }
    }

    /** 기동 시 설정 상태 한 줄 — 켜졌는지 로그로 확인할 수 있게(URL은 남기지 않는다). */
    @EventListener(ApplicationReadyEvent.class)
    public void logConfiguration() {
        if (url.isEmpty()) {
            log.info("외부 heartbeat 꺼짐 — .env에 MONITOR_HEARTBEAT_URL(Healthchecks ping URL)을 넣으면 장중 1분마다 핑");
        } else {
            log.info("외부 heartbeat 켜짐 — 장중(ACTIVE) 1분마다 핑");
        }
    }
}
