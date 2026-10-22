package com.autostock.risk;

import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.Side;
import com.autostock.common.event.Signal;
import com.autostock.common.event.SignalDecision;
import com.autostock.common.util.ClientOrderId;
import com.autostock.common.util.MarketConstants;
import com.autostock.common.util.Price;
import com.autostock.common.util.Quantity;
import com.autostock.common.util.StockCode;
import com.autostock.common.util.StockNames;
import com.autostock.market.MarketCalendarService;
import com.autostock.portfolio.PositionBook;
import com.autostock.portfolio.StrategyBook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 리스크 게이트 — <b>모든 주문이 반드시 통과해야 하는 유일한 관문</b>.
 *
 * <p>왜 필요한가? 전략(strategy)은 "사고 싶다/팔고 싶다"는 의견(Signal)만 낼 수 있고,
 * 실제 돈이 걸린 주문(OrderRequest)으로 바꾸는 권한은 이 클래스에만 있다.
 * 전략 코드에 버그가 있어도 여기서 걸러지므로 계좌가 보호된다. (PLAN 4절 불변 원칙)
 *
 * <p>검사 순서 — 순서가 중요하다:
 * <ol>
 *   <li><b>장 시간 가드</b>: 정규장(09:00~15:30 KST) 외 시간의 시그널은 거부.
 *       외부 상태(시계)만 보면 되는 가장 값싼 검사라 맨 앞에 둔다.</li>
 *   <li><b>킬스위치</b>: 비상 정지 상태면 무조건 거부.</li>
 *   <li><b>사이징</b>: 몇 주를 살/팔 수 있는지 계산. 0주면 여기서 끝 —
 *       주문 슬롯(일 한도)을 낭비하지 않기 위해 한도 검사보다 먼저 한다. 매수는 이 단계에서
 *       보유·<b>미체결 매수</b>({@link OpenOrderQuery}, 실행 계획 1.2)·동시 보유 한도도 함께 본다.</li>
 *   <li><b>일 주문 한도</b>: 하루 주문 횟수 상한. 폭주(버그로 인한 연속 주문)의 마지막 방어선.</li>
 * </ol>
 *
 * <pre>
 *  Signal ──▶ [장시간?] ──▶ [킬스위치?] ──▶ [사이징 &gt; 0?] ──▶ [일 한도 OK?] ──▶ OrderRequest 발행
 *                │ 거부         │ 거부            │ 거부              │ 거부
 *                ▼             ▼                ▼                  ▼
 *              (로그만 남기고 조용히 버린다 — 예외를 던지지 않는 이유:
 *               시그널 거부는 "정상 동작"이지 오류가 아니기 때문)
 * </pre>
 *
 * <p><b>장 시간 가드 설계</b>: {@code risk.enforce-market-hours}(기본 true)로 켜고 끌 수 있다.
 * SIM 모드 테스트에서 매번 "지금이 장중"이 되도록 시각을 맞추는 건 번거롭고 깨지기 쉬우므로,
 * 테스트는 이 플래그를 false로 준 {@link RiskProperties}를 생성해 가드 자체를 끄고 검증할 수
 * 있게 했다. 실제 장 시간 판정 자체는 {@link Clock}을 주입받아 계산한다 —
 * {@code StaleOrderCanceller}와 같은 이유로, System 시계를 직접 부르지 않아야 테스트가
 * 결정론적이다(가드를 켠 채로 특정 시각을 검증하고 싶을 때 {@link Clock#fixed}로 고정).
 *
 * <p><b>market 모듈 참조</b>: 장 시간 판정은 {@code common.util.TradingCalendar} 정적
 * 호출 대신 {@link MarketCalendarService}(market의 공개 API)를 주입받아 위임한다.
 * risk가 market을 직접 참조하는 것은 {@code monitor}가 {@code risk}를 참조하는 것과
 * 같은 성격의 허용된 모듈 간 참조다(둘 다 어느 모듈에도 {@code allowedDependencies} 제한이
 * 없는 공개 패키지 최상위 타입만 참조 — {@code ModularityTests}가 이 경계를 검증한다).
 *
 * <p><b>portfolio 모듈 참조</b>(ADR-6 재편): 사이징·매도 수량 판단에 쓰는 {@link PositionBook}은
 * risk 소유가 아니라 portfolio 모듈 소유다 — "무엇을 들고 있나"는 portfolio가, "그래서 주문을
 * 허용할지"는 risk가 답한다(책임 분리, ARCHITECTURE.md 2절). 일 손실 한도·킬스위치 등 리스크
 * 한도 판단 장치({@link DailyPnlTracker}, {@link KillSwitch})는 이 클래스와 함께 risk에 남는다.
 *
 * <p><b>거시 국면·공시 배제(PLAN 5절, macro-intel 1단계)</b>: {@link MacroGuard#buyBlockReason()}이 있으면(임계 초과
 * 보수 모드 또는 지표 오래됨 — 실행 계획 1.4) 매수({@link #sizeBuy})를 거부한다(매도는 영향받지 않는다 — 청산은 항상 허용, 보수 모드는
 * "신규 진입만 금지"). {@link DisclosureBlacklist}에 등록된 종목도 같은 자리에서 매수를 거부한다.
 * 두 장치 모두 macrointel이 아니라 risk 소유다 — "한도·차단 판단은 risk 소유" 원칙(risk/package-info.java).
 */
@Component
public class RiskGate {

    private static final Logger log = LoggerFactory.getLogger(RiskGate.class);

    private final ApplicationEventPublisher publisher;
    private final KillSwitch killSwitch;
    private final RiskProperties properties;
    private final PositionSizer sizer;
    private final PositionBook positionBook;
    private final DailyLimitTracker dailyLimits;
    private final EquitySource equitySource;
    private final Clock clock;
    private final MarketCalendarService marketCalendarService;
    private final MacroGuard macroGuard;
    private final DisclosureBlacklist disclosureBlacklist;
    private final OpenOrderQuery openOrderQuery;
    /** 전략별 장부 — 슬리브가 있는 전략의 보유 판단(2026-10-16). null이면 슬리브를 쓰지 않는다(시험용 생성자). */
    private final StrategyBook strategyBook;
    private final MultiStrategyRiskProperties multiStrategy;
    /** 운용 자금 한도(2026-10-21) — 사이징 기준·전체 매수 원가 상한·1주 허용. 꺼져 있으면 예전 동작. */
    private final TradingCapitalProperties capital;

    /** 슬리브 없는 생성자 — 기존 시험용. 운영은 {@link #RiskGate(ApplicationEventPublisher, KillSwitch, RiskProperties, PositionSizer, PositionBook, DailyLimitTracker, EquitySource, Clock, MarketCalendarService, MacroGuard, DisclosureBlacklist, OpenOrderQuery, StrategyBook, MultiStrategyRiskProperties)}. */
    public RiskGate(ApplicationEventPublisher publisher,
                    KillSwitch killSwitch,
                    RiskProperties properties,
                    PositionSizer sizer,
                    PositionBook positionBook,
                    DailyLimitTracker dailyLimits,
                    EquitySource equitySource,
                    Clock clock,
                    MarketCalendarService marketCalendarService,
                    MacroGuard macroGuard,
                    DisclosureBlacklist disclosureBlacklist,
                    OpenOrderQuery openOrderQuery) {
        this(publisher, killSwitch, properties, sizer, positionBook, dailyLimits, equitySource, clock,
                marketCalendarService, macroGuard, disclosureBlacklist, openOrderQuery, null,
                MultiStrategyRiskProperties.none());
    }

    /** 운용 자금 없는 생성자 — 다전략 시험용(RiskGateSleeveTest). 운영은 운용 자금을 받는 아래 생성자. */
    public RiskGate(ApplicationEventPublisher publisher,
                    KillSwitch killSwitch,
                    RiskProperties properties,
                    PositionSizer sizer,
                    PositionBook positionBook,
                    DailyLimitTracker dailyLimits,
                    EquitySource equitySource,
                    Clock clock,
                    MarketCalendarService marketCalendarService,
                    MacroGuard macroGuard,
                    DisclosureBlacklist disclosureBlacklist,
                    OpenOrderQuery openOrderQuery,
                    StrategyBook strategyBook,
                    MultiStrategyRiskProperties multiStrategy) {
        this(publisher, killSwitch, properties, sizer, positionBook, dailyLimits, equitySource, clock,
                marketCalendarService, macroGuard, disclosureBlacklist, openOrderQuery, strategyBook, multiStrategy,
                TradingCapitalProperties.none());
    }

    @Autowired
    public RiskGate(ApplicationEventPublisher publisher,
                    KillSwitch killSwitch,
                    RiskProperties properties,
                    PositionSizer sizer,
                    PositionBook positionBook,
                    DailyLimitTracker dailyLimits,
                    EquitySource equitySource,
                    Clock clock,
                    MarketCalendarService marketCalendarService,
                    MacroGuard macroGuard,
                    DisclosureBlacklist disclosureBlacklist,
                    OpenOrderQuery openOrderQuery,
                    StrategyBook strategyBook,
                    MultiStrategyRiskProperties multiStrategy,
                    TradingCapitalProperties capital) {
        this.capital = capital == null ? TradingCapitalProperties.none() : capital;
        this.strategyBook = strategyBook;
        this.multiStrategy = multiStrategy == null ? MultiStrategyRiskProperties.none() : multiStrategy;
        this.publisher = publisher;
        this.killSwitch = killSwitch;
        this.properties = properties;
        this.sizer = sizer;
        this.positionBook = positionBook;
        this.dailyLimits = dailyLimits;
        this.equitySource = equitySource;
        this.clock = clock;
        this.marketCalendarService = marketCalendarService;
        this.macroGuard = macroGuard;
        this.disclosureBlacklist = disclosureBlacklist;
        this.openOrderQuery = openOrderQuery;
    }

    /**
     * 전략이 발행한 {@link Signal}을 받아 검사 후, 통과 시에만 {@link OrderRequest}를 발행한다.
     *
     * @param signal 전략의 매매 의견 (이 시점에는 아직 아무 돈도 걸려있지 않다)
     */
    @EventListener
    public void onSignal(Signal signal) {
        // ── 0단계: 장 시간 가드 ─────────────────────────────────────────
        // 정규장 외 시간에 들어온 시그널(예: 배치 재처리, 테스트 데이터 오발행 등)은
        // 애초에 체결될 수 없거나 의도치 않은 시점에 주문이 나가는 사고로 이어질 수 있다.
        if (properties.enforceMarketHours() && !isMarketHours()) {
            log.info("장 시간 외 — 시그널 거부: {}", StockNames.label(signal.symbol()));
            publishRejected(signal, "장 시간 외 — 시그널 거부", Map.of());
            return;
        }

        // ── 1단계: 킬스위치 ─────────────────────────────────────────────
        // 텔레그램 명령·일 손실 한도·WS 단절 등 어떤 이유로든 비상 정지 상태면
        // 신규 주문은 전면 차단된다.
        if (killSwitch.isEngaged()) {
            if (reducingSellAllowed(signal)) {
                log.info("킬스위치(일 손실 한도) 작동 중 — 보유 이내 매도는 허용(D-20 b): {} {}", signal.strategyId(),
                        StockNames.label(signal.symbol()));
            } else {
                log.warn("킬스위치 작동 중 — 시그널 거부: {}", StockNames.label(signal.symbol()));
                publishRejected(signal, "킬스위치 작동 중 — 시그널 거부", Map.of());
                return;
            }
        }

        // ── 2단계: 사이징 (몇 주?) ──────────────────────────────────────
        // 매수: 계좌의 일정 비율만 투입 / 매도: 보유 수량 전량 청산.
        // switch 식(expression)을 쓰면 Side에 새 값이 추가될 때 컴파일러가
        // 처리 누락을 잡아준다.
        // 수동 지정 수량(Signal.fixedQuantity, 운영 1일차 ⑦ — 대시보드 폼)이 있으면
        // 자동 사이징 대신 쓰되, 리스크 상한(매수 예산 캡·매도 보유량 캡)은 그대로 적용한다.
        long quantity = signal.fixedQuantity() != null
                ? sizeManual(signal)
                : switch (signal.side()) {
                    case BUY -> sleeveOf(signal).map(s -> sizeSleeveBuy(signal, s)).orElseGet(() -> sizeBuy(signal));
                    case SELL -> sleeveOf(signal).isPresent() && strategyBook != null
                            ? sizeSleeveSell(signal) : sizeSell(signal);
                };
        if (quantity <= 0) {
            return; // 거부 사유는 sizeBuy/sizeSell 안에서 이미 로그로 남겼다
        }

        // ── 2-1단계: 지정가 (호가단위 정규화) ───────────────────────────
        // 실측 2026-09-18: 259,250원 지정가 → RC4003 거부. 전략 계산값·수동 입력값 모두 여기서 눈금에 맞춘다
        // — 브로커 거부 후 재시도보다 주문 전 보정이 슬롯·대사 부담이 없다.
        // 기준가가 없거나 0이면 지정가 주문을 낼 수 없다(매수는 사이징에서 이미 걸러지고, 매도만 여기 온다 —
        // 예: 잔고 복원에서 매입가를 못 찾아 평균단가가 0인 포지션의 강제 청산). 슬롯을 쓰기 전에 거부한다.
        BigDecimal aligned = KrxTickSize.align(refPriceOf(signal));
        if (aligned == null || aligned.signum() <= 0) {
            log.warn("기준가 없음 — 지정가를 정할 수 없어 거부: {} {} refPrice={}",
                    StockNames.label(signal.symbol()), signal.side(), signal.refPrice());
            publishRejected(signal, "기준가 없음 — 지정가를 정할 수 없어 거부",
                    Map.of("refPrice", String.valueOf(signal.refPrice())));
            return;
        }
        Price limitPrice = new Price(aligned);
        if (aligned.compareTo(refPriceOf(signal)) != 0) {
            log.info("호가단위 보정: {} {} 기준가 {} → 지정가 {}", StockNames.label(signal.symbol()), signal.side(),
                    signal.refPrice(), limitPrice);
        }

        // ── 2-2단계: 운용 자금 한도(2026-10-21) ────────────────────────
        // 계좌 보유 매수 원가 + 미체결 매수 + 이번 주문이 운용 자금을 넘지 않게 수량을 줄인다(수동 주문 포함).
        // 지정가가 정해진 뒤라 실제로 걸리는 금액으로 센다. 슬롯을 쓰기 전에 본다.
        if (signal.side() == Side.BUY && capital.limited()) {
            quantity = fitWithinCapital(signal, quantity, aligned);
            if (quantity <= 0) {
                return;
            }
        }

        // ── 3단계: 일 주문 한도 ─────────────────────────────────────────
        // 사이징까지 통과한 "진짜 주문 후보"만 슬롯을 소비한다.
        Optional<DailyLimitTracker.OrderSlot> slot = dailyLimits.tryAcquireOrderSlot();
        if (slot.isEmpty()) {
            log.warn("일 주문 한도 초과 — 거부: {}", StockNames.label(signal.symbol()));
            publishRejected(signal, "일 주문 한도 초과 — 거부",
                    Map.of("quantity", String.valueOf(quantity)));
            return;
        }

        // ── 통과: 주문 요청 발행 ────────────────────────────────────────
        // idempotencyKey(멱등키): 같은 주문이 두 번 실행되는 사고를 막는 고유 번호표.
        // PLAN.md ADR-6 7절 — UUID 대신 사람이 읽을 수 있는 ClientOrderId 포맷을 쓴다
        // ("20260813-BREAKOUT-005930-BUY-005"). OrderRequest.idempotencyKey 필드 자체는
        // 스키마 변경 없이 그대로 재사용한다 — 담기는 문자열의 "형식"만 바뀐 것이다.
        // 날짜와 일련번호는 슬롯이 함께 준 값을 그대로 쓴다(실행 계획 1.2) — 예전처럼 증가 뒤
        // todayOrderCount()를 다시 읽으면 동시에 통과한 두 주문이 같은 번호를 받을 수 있었다.
        String clientOrderId = ClientOrderId.generate(
                slot.get().day(),
                signal.strategyId(),
                signal.symbol().value(),
                signal.side(),
                slot.get().serial()
        ).value();

        publisher.publishEvent(new OrderRequest(
                clientOrderId,
                signal.strategyId(),
                signal.symbol(),
                signal.side(),
                new Quantity(quantity),
                limitPrice,
                clock.instant()));
    }

    /** 시그널 기준가(원). 없으면 null — 사이징(매수)과 지정가 산출(매도)이 각각 거부 사유로 다룬다. */
    private static BigDecimal refPriceOf(Signal signal) {
        return signal.refPrice() == null ? null : signal.refPrice().value();
    }

    /** 사이징 기준 금액 — 운용 자금이 켜져 있으면 min(계좌 평가액, 운용 자금)(2026-10-21). */
    private BigDecimal sizingEquity() {
        return capital.sizingBase(equitySource.equity());
    }

    /**
     * 운용 자금 한도에 맞춘 매수 수량(2026-10-21, aiDoc/multi-strategy-paper-2026-10-16.md 8절). 계좌 보유 매수 원가
     * (계좌 장부 평단 × 수량 — 평단 미상은 세지 못한다) + 미체결 매수 남은 금액 + 이번 주문(수량 × 지정가)이 운용 자금을
     * 넘으면 남은 여유만큼으로 줄인다. 한 주도 못 사거나 미체결 금액을 모르면 거부한다(모르면 사지 않는다).
     */
    private long fitWithinCapital(Signal signal, long quantity, BigDecimal price) {
        BigDecimal held = BigDecimal.ZERO;
        for (PositionBook.Position position : positionBook.snapshot().values()) {
            if (position.avgPrice() != null && position.quantity() > 0) {
                held = held.add(position.avgPrice().multiply(BigDecimal.valueOf(position.quantity())));
            }
        }
        BigDecimal pending;
        try {
            pending = openOrderQuery.openBuyAmount();
        } catch (RuntimeException e) {
            log.warn("미체결 매수 금액 조회 실패 — 매수 거부(운용 자금 한도를 셀 수 없다): {} {}", signal.strategyId(),
                    StockNames.label(signal.symbol()), e);
            publishRejected(signal, "미체결 매수 금액 조회 실패 — 매수 거부", Map.of());
            return 0;
        }
        if (pending == null) {
            pending = BigDecimal.ZERO;
        }
        BigDecimal limit = capital.limit();
        BigDecimal room = limit.subtract(held).subtract(pending);
        long fit = room.signum() <= 0 ? 0 : room.divide(price, 0, java.math.RoundingMode.DOWN).longValue();
        if (fit >= quantity) {
            return quantity;
        }
        String heldText = held.setScale(0, java.math.RoundingMode.HALF_UP).toPlainString();
        String pendingText = pending.setScale(0, java.math.RoundingMode.HALF_UP).toPlainString();
        if (fit <= 0) {
            log.info("운용 자금 한도 — 매수 거부: {} {} {}주 × {}원 (한도 {}원, 보유 원가 {}원 + 미체결 매수 {}원)",
                    signal.strategyId(), StockNames.label(signal.symbol()), quantity, price.toPlainString(),
                    limit.toPlainString(), heldText, pendingText);
            publishRejected(signal, "운용 자금 한도 — 매수 거부", Map.of("capitalLimit", limit.toPlainString(),
                    "heldCost", heldText, "pendingBuy", pendingText, "requestedQuantity", String.valueOf(quantity)));
            return 0;
        }
        log.info("운용 자금 한도 — 매수 수량 {}주 → {}주: {} {} (한도 {}원, 보유 원가 {}원 + 미체결 매수 {}원)", quantity, fit,
                signal.strategyId(), StockNames.label(signal.symbol()), limit.toPlainString(), heldText, pendingText);
        return fit;
    }

    /** 기동 — 다전략 슬리브 설정을 한 줄로 남긴다(설정이 실제로 들어갔는지 로그로 확인). */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void announceSleeves() {
        if (capital.limited()) {
            log.info("운용 자금 {}원 — 사이징 기준 min(계좌 평가액, 운용 자금) · 계좌 보유 매수 원가 + 미체결 매수 + 새 주문 ≤ 운용 자금"
                    + " · 예산으로 0주면 1주 값이 예산의 {}배 이하일 때 1주", "%,d".formatted(capital.limitKrw()),
                    capital.oneShareMaxBudgetMultiple());
        }
        if (multiStrategy.sleeves().isEmpty()) {
            return;
        }
        String sleeves = multiStrategy.sleeves().stream()
                .map(s -> "%s %s%%/%d".formatted(s.strategy(),
                        BigDecimal.valueOf(s.fraction()).movePointRight(2).stripTrailingZeros().toPlainString(),
                        s.maxPositions()))
                .collect(java.util.stream.Collectors.joining(" · "));
        log.info("다전략 슬리브: {} — 일 손실 한도 {}%, 일 주문 {}건, 계좌 동시 보유 {}, 손실 한도 킬스위치 중 보유 매도 {}", sleeves,
                BigDecimal.valueOf(properties.dailyMaxLossPct()).movePointRight(2).stripTrailingZeros().toPlainString(),
                properties.dailyMaxOrders(), properties.maxConcurrentPositions(),
                multiStrategy.allowReducingSellOnLossLimit() ? "허용" : "차단");
    }

    /** 슬리브가 있는 전략인가 — 전략 장부가 없으면(시험용 생성자) 슬리브를 쓰지 않는다. */
    private Optional<MultiStrategyRiskProperties.Sleeve> sleeveOf(Signal signal) {
        if (strategyBook == null) {
            return Optional.empty();
        }
        return multiStrategy.sleeveOf(signal.strategyId());
    }

    /**
     * 킬스위치 중 매도 예외(D-20 b, 2026-10-16) — 설정이 켜져 있고, 킬스위치 사유가 일 손실 한도이고, 매도이며,
     * 팔 보유가 있을 때만. 사이징 단계가 보유 수량 이내로 자른다(매도는 노출을 줄이는 방향이다).
     */
    private boolean reducingSellAllowed(Signal signal) {
        if (!multiStrategy.allowReducingSellOnLossLimit() || signal.side() != Side.SELL) {
            return false;
        }
        boolean lossLimit = killSwitch.engagedReason().map(r -> r.contains(DailyPnlTracker.LOSS_LIMIT_REASON_PREFIX))
                .orElse(false);
        return lossLimit && positionBook.holds(signal.symbol());
    }

    /** 전략 ID 정규화 — ClientOrderId·전략 장부는 대문자다. */
    private static String strategyKey(Signal signal) {
        return signal.strategyId() == null ? "" : signal.strategyId().toUpperCase(Locale.ROOT);
    }

    /**
     * 슬리브 전략의 매수 수량(2026-10-16, aiDoc/multi-strategy-paper-2026-10-16.md 1-1절).
     * <ul>
     *   <li>거시 보수 모드·공시 블랙리스트 — 예전과 같다(계좌 공통)</li>
     *   <li>보유 중복·미체결 매수·동시 보유 — <b>그 전략</b> 기준(다른 전략이 든 종목도 살 수 있다)</li>
     *   <li>계좌 동시 보유 상한({@code risk.max-concurrent-positions}) — 새 종목이 늘어날 때만 센다</li>
     *   <li>예산 = 계좌 평가액 × 슬리브 ÷ 전략 동시 보유 상한 × 신호 비중</li>
     * </ul>
     */
    private long sizeSleeveBuy(Signal signal, MultiStrategyRiskProperties.Sleeve sleeve) {
        Optional<String> macroBlock = macroGuard.buyBlockReason();
        if (macroBlock.isPresent()) {
            log.info("{}: {} {}", macroBlock.get(), signal.strategyId(), StockNames.label(signal.symbol()));
            publishRejected(signal, macroBlock.get(), Map.of());
            return 0;
        }
        if (disclosureBlacklist.isBlacklisted(signal.symbol().value())) {
            log.info("공시 블랙리스트 종목 — 매수 거부: {} {}", signal.strategyId(), StockNames.label(signal.symbol()));
            publishRejected(signal, "공시 블랙리스트 종목 — 매수 거부", Map.of());
            return 0;
        }
        String strategy = strategyKey(signal);
        if (strategyBook.holds(strategy, signal.symbol())) {
            log.info("이 전략이 이미 보유 중 — 추가 매수 차단: {} {}", strategy, StockNames.label(signal.symbol()));
            publishRejected(signal, "이 전략이 이미 보유 중 — 추가 매수 차단", Map.of());
            return 0;
        }
        Set<StockCode> strategyOpenBuys;
        Set<StockCode> accountOpenBuys;
        try {
            strategyOpenBuys = openOrderQuery.symbolsWithOpenOrder(strategy, Side.BUY);
            accountOpenBuys = openOrderQuery.symbolsWithOpenBuy();
        } catch (RuntimeException e) {
            log.warn("미체결 주문 조회 실패 — 매수 거부: {} {}", strategy, StockNames.label(signal.symbol()), e);
            publishRejected(signal, "미체결 주문 조회 실패 — 매수 거부", Map.of());
            return 0;
        }
        if (strategyOpenBuys.contains(signal.symbol())) {
            log.info("이 전략의 미체결 매수 있음 — 추가 매수 차단: {} {}", strategy, StockNames.label(signal.symbol()));
            publishRejected(signal, "이 전략의 미체결 매수 있음 — 추가 매수 차단", Map.of());
            return 0;
        }
        long strategyPending = strategyOpenBuys.stream().filter(s -> !strategyBook.holds(strategy, s)).count();
        int strategyOpen = strategyBook.openPositionCount(strategy);
        if (strategyOpen + strategyPending >= sleeve.maxPositions()) {
            log.info("전략 동시 보유 한도 도달({}) — 매수 거부: {} {} (보유 {}, 미체결 매수 {})", sleeve.maxPositions(),
                    strategy, StockNames.label(signal.symbol()), strategyOpen, strategyPending);
            publishRejected(signal, "전략 동시 보유 한도 도달(" + sleeve.maxPositions() + ") — 매수 거부",
                    Map.of("openPositionCount", String.valueOf(strategyOpen),
                            "pendingBuyCount", String.valueOf(strategyPending)));
            return 0;
        }
        if (!positionBook.holds(signal.symbol()) && !accountOpenBuys.contains(signal.symbol())) {
            long accountPending = accountOpenBuys.stream().filter(s -> !positionBook.holds(s)).count();
            int accountOpen = positionBook.openPositionCount();
            if (accountOpen + accountPending >= properties.maxConcurrentPositions()) {
                log.info("계좌 동시 보유 한도 도달({}) — 매수 거부: {} {}", properties.maxConcurrentPositions(), strategy,
                        StockNames.label(signal.symbol()));
                publishRejected(signal, "계좌 동시 보유 한도 도달(" + properties.maxConcurrentPositions() + ") — 매수 거부",
                        Map.of("openPositionCount", String.valueOf(accountOpen),
                                "pendingBuyCount", String.valueOf(accountPending)));
                return 0;
            }
        }
        BigDecimal equity = sizingEquity();
        double confidence = clampConfidence(signal);
        BigDecimal price = refPriceOf(signal);
        long qty = 0;
        BigDecimal budget = null;
        if (price != null && price.signum() > 0 && equity != null && equity.signum() > 0) {
            // 실수 나눗셈(0.15/3 = 0.0499…)으로 1주가 깎이지 않게 금액 단위로 나눈다
            budget = equity.multiply(BigDecimal.valueOf(sleeve.fraction()))
                    .divide(BigDecimal.valueOf(sleeve.maxPositions()), 10, java.math.RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(confidence));
            qty = budget.divide(price, 0, java.math.RoundingMode.DOWN).longValue();
        }
        if (qty <= 0 && capital.allowsOneShare(budget, price)) {
            // 1주 허용(2026-10-21, 운용 자금 — TradingCapitalProperties). 전체 매수 원가 상한은 2-2단계가 다시 본다
            log.info("예산 {}원 < 1주 {}원 — 1주 허용(예산의 {}배 이내): {} {}",
                    budget.setScale(0, java.math.RoundingMode.DOWN).toPlainString(), price.toPlainString(),
                    capital.oneShareMaxBudgetMultiple(), strategy, StockNames.label(signal.symbol()));
            qty = 1;
        }
        if (qty <= 0) {
            log.info("사이징 결과 0주 — 매수 불가: {} {} (equity={}, 슬리브 {}÷{}, price={}, confidence={})", strategy,
                    StockNames.label(signal.symbol()), equity, sleeve.fraction(), sleeve.maxPositions(),
                    signal.refPrice(), confidence);
            publishRejected(signal, "사이징 결과 0주 — 매수 불가",
                    Map.of("equity", String.valueOf(equity), "confidence", String.valueOf(confidence),
                            "sleeve", String.valueOf(sleeve.fraction())));
        }
        return qty;
    }

    /**
     * 슬리브 전략의 매도 수량 — 그 전략이 든 수량 전부(계좌 수량보다 많으면 계좌 수량까지). 그 전략의 미체결 매도가
     * 이미 있으면 거부한다(같은 청산을 두 번 내지 않게).
     */
    private long sizeSleeveSell(Signal signal) {
        String strategy = strategyKey(signal);
        Optional<PositionBook.Position> mine = strategyBook.position(strategy, signal.symbol());
        if (mine.isEmpty()) {
            log.info("이 전략은 보유하지 않음 — 매도 시그널 무시: {} {}", strategy, StockNames.label(signal.symbol()));
            publishRejected(signal, "이 전략은 보유하지 않음 — 매도 시그널 무시", Map.of());
            return 0;
        }
        try {
            if (openOrderQuery.symbolsWithOpenOrder(strategy, Side.SELL).contains(signal.symbol())) {
                log.info("이 전략의 미체결 매도 있음 — 중복 매도 차단: {} {}", strategy, StockNames.label(signal.symbol()));
                publishRejected(signal, "이 전략의 미체결 매도 있음 — 중복 매도 차단", Map.of());
                return 0;
            }
        } catch (RuntimeException e) {
            log.warn("미체결 매도 조회 실패 — 그대로 진행(매도는 노출을 줄인다): {} {}", strategy,
                    StockNames.label(signal.symbol()), e);
        }
        long qty = mine.get().quantity();
        PositionBook.Position account = positionBook.get(signal.symbol());
        if (account == null) {
            log.warn("전략 장부엔 {}주 있는데 계좌 장부엔 없음 — 매도 거부(장부 어긋남 확인 필요): {} {}", qty, strategy,
                    StockNames.label(signal.symbol()));
            publishRejected(signal, "계좌 장부에 보유 없음 — 매도 거부(장부 어긋남)", Map.of("strategyQuantity", String.valueOf(qty)));
            return 0;
        }
        if (account.quantity() < qty) {
            log.warn("전략 장부 {}주 > 계좌 {}주 — 계좌 수량까지만 매도: {} {}", qty, account.quantity(), strategy,
                    StockNames.label(signal.symbol()));
            qty = account.quantity();
        }
        return qty;
    }

    /**
     * 매수 수량 결정. 다음을 모두 만족해야 0보다 큰 수량이 나온다.
     * <ul>
     *   <li>미보유 종목일 것 — 물타기(추가 매수)는 의도적으로 금지</li>
     *   <li>그 종목에 결과가 확정되지 않은 매수 주문이 없을 것 — 전송~체결 사이 중복 매수 방지(실행 계획 1.2)</li>
     *   <li>동시 보유 종목 수(+ 미보유 종목의 미체결 매수 수)가 한도 미만일 것 — 분산 한도 (기본 5종목)</li>
     *   <li>고정비율 사이징 결과가 1주 이상일 것</li>
     * </ul>
     *
     * <p><b>confidence = 투입 비중(2026-08-13)</b>: 예산 = equity × maxPositionPctPerSymbol ×
     * signal.confidence(). 스키마(Signal.confidence)는 원래 "확신도"라는 이름이지만, C3
     * 같은 사이징 전략(변동성 타게팅)은 이 필드에 "얼마나 투입할지"(0~1의 비중)를 실어
     * 보낸다 — 새 필드를 추가하지 않고 기존 confidence 필드를 재사용하는 설계 판단이다.
     * 기존 전략들처럼 confidence=1.0이면 이 곱셈이 결과에 영향을 주지 않는다(수치 무변화).
     */
    private long sizeBuy(Signal signal) {
        // ── 거시 국면 보수 모드(PLAN 5절) ──────────────────────────────────
        // 킬스위치와 달리 매도는 막지 않는다 — sizeSell 경로는 이 검사를 거치지 않으므로
        // 청산 시그널은 그대로 통과한다(MacroGuard 클래스 설명 "보수 모드 vs 킬스위치" 참고).
        // 사유는 임계 초과 또는 지표 오래됨(실행 계획 1.4) — MacroGuard가 정한 문구를 그대로 쓴다.
        Optional<String> macroBlock = macroGuard.buyBlockReason();
        if (macroBlock.isPresent()) {
            log.info("{}: {}", macroBlock.get(), StockNames.label(signal.symbol()));
            publishRejected(signal, macroBlock.get(), Map.of());
            return 0;
        }
        // ── DART 공시 배제(PLAN 5절, 골격) ─────────────────────────────────
        if (disclosureBlacklist.isBlacklisted(signal.symbol().value())) {
            log.info("공시 블랙리스트 종목 — 매수 거부: {}", StockNames.label(signal.symbol()));
            publishRejected(signal, "공시 블랙리스트 종목 — 매수 거부", Map.of());
            return 0;
        }
        if (positionBook.holds(signal.symbol())) {
            log.info("이미 보유 중 — 추가 매수 차단: {}", StockNames.label(signal.symbol()));
            publishRejected(signal, "이미 보유 중 — 추가 매수 차단", Map.of());
            return 0;
        }
        // ── 미체결 매수(실행 계획 1.2, BE-P1-2) ─────────────────────────────
        // 장부(PositionBook)는 체결된 것만 안다 — 전송~체결 사이의 매수도 "보유 예정"으로 보고 같은 두 검사에 넣는다.
        // 조회가 실패하면 모르는 채로 사지 않는다(매도는 이 경로를 거치지 않으므로 청산은 막히지 않는다).
        Set<StockCode> openBuys;
        try {
            openBuys = openOrderQuery.symbolsWithOpenBuy();
        } catch (RuntimeException e) {
            log.warn("미체결 주문 조회 실패 — 매수 거부: {}", StockNames.label(signal.symbol()), e);
            publishRejected(signal, "미체결 주문 조회 실패 — 매수 거부", Map.of());
            return 0;
        }
        if (openBuys.contains(signal.symbol())) {
            log.info("미체결 매수 주문 있음 — 추가 매수 차단: {}", StockNames.label(signal.symbol()));
            publishRejected(signal, "미체결 매수 주문 있음 — 추가 매수 차단", Map.of());
            return 0;
        }
        // 이미 보유한 종목의 미체결 매수(부분 체결 등)는 두 번 세지 않는다
        long pendingBuyCount = openBuys.stream().filter(symbol -> !positionBook.holds(symbol)).count();
        int openPositionCount = positionBook.openPositionCount();
        if (openPositionCount + pendingBuyCount >= properties.maxConcurrentPositions()) {
            log.info("동시 보유 한도 도달({}) — 매수 거부: {} (보유 {}, 미체결 매수 {})",
                    properties.maxConcurrentPositions(), StockNames.label(signal.symbol()),
                    openPositionCount, pendingBuyCount);
            publishRejected(signal, "동시 보유 한도 도달(" + properties.maxConcurrentPositions() + ") — 매수 거부",
                    Map.of("openPositionCount", String.valueOf(openPositionCount),
                            "pendingBuyCount", String.valueOf(pendingBuyCount)));
            return 0;
        }
        // equity 조회는 EquitySource에 위임한다 — SIM은 설정값 고정(PaperEquitySource),
        // LIVE는 브로커 잔고 조회(execution.BrokerEquitySource, 60초 캐시)로 실행 모드에 따라
        // 조건부로 갈린다(RiskGate는 둘 중 무엇이 떠 있는지 모른다).
        BigDecimal equity = sizingEquity();
        double confidence = clampConfidence(signal);
        long qty = sizer.sizeBuy(equity, refPriceOf(signal), confidence);
        if (qty <= 0) {
            log.info("사이징 결과 0주 — 매수 불가: {} (equity={}, price={}, confidence={})",
                    StockNames.label(signal.symbol()), equity, signal.refPrice(), confidence);
            publishRejected(signal, "사이징 결과 0주 — 매수 불가",
                    Map.of("equity", equity.toString(), "confidence", String.valueOf(confidence)));
        }
        return qty;
    }

    /**
     * confidence는 전략이 어떤 값을 보내든(버그로 음수·0·1 초과가 와도) 사이징 계산이
     * 안전하게 끝나야 한다 — 범위(0, 1]를 벗어나면 경고 로그를 남기고 1.0(전액)으로
     * 클램프한다. "무조건 거부"가 아니라 "안전한 기본값으로 대체"를 택한 이유는, 사이징
     * 신호 하나 때문에 정상적인 매수 시그널 전체를 버리는 것이 더 위험하다고 판단해서다.
     */
    private double clampConfidence(Signal signal) {
        double confidence = signal.confidence();
        if (confidence <= 0.0 || confidence > 1.0) {
            log.warn("confidence 범위(0,1] 벗어남({}) — 1.0으로 클램프: {}", confidence, StockNames.label(signal.symbol()));
            return 1.0;
        }
        return confidence;
    }

    /**
     * 수동 지정 수량 검증 (운영 1일차 ⑦ — 대시보드 수동 시그널 전용).
     *
     * <p>수동 지정은 "자동 산정치 이하로 줄이는" 용도다 — 리스크 한도 우회 수단이 아니다:
     * <ul>
     *   <li>매수: 보수 모드·공시 블랙리스트는 그대로 거부. 물타기 금지·동시 보유 한도는
     *       수동 지정에 한해 적용하지 않는다(운영자가 의도한 개입 — 테스트/수동 정리 용도).
     *       <b>1건 금액 상한</b>({@code risk.manual-order-max-krw}, 기본 100만 원 — D-06, Phase 0.4): 지정 수량 ×
     *       기준가가 상한을 넘으면 줄이지 않고 거부한다(잘못 입력한 주문을 조용히 줄여 내보내지 않게 — 9/18 "193주"
     *       사고). 사이징 단계라 거부돼도 주문 슬롯을 쓰지 않는다. 그 다음 상한은 자동 사이징과 같은 예산 캡
     *       (equity×비중×confidence)이다.</li>
     *   <li>매도: 보유 수량 캡 — 초과 지정 시 보유량으로 줄인다(2026-09-11 실측: 이중계상된
     *       로컬 23주로 매도 시 브로커 800033 "매도가능수량 부족" 거부 — 이 캡이 1차 방어).
     *       금액 상한은 적용하지 않는다 — 매도는 노출을 줄이는 방향이고, 보유분 수동 청산을 막지 않기 위해서다.</li>
     * </ul>
     */
    private long sizeManual(Signal signal) {
        long requested = signal.fixedQuantity();
        if (requested <= 0) {
            publishRejected(signal, "수동 지정 수량이 0 이하 — 거부", Map.of());
            return 0;
        }
        if (signal.side() == Side.SELL) {
            PositionBook.Position position = positionBook.get(signal.symbol());
            if (position == null) {
                log.info("미보유 종목 수동 매도 무시: {}", StockNames.label(signal.symbol()));
                publishRejected(signal, "미보유 종목 매도 시그널 무시", Map.of());
                return 0;
            }
            long qty = Math.min(requested, position.quantity());
            if (qty < requested) {
                log.info("수동 매도 수량 {} → 보유량 {}로 캡: {}", requested, qty, StockNames.label(signal.symbol()));
            }
            return qty;
        }
        // BUY
        Optional<String> macroBlock = macroGuard.buyBlockReason();
        if (macroBlock.isPresent()) {
            log.info("{} — 수동 매수도 거부: {}", macroBlock.get(), StockNames.label(signal.symbol()));
            publishRejected(signal, macroBlock.get(), Map.of());
            return 0;
        }
        if (disclosureBlacklist.isBlacklisted(signal.symbol().value())) {
            log.info("공시 블랙리스트 종목 — 수동 매수도 거부: {}", StockNames.label(signal.symbol()));
            publishRejected(signal, "공시 블랙리스트 종목 — 매수 거부", Map.of());
            return 0;
        }
        BigDecimal refPrice = refPriceOf(signal);
        if (refPrice != null) {
            BigDecimal amount = refPrice.multiply(BigDecimal.valueOf(requested));
            BigDecimal maxAmount = BigDecimal.valueOf(properties.manualOrderMaxKrw());
            if (amount.compareTo(maxAmount) > 0) {
                log.warn("수동 매수 금액 상한 초과 — 거부: {} {}주 × {} = {}원 > 상한 {}원",
                        StockNames.label(signal.symbol()), requested, refPrice, amount.toPlainString(), maxAmount);
                publishRejected(signal, "수동 주문 금액 상한 초과 — 거부",
                        Map.of("amount", amount.toPlainString(), "maxAmount", maxAmount.toPlainString(),
                                "quantity", String.valueOf(requested)));
                return 0;
            }
        }
        long cap = sizer.sizeBuy(sizingEquity(), refPriceOf(signal), clampConfidence(signal));
        if (cap <= 0) {
            publishRejected(signal, "예산 캡 0주 — 수동 매수 불가", Map.of());
            return 0;
        }
        long qty = Math.min(requested, cap);
        if (qty < requested) {
            log.info("수동 매수 수량 {} → 예산 캡 {}로 축소: {}", requested, qty, StockNames.label(signal.symbol()));
        }
        return qty;
    }

    /**
     * 매도 수량 결정 — 정책: <b>부분 매도 없이 전량 청산</b>.
     * 보유하지 않은 종목의 매도 시그널은 무시한다(공매도 미지원).
     */
    private long sizeSell(Signal signal) {
        PositionBook.Position position = positionBook.get(signal.symbol());
        if (position == null) {
            log.info("미보유 종목 매도 시그널 무시: {}", StockNames.label(signal.symbol()));
            publishRejected(signal, "미보유 종목 매도 시그널 무시", Map.of());
            return 0;
        }
        return position.quantity();
    }

    /**
     * 지금이 정규장 시간(09:00~15:30 KST)인지 — {@link #clock}으로 얻은 시각을 KST로 환산해
     * {@link MarketCalendarService#isMarketHours}에 위임 판정한다.
     */
    private boolean isMarketHours() {
        LocalDateTime now = LocalDateTime.now(clock.withZone(MarketConstants.KST));
        return marketCalendarService.isMarketHours(now);
    }

    /**
     * 게이트 거부 지점마다 {@link SignalDecision}(conclusion=REJECTED)을 발행한다(FE-6,
     * PLAN.md ADR-10 확장표). reason은 이 클래스가 이미 남기는 로그 메시지 문구를 그대로
     * 재사용한다 — 감사 화면과 로그가 서로 다른 문구로 갈라지는 것을 막기 위함이다.
     *
     * <p>horizon은 strategyId에서 유도한다: C3(중기 전략, ADR-11)는 "MID", 그 외(테스트
     * 시그널 등 아직 지평 프레임에 편입되지 않은 전략)는 "TEST"로 표시한다 — 향후 지평별
     * 전략이 추가되면 이 매핑도 함께 늘어난다.
     */
    private void publishRejected(Signal signal, String reason, Map<String, String> metrics) {
        publisher.publishEvent(new SignalDecision(
                horizonFor(signal.strategyId()),
                signal.strategyId(),
                signal.symbol(),
                "REJECTED",
                reason,
                metrics,
                clock.instant()));
    }

    /**
     * strategyId → 보유기간 지평(ADR-11) 매핑. C3 "MID", 단타·종가→익일 "DAY", 차트 기법 "SWING", 그 외 "TEST"
     * (2026-10-16 다전략 추가).
     */
    static String horizonFor(String strategyId) {
        if (strategyId == null) {
            return "TEST";
        }
        String id = strategyId.toUpperCase(Locale.ROOT);
        if (id.startsWith("C3")) {
            return "MID";
        }
        if (id.startsWith("VB-") || id.startsWith("ON-")) {
            return "DAY";
        }
        if (id.startsWith("TA-")) {
            return "SWING";
        }
        return "TEST";
    }
}
