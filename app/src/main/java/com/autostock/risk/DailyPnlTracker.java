package com.autostock.risk;

import com.autostock.common.event.DayStartMarks;
import com.autostock.common.event.Fill;
import com.autostock.common.event.PositionRestored;
import com.autostock.common.event.Side;
import com.autostock.common.util.MarketConstants;
import com.autostock.common.util.Price;
import com.autostock.common.util.StockCode;
import com.autostock.common.util.StockNames;
import jakarta.annotation.PostConstruct;
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
 * 일 손실 한도 추적기 — 오늘 손익을 누적해 {@code risk.daily-max-loss-pct}(기본 -2%)에
 * 도달하면 {@link KillSwitch}를 작동시킨다(PLAN 8절, 모의 무인 운영 전 필수 안전장치).
 *
 * <h2>두 가지 손익(2026-10-13)</h2>
 * <ul>
 *   <li><b>한도용 오늘 손익</b>({@link #todayPnlForLimit()}) — 기준가 대비. 밤사이 들고 온 보유분의 기준가는 전일 종가,
 *       오늘 산 분량은 매수가다. 킬스위치는 이 값으로 판단한다.</li>
 *   <li><b>실현손익</b>({@link #todayRealizedPnl()}) — 매입 평단 대비(예전과 같다). 일일 리포트·화면에 보인다.</li>
 * </ul>
 * 배경: 예전에는 한도도 매입 평단 기준이었다. 그래서 며칠에 걸쳐 쌓인 평가손실이 청산하는 날 한꺼번에 "오늘 손실"이 됐다 —
 * 10/13 09:05 C3 국면 OFF 강제 청산에서 실현손익 -1,097만 원(한도 -977만 원)으로 킬스위치가 켜졌고, 오늘 하루 움직임은
 * 작았다. 킬스위치는 매도 신호도 막으므로, 청산 도중에 켜지면 일부만 팔린 채 남는다(aiDoc/upgrade-2026-10/16-log-review-2026-10-13.md).
 * 기준가는 잔고 복원({@link PositionRestored#previousClose()})과 거래일 아침 {@link DayStartMarks}(execution.PositionRestorer)로
 * 잡는다. 전일 종가를 모르면 매입 평단을 기준가로 쓴다 — 예전처럼 보수적이다(경고 로그).
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
 * 손익 = (체결가 − 기준가) × 수량 − 왕복 추정비용. 왕복 추정비용은 매수쪽
 * (기준가×수량×feeRate)과 매도쪽(체결가×수량×(feeRate+sellTaxRate))을 더한 근사치다 —
 * 실제 매수 시점의 수수료가 아니라 "지금 시점의 기준가로 역산한 추정값"이라는 한계가 있다
 * (자체 장부가 개별 매수 체결의 수수료를 따로 저장하지 않기 때문). 정밀 계산이 아니라
 * "킬스위치를 켤지 말지"를 위한 보수적 추정이므로 이 정도 근사로 충분하다고 판단했다.
 *
 * <h2>날짜 롤오버</h2>
 * {@link DailyLimitTracker}와 같은 패턴(KST 자정 기준)을 따르되, 값이 int 카운터가 아니라
 * BigDecimal 누적합이라 여러 필드를 함께 갱신해야 해서 AtomicReference 대신
 * {@link ReentrantLock}으로 묶었다({@code monitor.EventFeed}와 같은 이유로 synchronized
 * 대신 ReentrantLock을 쓴다 — 가상 스레드 pinning 회피, 다만 이 락 내부 연산은 순수 BigDecimal
 * 계산이라 애초에 블로킹이 없다). 기준가는 자정에 바꾸지 않는다 — 다음 {@link DayStartMarks}가 올 때까지
 * 전날 기준가를 쓴다(이틀치 움직임이 잡힐 수 있어 보수적이다).
 *
 * <h2>재기동 복원 (Phase 0.2, 사용자 결정 D-05 — aiDoc/risk-state-persistence.md)</h2>
 * 누계가 바뀔 때마다 오늘(KST) 행을 {@code risk_daily_pnl}에 저장하고(실현손익 + 한도용 손익, V17), 빈 초기화 때
 * 오늘 행으로 복원한다(다른 날짜 행은 읽지 않는다). 한도용 손익이 없는 예전 행이면 실현손익으로 복원한다(보수적).
 * 복원만으로는 킬스위치를 켜지 않는다 — 한도 도달로 작동했던 킬스위치는 그 자체가 복원되고, 사람이 해제했다면
 * 그 판단을 존중한다(다음 손실 체결에서 다시 검사). 원가 장부({@link Lot})는 브로커 잔고 복원 이벤트({@link PositionRestored})로
 * 시드한다. 저장·복원 실패는 ERROR 로그만 남기고 메모리 계산을 계속한다(한도 판단이 멈추지 않게).
 *
 * <h2>한계(TODO)</h2>
 * 여기서 보는 것은 체결로 확정된 손익뿐이다. 보유 중인 포지션의 장중 평가손익은 포함하지 않는다 —
 * 실시간 시세 연동이 있어야 계산할 수 있으므로 TODO로 남긴다(킬스위치 사유 메시지에도 명시).
 */
@Component
public class DailyPnlTracker {

    private static final Logger log = LoggerFactory.getLogger(DailyPnlTracker.class);

    /**
     * 자체 미니 장부의 한 종목 상태 — 수량·매입 평단(실현손익용)·오늘 기준가(한도용). PositionBook.Position과 별개다.
     */
    private record Lot(long quantity, BigDecimal avgPrice, BigDecimal mark) {
    }

    private final RiskProperties properties;
    private final EquitySource equitySource;
    private final KillSwitch killSwitch;
    private final RiskStateStore store;
    private final Clock clock;

    /** 종목별 평단·수량·기준가 자체 장부 — PositionBook과 별개(클래스 설명 참고). */
    private final Map<StockCode, Lot> lots = new ConcurrentHashMap<>();

    private final ReentrantLock lock = new ReentrantLock();
    private LocalDate currentDay;
    private BigDecimal realizedPnlToday = BigDecimal.ZERO;
    private BigDecimal limitPnlToday = BigDecimal.ZERO;

    public DailyPnlTracker(RiskProperties properties, EquitySource equitySource,
                           KillSwitch killSwitch, RiskStateStore store, Clock clock) {
        this.properties = properties;
        this.equitySource = equitySource;
        this.killSwitch = killSwitch;
        this.store = store;
        this.clock = clock;
        this.currentDay = today();
    }

    /** 오늘(KST) 저장된 누계로 복원한다 — 빈 초기화 때 1회(클래스 설명 "재기동 복원"). */
    @PostConstruct
    void restoreToday() {
        lock.lock();
        try {
            LocalDate day = today();
            currentDay = day;
            store.loadDailyPnl(day).ifPresent(saved -> {
                realizedPnlToday = saved.realizedPnl();
                limitPnlToday = saved.limitPnl() != null ? saved.limitPnl() : saved.realizedPnl();
                log.info("DailyPnlTracker: 오늘({}) 누계 복원 — 한도용 손익 {}원, 실현손익 {}원{}", day, limitPnlToday,
                        realizedPnlToday, saved.limitPnl() == null ? "(예전 행 — 한도용도 실현손익으로)" : "");
            });
        } catch (RuntimeException e) {
            log.error("DailyPnlTracker: 오늘 손익 누계 복원 실패 — 0에서 시작한다(재기동 전 손실이 한도 계산에서 빠질 수 있음)", e);
        } finally {
            lock.unlock();
        }
    }

    @EventListener
    public void onFill(Fill fill) {
        if (fill.side() == Side.BUY) {
            recordBuy(fill);
        } else {
            recordSell(fill);
        }
    }

    /**
     * 브로커 잔고 복원 포지션으로 원가 장부를 시드한다(클래스 설명 "재기동 복원"). PositionBook.onPositionRestored와
     * 같은 규칙 — putIfAbsent: 이 JVM에서 이미 체결로 만든 장부가 더 최신이다. 평단 미상(null)이면 시드하지 않는다
     * (그 종목 매도는 기존대로 손익 계산을 건너뛰고 경고한다). 기준가는 전일 종가, 모르면 매입 평단이다.
     */
    @EventListener
    public void onPositionRestored(PositionRestored restored) {
        if (restored.quantity() <= 0) {
            return;
        }
        if (restored.avgPrice() == null) {
            log.warn("DailyPnlTracker: {} 평단 미상 — 원가 장부 시드 생략(이 종목 매도의 손익은 계산되지 않는다)",
                    StockNames.label(restored.symbol()));
            return;
        }
        BigDecimal avg = restored.avgPrice().value();
        BigDecimal mark = markOrAverage(restored.symbol(), restored.previousClose(), avg);
        Lot previous = lots.putIfAbsent(restored.symbol(), new Lot(restored.quantity(), avg, mark));
        if (previous == null) {
            log.info("DailyPnlTracker: 원가 장부 시드(브로커 잔고): {} {}주 @ {} · 일 손실 기준가 {}",
                    StockNames.label(restored.symbol()), restored.quantity(), restored.avgPrice(), mark);
        }
    }

    /**
     * 거래일 아침 기준가 — 밤사이 들고 온 보유분의 기준가를 전일 종가로 맞춘다(클래스 설명 "두 가지 손익"). 오늘 날짜가 아닌
     * 이벤트는 버린다. 장부에 없는 보유는 평단을 알면 시드한다(복원을 놓친 경우). 전일 종가를 모르는 종목은 기준가를 두지 않는다.
     */
    @EventListener
    public void onDayStartMarks(DayStartMarks event) {
        lock.lock();
        try {
            rollDayIfNeeded();
            if (!event.day().equals(currentDay)) {
                log.warn("DailyPnlTracker: 오늘({})이 아닌 기준가({}) — 무시", currentDay, event.day());
                return;
            }
            int marked = 0;
            for (DayStartMarks.Mark mark : event.marks()) {
                if (mark.previousClose() == null) {
                    log.warn("DailyPnlTracker: {} 전일 종가 미상 — 기준가를 바꾸지 않는다", StockNames.label(mark.symbol()));
                    continue;
                }
                BigDecimal close = mark.previousClose().value();
                Lot updated = lots.computeIfPresent(mark.symbol(),
                        (symbol, lot) -> new Lot(lot.quantity(), lot.avgPrice(), close));
                if (updated == null && mark.avgPrice() != null && mark.quantity() > 0) {
                    updated = lots.putIfAbsent(mark.symbol(), new Lot(mark.quantity(), mark.avgPrice().value(), close));
                    updated = updated == null ? lots.get(mark.symbol()) : updated;
                }
                if (updated != null) {
                    marked++;
                }
            }
            log.info("DailyPnlTracker: {} 일 손실 기준가 갱신(전일 종가) — {}종목", currentDay, marked);
        } finally {
            lock.unlock();
        }
    }

    private static BigDecimal markOrAverage(StockCode symbol, Price previousClose, BigDecimal avg) {
        if (previousClose != null) {
            return previousClose.value();
        }
        log.warn("DailyPnlTracker: {} 전일 종가 미상 — 일 손실 기준가를 매입 평단으로 둔다(밤사이 손실까지 오늘 손실로 잡힐 수 있다)",
                StockNames.label(symbol));
        return avg;
    }

    /** 매수 체결 반영 — PositionBook.onFill의 매수 가지와 같은 가중평균. 기준가도 매수가로 가중평균한다(오늘 산 분량). */
    private void recordBuy(Fill fill) {
        BigDecimal price = fill.fillPrice().value();
        long qty = fill.filledQuantity().value();
        lots.compute(fill.symbol(), (symbol, current) -> {
            if (current == null) {
                return new Lot(qty, price, price);
            }
            long newQty = current.quantity() + qty;
            return new Lot(newQty, weighted(current.avgPrice(), current.quantity(), price, qty, newQty),
                    weighted(current.mark(), current.quantity(), price, qty, newQty));
        });
    }

    private static BigDecimal weighted(BigDecimal oldValue, long oldQty, BigDecimal price, long qty, long newQty) {
        BigDecimal total = oldValue.multiply(BigDecimal.valueOf(oldQty)).add(price.multiply(BigDecimal.valueOf(qty)));
        return total.divide(BigDecimal.valueOf(newQty), 2, RoundingMode.HALF_UP);
    }

    /**
     * 매도 체결 반영 — 갱신 전 평단·기준가로 손익을 계산한 뒤 장부를 줄인다(또는 제거한다).
     * 자체 장부에 없는 심볼의 매도(재시작 직후 등, 클래스 설명의 한계와 별개의 상황)는
     * 손익 계산을 스킵하고 경고만 남긴다 — 잘못된 평단으로 왜곡된 값을 누적시키는 것보다 낫다.
     */
    private void recordSell(Fill fill) {
        StockCode symbol = fill.symbol();
        Lot current = lots.get(symbol);
        if (current == null) {
            log.warn("DailyPnlTracker: 자체 장부에 없는 종목의 매도 체결 — 손익 계산 스킵(재시작 등으로 장부 유실 가능): {}",
                    StockNames.label(symbol));
            return;
        }

        long soldQty = fill.filledQuantity().value();
        long remaining = current.quantity() - soldQty;
        if (remaining <= 0) {
            lots.remove(symbol);
        } else {
            lots.put(symbol, new Lot(remaining, current.avgPrice(), current.mark()));
        }

        BigDecimal price = fill.fillPrice().value();
        addPnl(netPnl(price, current.avgPrice(), soldQty), netPnl(price, current.mark(), soldQty));
    }

    /** (체결가 − 기준) × 수량 − 왕복 추정비용(클래스 설명 "비용 반영"). 기준은 갱신 전 값이어야 한다. */
    private BigDecimal netPnl(BigDecimal fillPrice, BigDecimal basis, long soldQty) {
        BigDecimal qty = BigDecimal.valueOf(soldQty);
        BigDecimal grossPnl = fillPrice.subtract(basis).multiply(qty);
        BigDecimal buyNotional = basis.multiply(qty);
        BigDecimal sellNotional = fillPrice.multiply(qty);
        BigDecimal roundTripCost = buyNotional.multiply(BigDecimal.valueOf(properties.feeRate()))
                .add(sellNotional.multiply(BigDecimal.valueOf(properties.feeRate() + properties.sellTaxRate())));
        return grossPnl.subtract(roundTripCost);
    }

    private void addPnl(BigDecimal realized, BigDecimal forLimit) {
        lock.lock();
        try {
            rollDayIfNeeded();
            realizedPnlToday = realizedPnlToday.add(realized);
            limitPnlToday = limitPnlToday.add(forLimit);
            persistToday();
            checkLimit();
        } finally {
            lock.unlock();
        }
    }

    /** 호출자가 이미 lock을 쥔 상태에서만 불러야 한다 — 락 안에서 저장해 여러 체결의 저장 순서가 누계 순서와 같게 한다. */
    private void persistToday() {
        try {
            store.saveDailyPnl(currentDay, realizedPnlToday, limitPnlToday, clock.instant());
        } catch (RuntimeException e) {
            log.error("DailyPnlTracker: 손익 누계 저장 실패({} 한도용 {}원·실현 {}원) — 메모리 누계로 한도 판단은 계속한다",
                    currentDay, limitPnlToday, realizedPnlToday, e);
        }
    }

    /** 호출자가 이미 lock을 쥔 상태에서만 불러야 한다. */
    private void rollDayIfNeeded() {
        LocalDate now = today();
        if (!now.equals(currentDay)) {
            currentDay = now;
            realizedPnlToday = BigDecimal.ZERO;
            limitPnlToday = BigDecimal.ZERO;
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(MarketConstants.KST));
    }

    /**
     * 일 손실 한도 도달 여부를 한도용 손익(기준가 대비)으로 검사하고, 도달했으면 킬스위치를 켠다.
     * 장중 평가손익은 포함하지 않는다 — TODO(시세 연동 필요, 클래스 설명 참고).
     */
    private void checkLimit() {
        BigDecimal equity = equitySource.equity();
        if (equity == null || equity.signum() <= 0) {
            log.warn("DailyPnlTracker: equity 값이 유효하지 않아 한도 검사를 건너뜀: {}", equity);
            return;
        }
        // dailyMaxLossPct는 음수(예: -0.02)이므로 threshold도 음수 — "이 값보다 더 밑으로 내려가면 위험".
        BigDecimal threshold = equity.multiply(BigDecimal.valueOf(properties.dailyMaxLossPct()));
        if (limitPnlToday.compareTo(threshold) <= 0) {
            killSwitch.engage(("일 손실 한도 도달: 오늘 손익 %s원(밤사이 보유분은 전일 종가 기준, 한도 %s원, equity=%s원 · "
                    + "매입가 기준 실현손익 %s원) — 장중 평가손익 미포함(TODO: 시세 연동 필요)")
                    .formatted(limitPnlToday.setScale(0, RoundingMode.HALF_UP), threshold.setScale(0, RoundingMode.HALF_UP),
                            equity.setScale(0, RoundingMode.HALF_UP), realizedPnlToday.setScale(0, RoundingMode.HALF_UP)));
        }
    }

    /** 오늘 누적 실현손익(매입 평단 대비) — 일일 리포트(monitor.DailyReportScheduler)·화면 공개용. */
    public BigDecimal todayRealizedPnl() {
        lock.lock();
        try {
            rollDayIfNeeded();
            return realizedPnlToday;
        } finally {
            lock.unlock();
        }
    }

    /** 오늘 한도용 손익(밤사이 보유분은 전일 종가, 오늘 산 분량은 매수가 대비) — 킬스위치 판단 기준. */
    public BigDecimal todayPnlForLimit() {
        lock.lock();
        try {
            rollDayIfNeeded();
            return limitPnlToday;
        } finally {
            lock.unlock();
        }
    }
}
