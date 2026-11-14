package com.autostock.strategy;

import com.autostock.common.event.Side;
import com.autostock.common.event.Signal;
import com.autostock.common.event.SignalDecision;
import com.autostock.common.event.StrategyRunCompleted;
import com.autostock.common.util.MarketConstants;
import com.autostock.common.util.Price;
import com.autostock.common.util.StockCode;
import com.autostock.common.util.StockNames;
import com.autostock.market.MarketCalendarService;
import com.autostock.market.MarketDataPort;
import com.autostock.monitor.TradingSystemManager;
import com.autostock.monitor.TradingSystemStatus;
import com.autostock.portfolio.StrategyBook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 지수 보유 전략(KODEX-HOLD) — 경로 C(ADR-16·D-31)의 전략 슬리브 "KODEX200 보유 + 리스크 오버레이".
 *
 * <p>판단은 하나뿐이다: 이 전략 장부에 지수 ETF가 없으면 최우선 매도호가로 매수 신호(비중 1.0)를 내고, 있으면 아무것도
 * 하지 않는다. <b>스스로 팔지 않는다.</b> 타이밍 규칙(국면 필터·거시 필터)을 넣지 않는 것이 이 전략의 정의다 — 사전 선언
 * 31계열이 모두 게이트 ①을 통과하지 못했기 때문이다. 손실 통제는 모든 주문이 지나는 리스크 관문(킬스위치·일 손실 한도·
 * 슬리브 한도)이 맡는다("전략은 주문하지 않는다", 23 K-15).
 *
 * <p>실행 시각·장중 따라잡기·운영 상태 이중 가드·휴장일 처리는 {@link C3LiveStrategy}와 같다(평일 09:05, 15:00 뒤에는
 * 하지 않음, 하루 따라잡기 3번까지). 실행 요약은 {@link StrategyRunCompleted}로 내 09:15 실행 확인과 텔레그램 한 줄이
 * C3와 같은 길로 간다.
 */
@Component
public class IndexHoldStrategy {

    private static final Logger log = LoggerFactory.getLogger(IndexHoldStrategy.class);

    /** Signal·슬리브·장부의 전략 ID. {@code autostock.risk.multi-strategy.sleeves}의 같은 이름 줄이 예산을 정한다. */
    public static final String STRATEGY_ID = "KODEX-HOLD";
    /** 보유기간 지평(ADR-11) — 장기. */
    static final String HORIZON = "LONG";

    static final LocalTime RUN_AT = LocalTime.of(9, 5);
    static final LocalTime JUDGE_DEADLINE = LocalTime.of(15, 0);
    static final int CATCH_UP_MAX_ATTEMPTS = 3;

    private final IndexHoldProperties properties;
    private final MarketDataPort marketData;
    private final StrategyBook strategyBook;
    private final ApplicationEventPublisher publisher;
    private final MarketCalendarService marketCalendar;
    private final TradingSystemManager tradingSystem;
    private final Clock clock;

    private volatile LocalDate ranDay;
    private volatile LocalDate catchUpDay;
    private volatile int catchUpAttempts;
    private final ReentrantLock runLock = new ReentrantLock();

    public IndexHoldStrategy(IndexHoldProperties properties, MarketDataPort marketData, StrategyBook strategyBook,
                             ApplicationEventPublisher publisher, MarketCalendarService marketCalendar,
                             TradingSystemManager tradingSystem, Clock clock) {
        this.properties = properties;
        this.marketData = marketData;
        this.strategyBook = strategyBook;
        this.publisher = publisher;
        this.marketCalendar = marketCalendar;
        this.tradingSystem = tradingSystem;
        this.clock = clock;
    }

    /** 매 평일 09:05 KST. 절전 복귀 등으로 15:00 뒤에 돌면 그날은 하지 않는다. */
    @Scheduled(cron = "0 5 9 * * MON-FRI", zone = "Asia/Seoul")
    public void run() {
        LocalTime now = LocalTime.now(clock.withZone(MarketConstants.KST));
        if (!now.isBefore(JUDGE_DEADLINE)) {
            log.info("KODEX-HOLD: 09:05 예약이 {}에 늦게 실행됨 — {} 이후라 오늘은 판단하지 않는다", now.withNano(0), JUDGE_DEADLINE);
            return;
        }
        runOnce(false);
    }

