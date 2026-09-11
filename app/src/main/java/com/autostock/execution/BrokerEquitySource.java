package com.autostock.execution;

import com.autostock.risk.EquitySource;
import com.autostock.risk.RiskProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * LIVE 모드 계좌 평가액 — {@link BrokerPort#balance()}를 조회해 risk 모듈의
 * {@link EquitySource} 계약을 구현한다.
 *
 * <p><b>모듈 경계</b>: execution 모듈이 risk.EquitySource를 구현하는 단방향 의존이다.
 * risk 쪽은 execution을 전혀 참조하지 않으므로(risk → execution 역방향 의존 없음)
 * 순환은 생기지 않는다(ModularityTests가 매 빌드마다 검증한다).
 *
 * <p><b>60초 캐시</b>: RiskGate(사이징)와 DailyPnlTracker(일 손실 한도 검사)는 시그널·체결이
 * 발생할 때마다 equity()를 호출할 수 있다. 그때마다 브로커 REST 잔고 조회(kt00018)를
 * 실제로 때리면 짧은 시간에 호출이 몰려 브로커 rate limit을 낭비한다 — 60초 이내 재호출은
 * 캐시된 값을 그대로 돌려준다. equity가 60초 안에 그렇게까지 급변하지 않는다는 전제다.
 *
 * <p><b>실패 시 2단계 폴백</b>: 1) 이전에 성공한 값이 있으면 그 값을 쓰고 경고 로그만 남긴다.
 * 2) 그마저 없으면(기동 직후 첫 조회부터 실패) {@link RiskProperties#paperEquity()}로
 * 폴백한다. {@link EquitySource#equity()} 계약상 예외를 던지지 않아야 하므로 — 잔고 조회
 * 실패로 리스크 게이트 전체가 멈추는 것보다는 다소 부정확하더라도 안전한 값을 쓰는 편이 낫다.
 *
 * <p>synchronized 대신 {@link ReentrantLock}을 쓰는 이유: 이 메서드는 실제로 브로커 REST
 * 호출(블로킹 I/O)을 포함한다 — JDK21 가상 스레드(spring.threads.virtual.enabled=true)에서
 * synchronized 블록 안의 블로킹은 캐리어 스레드를 고정(pinning)시키는 알려진 문제가 있다
 * (application.yml, monitor/EventFeed와 동일한 근거).
 */
@Component
@ConditionalOnProperty(prefix = "execution", name = "mode", havingValue = "LIVE")
public class BrokerEquitySource implements EquitySource {

    private static final Logger log = LoggerFactory.getLogger(BrokerEquitySource.class);
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final BrokerPort brokerPort;
    private final RiskProperties riskProperties;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    /** 마지막으로 성공한 조회값 — 실패 시 1차 폴백. null이면 아직 한 번도 성공하지 못한 상태. */
    private BigDecimal lastValue;
    private Instant lastFetchedAt = Instant.EPOCH;

    public BrokerEquitySource(BrokerPort brokerPort, RiskProperties riskProperties, Clock clock) {
        this.brokerPort = brokerPort;
        this.riskProperties = riskProperties;
        this.clock = clock;
    }

    @Override
    public BigDecimal equity() {
        lock.lock();
        try {
            Instant now = Instant.now(clock);
            if (lastValue != null && Duration.between(lastFetchedAt, now).compareTo(CACHE_TTL) < 0) {
                return lastValue; // 캐시 히트 — rate limit 절약(클래스 설명 참고)
            }
            return fetchFresh(now);
        } finally {
            lock.unlock();
        }
    }

    private BigDecimal fetchFresh(Instant now) {
        try {
            // 실측 확정(2026-09-11): equity = 추정예탁자산(prsm_dpst_aset_amt, 예수금+평가 = 총자산).
            // 총평가(tot_evlt_amt)는 보유 주식이 없으면 0이라 이걸 쓰면 "현금 5천만인데 equity=0 →
            // 사이징 0주 매수 불가" 사고가 난다(2026-09-11 운영 로그로 실증). 추정예탁자산이 0으로
            // 오는 비정상 응답에만 총평가로 방어 폴백한다.
            BrokerBalance balance = brokerPort.balance();
            BigDecimal fresh = balance.estimatedDepositAsset();
            if (fresh == null || fresh.signum() <= 0) {
                log.warn("추정예탁자산이 0/누락 — 총평가금액으로 폴백: {}", balance.totalEvaluationAmount());
                fresh = balance.totalEvaluationAmount();
            }
            lastValue = fresh;
            lastFetchedAt = now;
            return fresh;
        } catch (Exception e) {
            if (lastValue != null) {
                log.warn("브로커 잔고 조회 실패 — 마지막 성공값으로 폴백: {}", lastValue, e);
                return lastValue;
            }
            BigDecimal paperFallback = BigDecimal.valueOf(riskProperties.paperEquity());
            log.warn("브로커 잔고 조회 실패, 이전 성공값도 없음 — paperEquity로 폴백: {}", paperFallback, e);
            return paperFallback;
        }
    }
}
