package com.autostock.risk;

import com.autostock.common.event.Fill;
import com.autostock.common.event.Side;
import com.autostock.common.util.MarketConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 일 손실 한도 추적기 — 오늘 실현손익을 누적해 {@code risk.daily-max-loss-pct}(기본 -2%)에
 * 도달하면 {@link KillSwitch}를 작동시킨다(PLAN 8절, 모의 무인 운영 전 필수 안전장치).
 *
 * <h2>왜 PositionBook의 평단을 그대로 쓰지 않고 자체 미니 장부를 따로 두나</h2>
 * {@link com.autostock.portfolio.PositionBook}(ADR-6 재편으로 portfolio 모듈 소유)도 이 클래스도
 * 같은 {@link Fill} 이벤트를 구독한다. 매도 체결이 오면
 * "체결가 - 평단"으로 실현손익을 계산해야 하는데, 그 평단은 <b>이 매도로 갱신되기 전의
 * 값</b>이어야 한다. 문제는 Spring의 {@code @EventListener}는 리스너 등록 순서(빈 생성 순서)에
 * 따라 호출 순서가 정해지고, 그 순서는 코드만 봐서는 보장되지 않는다 — 만약 PositionBook이
 * 먼저 갱신된 뒤 이 클래스가 나중에 호출되면 이미 줄어든/사라진 포지션을 보게 되어 손익
 * 계산이 틀어진다(부분 매도는 평단이 그대로라 괜찮지만, 전량 매도는 포지션 자체가
 * 맵에서 삭제되어 버린다).
 *
 * <p>가장 안전한 해법은 "리스너 순서에 의존하지 않는 것"이다. 그래서 이 클래스는
 * PositionBook과 똑같은 가중평균 로직을 자체 {@link Lot} 맵으로 중복 구현한다.
 * 코드 중복이라는 단점이 있지만, 리스너 순서 버그(재현이 어렵고 찾기 힘든 종류의 버그)보다는
 * 안전하다고 판단했다 — PositionBook과 로직이 갈라지면 안 되므로 수정 시 둘 다 확인할 것.
 *
 * <h2>비용 반영</h2>
 * 실현손익 = (체결가 − 평단) × 수량 − 왕복 추정비용. 왕복 추정비용은 매수쪽
 * (평단×수량×feeRate)과 매도쪽(체결가×수량×(feeRate+sellTaxRate))을 더한 근사치다 —
 * 실제 매수 시점의 수수료가 아니라 "지금 시점의 평단으로 역산한 추정값"이라는 한계가 있다
 * (자체 장부가 개별 매수 체결의 수수료를 따로 저장하지 않기 때문). 정밀 계산이 아니라
 * "킬스위치를 켤지 말지"를 위한 보수적 추정이므로 이 정도 근사로 충분하다고 판단했다.
 *
 * <h2>날짜 롤오버</h2>
 * {@link DailyLimitTracker}와 같은 패턴(KST 자정 기준)을 따르되, 값이 int 카운터가 아니라
 * BigDecimal 누적합이라 여러 필드를 함께 갱신해야 해서 AtomicReference 대신
 * {@link ReentrantLock}으로 묶었다({@code monitor.EventFeed}와 같은 이유로 synchronized
 * 대신 ReentrantLock을 쓴다 — 가상 스레드 pinning 회피, 다만 이 락 내부 연산은 순수 BigDecimal
 * 계산이라 애초에 블로킹이 없다).
 *
 * <h2>한계(TODO)</h2>
 * 여기서 보는 것은 "실현"손익뿐이다. 보유 중인 포지션의 미실현 평가손익은 포함하지 않는다 —
 * 실시간 시세 연동이 있어야 계산할 수 있으므로 TODO로 남긴다(클래스 설명 참고,
 * 킬스위치 사유 메시지에도 명시).
 */
@Component
public class DailyPnlTracker {

    private static final Logger log = LoggerFactory.getLogger(DailyPnlTracker.class);

    /** 자체 미니 장부의 한 종목 상태 — PositionBook.Position과 구조는 같지만 별도 인스턴스다(클래스 설명 참고). */
    private record Lot(long quantity, BigDecimal avgPrice) {
    }

    private final RiskProperties properties;
    private final EquitySource equitySource;
    private final KillSwitch killSwitch;
    private final Clock clock;

    /** 종목별 평단·수량 자체 장부 — PositionBook과 별개(클래스 설명 참고). */
    private final Map<String, Lot> lots = new ConcurrentHashMap<>();

    private final ReentrantLock lock = new ReentrantLock();
    private LocalDate currentDay;
    private BigDecimal realizedPnlToday = BigDecimal.ZERO;

    public DailyPnlTracker(RiskProperties properties, EquitySource equitySource,
                           KillSwitch killSwitch, Clock clock) {
        this.properties = properties;
        this.equitySource = equitySource;
        this.killSwitch = killSwitch;
        this.clock = clock;
        this.currentDay = today();
    }