    /** 장중 기동 따라잡기 — 09:05~15:00에 오늘 아직 판단하지 않았으면 판단한다(하루 3번까지). */
    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void catchUp() {
        if (!properties.enabled()) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(MarketConstants.KST));
        LocalDate today = now.toLocalDate();
        LocalTime time = now.toLocalTime();
        if (time.isBefore(RUN_AT) || !time.isBefore(JUDGE_DEADLINE) || today.equals(ranDay)) {
            return;
        }
        if (tradingSystem.status() != TradingSystemStatus.RUNNING || !marketCalendar.isTradingDay(today)) {
            return;
        }
        if (!today.equals(catchUpDay)) {
            catchUpDay = today;
            catchUpAttempts = 0;
        }
        if (catchUpAttempts >= CATCH_UP_MAX_ATTEMPTS) {
            return;
        }
        catchUpAttempts++;
        log.info("KODEX-HOLD: 오늘 09:05 판단 기록 없음(장중 기동 등) — 지금 판단한다({}/{}번째)", catchUpAttempts,
                CATCH_UP_MAX_ATTEMPTS);
        runOnce(true);
    }

    private void runOnce(boolean catchUp) {
        runLock.lock();
        try {
            if (catchUp && LocalDate.now(clock.withZone(MarketConstants.KST)).equals(ranDay)) {
                return;
            }
            String line = execute();
            if (line != null) {
                LocalDate today = LocalDate.now(clock.withZone(MarketConstants.KST));
                ranDay = today;
                log.info(line);
                publisher.publishEvent(new StrategyRunCompleted(STRATEGY_ID, today, line, 0, clock.instant()));
            }
        } finally {
            runLock.unlock();
        }
    }

    /** 판단 본체 — 판단했으면 요약 한 줄, 꺼짐·운영 상태·휴장일로 건너뛰면 null. */
    String execute() {
        if (!properties.enabled()) {
            return null;
        }
        TradingSystemStatus status = tradingSystem.status();
        if (status != TradingSystemStatus.RUNNING) {
            log.info("KODEX-HOLD: 운영 상태가 RUNNING이 아님({}) — 이번 스케줄 스킵", status);
            return null;
        }
        LocalDate today = LocalDate.now(clock.withZone(MarketConstants.KST));
        if (!marketCalendar.isTradingDay(today)) {
            log.info("KODEX-HOLD: 휴장일({}) — 이번 스케줄 스킵", today);
            return null;
        }
        String symbol = properties.symbol();
        StockCode code = new StockCode(symbol);
        if (strategyBook.holds(STRATEGY_ID, code)) {
            publishDecision(code, "HOLD", "보유 중 — 지수 보유 전략은 스스로 팔지 않는다(경로 C, ADR-16)", Map.of());
            return "KODEX-HOLD 판단 %s — %s 보유 중 · 신호 없음".formatted(today, StockNames.label(symbol));
        }
        BigDecimal ask = bestAsk(symbol);
        publisher.publishEvent(new Signal(STRATEGY_ID, code, Side.BUY, ask == null ? null : new Price(ask), 1.0,
                clock.instant()));
        publishDecision(code, "BUY", "미보유 — 슬리브 전액 매수 신호(경로 C, ADR-16)",
                Map.of("refPrice", ask == null ? "없음" : ask.toPlainString()));
        return "KODEX-HOLD 판단 %s — %s 미보유 · 매수 신호(기준가 %s)".formatted(today, StockNames.label(symbol),
                ask == null ? "없음 — 리스크 관문이 거부" : ask.toPlainString());
    }

    /** 최우선 매도호가, 없으면 현재가, 둘 다 없으면 null(리스크 관문이 사이징에서 거부한다) — C3와 같은 순서. */
    private BigDecimal bestAsk(String symbol) {
        try {
            BigDecimal ask = marketData.bestQuote(symbol).bestAsk();
            if (ask != null && ask.signum() > 0) {
                return ask;
            }
        } catch (RuntimeException e) {
            log.warn("KODEX-HOLD: {} 최우선 매도호가 조회 실패: {}", StockNames.label(symbol), e.getMessage());
        }
        try {
            BigDecimal current = marketData.stockQuote(symbol).current();
            if (current != null && current.signum() > 0) {
                log.warn("KODEX-HOLD: {} 최우선 매도호가 없음 — 현재가 {}로 매수 신호", StockNames.label(symbol), current);
                return current;
            }
        } catch (RuntimeException e) {
            log.warn("KODEX-HOLD: {} 현재가 조회 실패: {}", StockNames.label(symbol), e.getMessage());
        }
        log.error("KODEX-HOLD: {} 호가·현재가 모두 없음 — 기준가 없이 매수 신호(리스크 관문이 거부한다)", StockNames.label(symbol));
        return null;
    }

    private void publishDecision(StockCode code, String conclusion, String reason, Map<String, String> metrics) {
        publisher.publishEvent(new SignalDecision(HORIZON, STRATEGY_ID, code, conclusion, reason, metrics, clock.instant()));
    }
}
