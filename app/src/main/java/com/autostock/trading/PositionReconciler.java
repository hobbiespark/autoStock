package com.autostock.trading;

import com.autostock.common.event.PositionMismatch;
import com.autostock.common.util.StockCode;
import com.autostock.common.util.StockNames;
import com.autostock.execution.BrokerHolding;
import com.autostock.execution.BrokerPort;
import com.autostock.market.MarketSessionService;
import com.autostock.portfolio.PositionBook;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 잔고 ↔ 장부 주기 대사 (실행 계획 1.5, BE-P1-4, 2026-10-02 — aiDoc/position-reconcile.md).
 *
 * <p>브로커 잔고(kt00018, {@link BrokerPort#balance()})의 종목별 수량과 {@link PositionBook}(체결·기동 복원으로 쌓은 장부)을
 * 5분마다 비교한다. 체결 통보 유실·HTS 수동 매매·복원 실패로 둘이 어긋나면 "보유 중 추가 매수 금지"·매도 수량 같은 리스크
 * 판단이 틀어진다. <b>알림과 메트릭만</b> 낸다 — 어느 쪽이 맞는지 사람이 확인할 때까지 자동 교정은 하지 않는다(계획 1.5).
 * <ul>
 *   <li>같은 불일치(종목·브로커 수량·장부 수량)를 <b>연속 2회</b>(약 10분) 볼 때만 알린다 — 체결 직후 잔고·통보 반영 시차로
 *       생기는 일시 불일치를 거른다. 같은 불일치는 한 번만 알리고, 알린 종목이 다시 일치하면 INFO로 남긴다.</li>
 *   <li>LIVE + 장중 세션(ACTIVE) + {@code trading.position-reconcile.enabled=true}일 때만 돈다(기본 false).</li>
 *   <li>게이지 {@code reconcile.position.mismatch} = 마지막 점검의 불일치 종목 수(연속 확인 전 포함). 실패는 카운터
 *       {@code reconcile.failure{kind=positions}}(실행 계획 1.7).</li>
 * </ul>
 * 의존 방향: trading → portfolio(장부 조회)·execution(잔고) — portfolio는 리프라 순환이 없다.
 */
@Component
public class PositionReconciler {

    private static final Logger log = LoggerFactory.getLogger(PositionReconciler.class);

    private final BrokerPort brokerPort;
    private final PositionBook positionBook;
    private final TradingProperties tradingProperties;
    private final PositionReconcileProperties properties;
    private final MarketSessionService marketSession;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final AtomicInteger mismatchCount = new AtomicInteger();

    /** 직전 점검의 불일치(종목별). */
    private Map<StockCode, Mismatch> lastSeen = Map.of();
    /** 이미 알린 불일치. */
    private final Set<Mismatch> alerted = new HashSet<>();

    public PositionReconciler(BrokerPort brokerPort, PositionBook positionBook, TradingProperties tradingProperties,
                              PositionReconcileProperties properties, MarketSessionService marketSession,
                              ApplicationEventPublisher publisher, Clock clock, MeterRegistry meterRegistry) {
        this.brokerPort = brokerPort;
        this.positionBook = positionBook;
        this.tradingProperties = tradingProperties;
        this.properties = properties;
        this.marketSession = marketSession;
        this.publisher = publisher;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        Gauge.builder("reconcile.position.mismatch", mismatchCount, AtomicInteger::get)
                .description("잔고·장부 수량이 다른 종목 수(마지막 점검)")
                .register(meterRegistry);
    }

    /** 5분 주기(기동 1분 뒤 시작). 실패는 경고 후 다음 회차 — fixedDelay인 이유는 StaleOrderCanceller 참고. */
    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 60 * 1000)
    public void scheduledCheck() {
        if (!properties.enabled() || tradingProperties.mode() != TradingProperties.Mode.LIVE || !marketSession.isActive()) {
            return;
        }
        try {
            check();
        } catch (RuntimeException e) {
            meterRegistry.counter("reconcile.failure", "kind", "positions").increment();
            log.warn("잔고·장부 대사 실패 — 다음 주기(5분)에 재시도", e);
        }
    }

    /** 한 번 비교한다. */
    synchronized void check() {
        Map<StockCode, Long> broker = new HashMap<>();
        for (BrokerHolding holding : brokerPort.balance().holdings()) {
            broker.merge(holding.symbol(), holding.quantity(), Long::sum);
        }
        Map<StockCode, Long> book = new HashMap<>();
        positionBook.snapshot().forEach((symbol, position) -> book.put(symbol, position.quantity()));

        Map<StockCode, Mismatch> now = new HashMap<>();
        Set<StockCode> symbols = new TreeSet<>((a, b) -> a.value().compareTo(b.value()));
        symbols.addAll(broker.keySet());
        symbols.addAll(book.keySet());
        for (StockCode symbol : symbols) {
            long brokerQty = broker.getOrDefault(symbol, 0L);
            long bookQty = book.getOrDefault(symbol, 0L);
            if (brokerQty != bookQty) {
                now.put(symbol, new Mismatch(symbol, brokerQty, bookQty));
            }
        }
        mismatchCount.set(now.size());

        for (Mismatch mismatch : now.values()) {
            if (mismatch.equals(lastSeen.get(mismatch.symbol())) && alerted.add(mismatch)) {
                log.warn("잔고·장부 불일치(연속 2회): {} 브로커 {}주 / 장부 {}주 — 자동 교정 안 함, 확인 필요",
                        StockNames.label(mismatch.symbol()), mismatch.brokerQuantity(), mismatch.bookQuantity());
                publisher.publishEvent(new PositionMismatch(mismatch.symbol(), mismatch.brokerQuantity(),
                        mismatch.bookQuantity(), clock.instant()));
            }
        }
        // 알린 불일치가 사라졌거나(일치) 값이 바뀌었으면 알림 기록을 지운다 — 바뀐 값은 다시 연속 2회 뒤에 알린다
        alerted.removeIf(old -> {
            if (old.equals(now.get(old.symbol()))) {
                return false;
            }
            if (!now.containsKey(old.symbol())) {
                log.info("잔고·장부 다시 일치: {} {}주", StockNames.label(old.symbol()),
                        broker.getOrDefault(old.symbol(), 0L));
            }
            return true;
        });
        lastSeen = Map.copyOf(now);
    }

    /** 한 종목의 불일치 — 값이 같아야 "같은 불일치"다. */
    record Mismatch(StockCode symbol, long brokerQuantity, long bookQuantity) {
    }
}