    @EventListener
    public void onFill(Fill fill) {
        if (fill.side() == Side.BUY) {
            recordBuy(fill);
        } else {
            recordSell(fill);
        }
    }

    /** 매수 체결 반영 — PositionBook.onFill의 매수 가지와 동일한 가중평균 로직. */
    private void recordBuy(Fill fill) {
        lots.compute(fill.symbol(), (symbol, current) -> {
            if (current == null) {
                return new Lot(fill.filledQuantity(), fill.fillPrice());
            }
            long newQty = current.quantity() + fill.filledQuantity();
            BigDecimal totalCost = current.avgPrice().multiply(BigDecimal.valueOf(current.quantity()))
                    .add(fill.fillPrice().multiply(BigDecimal.valueOf(fill.filledQuantity())));
            BigDecimal avg = totalCost.divide(BigDecimal.valueOf(newQty), 2, RoundingMode.HALF_UP);
            return new Lot(newQty, avg);
        });
    }

    /**
     * 매도 체결 반영 — 갱신 전 평단으로 실현손익을 계산한 뒤 장부를 줄인다(또는 제거한다).
     * 자체 장부에 없는 심볼의 매도(재시작 직후 등, 클래스 설명의 한계와 별개의 상황)는
     * 손익 계산을 스킵하고 경고만 남긴다 — 잘못된 평단으로 왜곡된 값을 누적시키는 것보다 낫다.
     */
    private void recordSell(Fill fill) {
        Lot current = lots.get(fill.symbol());
        if (current == null) {
            log.warn("DailyPnlTracker: 자체 장부에 없는 종목의 매도 체결 — 실현손익 계산 스킵(재시작 등으로 장부 유실 가능): {}",
                    fill.symbol());
            return;
        }

        BigDecimal avgPriceBeforeThisFill = current.avgPrice(); // "갱신 전 평단" — 클래스 설명 핵심
        long soldQty = fill.filledQuantity();

        long remaining = current.quantity() - soldQty;
        if (remaining <= 0) {
            lots.remove(fill.symbol());
        } else {
            lots.put(fill.symbol(), new Lot(remaining, avgPriceBeforeThisFill));
        }

        BigDecimal qty = BigDecimal.valueOf(soldQty);
        BigDecimal grossPnl = fill.fillPrice().subtract(avgPriceBeforeThisFill).multiply(qty);

        BigDecimal buyNotional = avgPriceBeforeThisFill.multiply(qty);
        BigDecimal sellNotional = fill.fillPrice().multiply(qty);
        BigDecimal roundTripCost = buyNotional.multiply(BigDecimal.valueOf(properties.feeRate()))
                .add(sellNotional.multiply(BigDecimal.valueOf(properties.feeRate() + properties.sellTaxRate())));

        addRealizedPnl(grossPnl.subtract(roundTripCost));
    }

    private void addRealizedPnl(BigDecimal pnl) {
        lock.lock();
        try {
            rollDayIfNeeded();
            realizedPnlToday = realizedPnlToday.add(pnl);
            checkLimit();
        } finally {
            lock.unlock();
        }
    }

    /** 호출자가 이미 lock을 쥔 상태에서만 불러야 한다. */
    private void rollDayIfNeeded() {
        LocalDate now = today();
        if (!now.equals(currentDay)) {
            currentDay = now;
            realizedPnlToday = BigDecimal.ZERO;
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(MarketConstants.KST));
    }

    /**
     * 일 손실 한도 도달 여부를 검사하고, 도달했으면 킬스위치를 켠다.
     * 미실현 손익은 포함하지 않는다 — TODO(시세 연동 필요, 클래스 설명 참고).
     */
    private void checkLimit() {
        BigDecimal equity = equitySource.equity();
        if (equity == null || equity.signum() <= 0) {
            log.warn("DailyPnlTracker: equity 값이 유효하지 않아 한도 검사를 건너뜀: {}", equity);
            return;
        }
        // dailyMaxLossPct는 음수(예: -0.02)이므로 threshold도 음수 — "이 값보다 더 밑으로 내려가면 위험".
        BigDecimal threshold = equity.multiply(BigDecimal.valueOf(properties.dailyMaxLossPct()));
        if (realizedPnlToday.compareTo(threshold) <= 0) {
            killSwitch.engage(
                    "일 손실 한도 도달: 오늘 실현손익 %s원 (한도 %s원, equity=%s원) — 미실현 손익 미포함(TODO: 시세 연동 필요)"
                            .formatted(realizedPnlToday, threshold, equity));
        }
    }

    /** 오늘 누적 실현손익 — 일일 리포트(monitor.DailyReportScheduler) 등 외부 공개용. */
    public BigDecimal todayRealizedPnl() {
        lock.lock();
        try {
            rollDayIfNeeded();
            return realizedPnlToday;
        } finally {
            lock.unlock();
        }
    }
}
