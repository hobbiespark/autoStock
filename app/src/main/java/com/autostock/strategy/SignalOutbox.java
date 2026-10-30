package com.autostock.strategy;

import com.autostock.common.event.Side;
import com.autostock.common.event.Signal;
import com.autostock.common.event.SignalDecision;
import com.autostock.common.util.Price;
import com.autostock.common.util.StockCode;
import com.autostock.common.util.StockNames;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 다전략 신호 발행 창구(2026-10-16) — 한 스레드에서 차례로 {@link Signal}·{@link SignalDecision}을 낸다.
 *
 * <p>왜 한 스레드인가: 단타·차트 기법의 장중 조건은 WS 수신 스레드(실시간 체결 이벤트)에서 판정한다. 거기서 바로 신호를 내면
 * RiskGate → 주문 전송(REST)까지 WS 스레드가 붙잡힌다. 또 여러 전략이 동시에 신호를 내면 RiskGate의 미체결·보유 확인이
 * 서로 경합한다. 그래서 신호는 이 창구에 넣고, 창구가 한 줄로 처리한다(C3는 예전처럼 자기 스케줄 스레드에서 낸다).
 */
@Component
public class SignalOutbox {

    private static final Logger log = LoggerFactory.getLogger(SignalOutbox.class);

    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "strategy-outbox");
        t.setDaemon(true);
        return t;
    });

    public SignalOutbox(ApplicationEventPublisher publisher, Clock clock) {
        this.publisher = publisher;
        this.clock = clock;
    }

    /** 매수 신호 — 판단 근거(SignalDecision BUY)와 함께. refPrice가 지정가 겸 사이징 기준이다(RiskGate가 호가단위로 맞춘다). */
    public void buy(String strategyId, String horizon, String symbol, BigDecimal refPrice, double confidence,
                    String reason, Map<String, String> metrics) {
        buy(strategyId, horizon, symbol, refPrice, null, confidence, reason, metrics);
    }

    /**
     * 매수 신호 + 슬리피지 기준가(2026-10-22, {@link Signal#benchmarkPrice()}) — 전략이 그 가격에 살 생각이었던 값.
     * null이면 지정가로 잰다.
     */
    public void buy(String strategyId, String horizon, String symbol, BigDecimal refPrice, BigDecimal benchmark,
                    double confidence, String reason, Map<String, String> metrics) {
        submit(() -> {
            publishDecision(strategyId, horizon, symbol, "BUY", reason, metrics);
            publisher.publishEvent(new Signal(strategyId, new StockCode(symbol), Side.BUY, priceOrNull(refPrice),
                    confidence, null, clock.instant(), priceOrNull(benchmark)));
        });
    }

    /**
     * 3분할 매수 신호(2026-10-30, {@link Signal#tranche()}) — 1차는 새 보유, 2·3차는 그 전략이 든 종목에 더 산다.
     * RiskGate가 한 차수를 종목 예산의 1/3로 센다.
     */
    public void buyTranche(String strategyId, String horizon, String symbol, BigDecimal refPrice, BigDecimal benchmark,
                           int tranche, String reason, Map<String, String> metrics) {
        submit(() -> {
            publishDecision(strategyId, horizon, symbol, "BUY", reason, metrics);
            publisher.publishEvent(new Signal(strategyId, new StockCode(symbol), Side.BUY, priceOrNull(refPrice),
                    1.0, null, clock.instant(), priceOrNull(benchmark), tranche));
        });
    }

    /** 매도 신호 — 슬리브 전략은 RiskGate가 그 전략 보유 수량만 판다. */
    public void sell(String strategyId, String horizon, String symbol, BigDecimal refPrice, String reason,
                     Map<String, String> metrics) {
        sell(strategyId, horizon, symbol, refPrice, null, reason, metrics);
    }

    /** 매도 신호 + 슬리피지 기준가(2026-10-22). null이면 지정가로 잰다. */
    public void sell(String strategyId, String horizon, String symbol, BigDecimal refPrice, BigDecimal benchmark,
                     String reason, Map<String, String> metrics) {
        submit(() -> {
            publishDecision(strategyId, horizon, symbol, "SELL", reason, metrics);
            publisher.publishEvent(new Signal(strategyId, new StockCode(symbol), Side.SELL, priceOrNull(refPrice), 1.0,
                    null, clock.instant(), priceOrNull(benchmark)));
        });
    }

    /** 주문 없는 판단 기록(SKIP·HOLD 등). */
    public void decision(String strategyId, String horizon, String symbol, String conclusion, String reason,
                         Map<String, String> metrics) {
        submit(() -> publishDecision(strategyId, horizon, symbol, conclusion, reason, metrics));
    }

    private void publishDecision(String strategyId, String horizon, String symbol, String conclusion, String reason,
                                 Map<String, String> metrics) {
        publisher.publishEvent(new SignalDecision(horizon, strategyId, new StockCode(symbol), conclusion, reason,
                metrics == null ? Map.of() : metrics, clock.instant()));
    }

    private void submit(Runnable task) {
        executor.execute(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("전략 신호 처리 중 오류", e);
            }
        });
    }

    /** 가격 × 배율(지정가 여유) — 반올림은 RiskGate의 호가단위 정렬이 한다. */
    public static BigDecimal times(BigDecimal price, double factor) {
        return price == null ? null : price.multiply(BigDecimal.valueOf(factor)).setScale(0, RoundingMode.HALF_UP);
    }

    private static Price priceOrNull(BigDecimal value) {
        return value != null && value.signum() > 0 ? new Price(value) : null;
    }

    /** 시험용 — 넣은 일이 모두 끝날 때까지 기다린다. */
    void drain() {
        try {
            executor.submit(() -> { }).get();
        } catch (Exception e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    static String label(String symbol) {
        return StockNames.label(symbol);
    }
}
