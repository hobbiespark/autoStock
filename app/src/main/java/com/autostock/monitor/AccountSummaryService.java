package com.autostock.monitor;

import com.autostock.common.util.MarketConstants;
import com.autostock.common.util.StockCode;
import com.autostock.execution.BrokerBalance;
import com.autostock.execution.BrokerHolding;
import com.autostock.execution.BrokerPort;
import com.autostock.market.MarketCalendarService;
import com.autostock.monitor.view.AccountView;
import com.autostock.portfolio.PositionBook;
import com.autostock.risk.OpenOrderQuery;
import com.autostock.risk.TradingCapitalProperties;
import com.autostock.trading.TradingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 대시보드 계좌 잔고(2026-10-23) — 사용자 "계좌 잔고 표시(보유종목 없으면 계좌금액) 되어야 함".
 *
 * <p>kt00018 잔고를 <b>필요할 때만</b>(대시보드가 부를 때) 읽고, 거래일에는 30초·휴장일에는 10분 안에 다시 부르면 마지막 값을 준다
 * — 대시보드는 2초마다 폴링하므로 그대로 브로커를 부르면 TR 한도(종류마다 초당 1건)를 낭비한다. 조회가 실패하면 마지막 성공값을
 * 그대로 두고 사유를 {@link AccountView#note()}에 적는다. 실패 경고는 상태가 바뀔 때 한 번만(주말 모의 서버 점검 때 로그가 쌓이지 않게).
 *
 * <p>매매 판단에는 쓰지 않는다 — 사이징·일 손실 한도는 execution.BrokerEquitySource(60초 캐시), 운용 자금 한도는 RiskGate가 따로 센다.
 * 여기의 운용 자금 사용액은 RiskGate와 같은 셈(앱 장부의 보유 매수 원가 + 미체결 매수 남은 금액)을 화면에 보이는 것이다.
 */
@Component
public class AccountSummaryService {

    private static final Logger log = LoggerFactory.getLogger(AccountSummaryService.class);

    static final Duration TRADING_DAY_TTL = Duration.ofSeconds(30);
    static final Duration OFF_DAY_TTL = Duration.ofMinutes(10);

    private final BrokerPort brokerPort;
    private final TradingProperties tradingProperties;
    private final MarketCalendarService calendar;
    private final PositionBook positionBook;
    private final OpenOrderQuery openOrderQuery;
    private final TradingCapitalProperties capital;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();   // 브로커 조회(블로킹) — 가상 스레드 고정을 피해 synchronized 대신

    private volatile BrokerBalance lastBalance;
    private volatile Instant lastBalanceAt;
    private volatile String lastError;
    private Instant lastAttemptAt = Instant.EPOCH;

    public AccountSummaryService(BrokerPort brokerPort, TradingProperties tradingProperties, MarketCalendarService calendar,
                                 PositionBook positionBook, OpenOrderQuery openOrderQuery,
                                 TradingCapitalProperties capital, Clock clock) {
        this.brokerPort = brokerPort;
        this.tradingProperties = tradingProperties;
        this.calendar = calendar;
        this.positionBook = positionBook;
        this.openOrderQuery = openOrderQuery;
        this.capital = capital == null ? TradingCapitalProperties.none() : capital;
        this.clock = clock;
    }

    /** 계좌 잔고 View — 필요하면 잔고를 다시 읽는다(클래스 설명의 주기). */
    public AccountView account() {
        BigDecimal heldCost = heldCost();
        BigDecimal pendingBuy = pendingBuy();
        if (tradingProperties.mode() != TradingProperties.Mode.LIVE) {
            return new AccountView(false, null, null, null, null, null, "SIM — 브로커 잔고 없음",
                    capital.limitKrw(), heldCost, pendingBuy);
        }
        refreshIfDue();
        BrokerBalance balance = lastBalance;
        String error = lastError;
        String note = error == null ? null
                : "잔고 조회 실패 — " + error + (balance == null ? "" : " (마지막으로 읽은 값)");
        if (balance == null) {
            return new AccountView(false, null, null, null, null, null, note == null ? "잔고 확인 중" : note,
                    capital.limitKrw(), heldCost, pendingBuy);
        }
        return new AccountView(true, balance.estimatedDepositAsset(), balance.totalEvaluationAmount(),
                balance.totalPurchaseAmount(), balance.totalProfitLoss(), lastBalanceAt, note,
                capital.limitKrw(), heldCost, pendingBuy);
    }

    /** 마지막으로 읽은 잔고의 종목별 보유(현재가 표시용) — 조회하지 않는다. 아직 못 읽었으면 빈 맵. */
    public Map<StockCode, BrokerHolding> latestHoldings() {
        BrokerBalance balance = lastBalance;
        Map<StockCode, BrokerHolding> bySymbol = new LinkedHashMap<>();
        if (balance != null) {
            balance.holdings().forEach(h -> bySymbol.put(h.symbol(), h));
        }
        return bySymbol;
    }

    private void refreshIfDue() {
        lock.lock();
        try {
            Instant now = clock.instant();
            boolean tradingDay = calendar.isTradingDay(LocalDate.now(clock.withZone(MarketConstants.KST)));
            Duration ttl = tradingDay ? TRADING_DAY_TTL : OFF_DAY_TTL;
            if (Duration.between(lastAttemptAt, now).compareTo(ttl) < 0) {
                return;
            }
            lastAttemptAt = now;
            try {
                BrokerBalance fresh = brokerPort.balance();
                lastBalance = fresh;
                lastBalanceAt = now;
                if (lastError != null) {
                    log.info("대시보드 잔고 조회 회복");
                }
                lastError = null;
            } catch (RuntimeException e) {
                if (lastError == null) {
                    log.warn("대시보드 잔고 조회 실패 — {}초 뒤 다시(그동안 마지막 값 표시): {}", ttl.toSeconds(), e.getMessage());
                } else {
                    log.debug("대시보드 잔고 조회 실패(계속): {}", e.getMessage());
                }
                lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
        } finally {
            lock.unlock();
        }
    }

    /** 계좌 보유 매수 원가 — 앱 장부(평단 × 수량, 평단 모르는 종목 제외). RiskGate 운용 자금 한도와 같은 셈. */
    private BigDecimal heldCost() {
        BigDecimal held = BigDecimal.ZERO;
        for (PositionBook.Position position : positionBook.snapshot().values()) {
            if (position.avgPrice() != null && position.quantity() > 0) {
                held = held.add(position.avgPrice().multiply(BigDecimal.valueOf(position.quantity())));
            }
        }
        return held;
    }

    /** 미체결 매수 남은 금액 — 못 세면 null. */
    private BigDecimal pendingBuy() {
        try {
            return openOrderQuery.openBuyAmount();
        } catch (RuntimeException e) {
            log.debug("대시보드 미체결 매수 금액 조회 실패: {}", e.toString());
            return null;
        }
    }
}
