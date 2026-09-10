package com.autostock.backtest;

import com.autostock.common.event.Candle;
import com.autostock.common.event.Fill;
import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.Side;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * C4 가설(국면 롱/숏, PLAN ADR-8) 전용 walk-forward 러너.
 *
 * <h2>C3와의 관계 — 딱 한 가지만 다르다</h2>
 * C3({@code VolatilityTargetingStrategy(RegimeFilteredStrategy(TimeSeriesMomentumStrategy(n)))})의
 * 신호·체결 규칙은 그대로 두고, "국면필터 OFF일 때 069500 슬리브가 현금 대신 KODEX
 * 인버스(114800)를 전액 보유"하는 실행 단계 로직만 추가한다. N(TSM lookback) 후보 선택
 * (train 단계)은 인버스와 무관하게 C3와 완전히 동일한 절차로 수행한다 — 이번 실험은
 * 파라미터 스윕이 아니라 단일 trial 추가이므로, 새 파라미터 축을 만들지 않는다.
 *
 * <h2>왜 기존 {@link WalkForwardRunner}/{@link BacktestRunner}를 그대로 재사용하지 못하는가</h2>
 * 그 둘은 "단일 종목 캔들 스트림 하나"를 순서대로 재생하는 구조라서, 같은 슬리브 안에서
 * 국면에 따라 서로 다른 두 종목(069500·114800)의 가격으로 번갈아 체결시키는 것을 표현할
 * 수 없다. 두 종목 캔들을 그냥 이어붙인 "합성 스트림"도 쓸 수 없다 — 국면 전환일에는
 * "069500 포지션을 069500 가격으로 청산"과 "114800 진입을 114800 가격으로" 두 가지가
 * 같은 날 필요한데, 캔들 하나는 한 종목의 가격만 표현하기 때문이다. 그래서 이 클래스는
 * 날짜별로 두 종목의 캔들을 모두 참조할 수 있는 자체 일별 루프를 둔다(아래 {@link
 * #runDayLoop}).
 *
 * <h2>일별 처리 순서(국면 전환일도 이 순서 하나로 정확히 처리된다)</h2>
 * <ol>
 *   <li>오늘 ON이고 어제까지 인버스를 보유 중이었다면, 그 인버스를 <b>오늘 인버스 가격으로</b>
 *       즉시 전량 청산한다(ADR-8 "국면 ON 복귀 시 즉시 인버스 청산 후 원 전략 복귀"). 청산
 *       대금은 그날 바로 069500 재진입 판단의 가용 현금이 된다.</li>
 *   <li>C3의 내부 전략({@code VolatilityTargetingStrategy(RegimeFilteredStrategy(TSM(n)))})을
 *       069500 캔들에 그대로 호출해 C3와 100% 동일한 신호·체결 규칙(매수스탑/매도스탑/
 *       익일시가매도, 같은 {@link CostModel})을 적용한다. OFF일 때는 이 내부 전략이 스스로
 *       "보유 중이면 청산, 미보유면 아무 것도 안 함"을 반환하므로(RegimeFilteredStrategy),
 *       069500 쪽은 국면 전환에 항상 자동으로 대응한다.</li>
 *   <li>오늘이 OFF이고(위 2단계 이후) 069500 포지션이 0이면 인버스 보유 상태를 갱신한다:
 *       미보유·미잠금 상태면 가용 현금 전액을 114800에 투입해 신규 진입, 이미 보유 중이면
 *       보유일수를 늘리고 20거래일(ADR-8 안전규칙②, {@link #MAX_INVERSE_HOLD_DAYS})을 넘기면
 *       즉시 청산 후 이번 OFF 구간이 끝날 때까지(다음 ON 전환 전까지) 재진입을 막는다
 *       (cashLocked) — 횡보 구간의 음의 복리를 방어하기 위함이다.</li>
 * </ol>
 *
 * <h2>114800에 별도 총보수·추적오차 조정을 하지 않는 이유</h2>
 * 114800.csv는 실제 시장 가격(호가) 데이터이므로 인버스 ETF의 총보수·일일 리밸런싱 복리
 * 괴리·추적오차가 이미 가격에 내재돼 있다. 여기에 추가로 비용을 얹으면 이중 계상이다.
 * 매매 비용은 069500과 완전히 동일한 {@link CostModel}(수수료·세금·슬리피지)만 적용한다.
 *
 * <h2>워밍업</h2>
 * {@link BacktestRunner}와 동일한 규약 — train/test 구간 시작 이전의 진짜 과거 캔들을
 * warmupCandles만큼 앞에 붙여 전략(TSM의 21봉 판단주기·N봉 종가이력, VolTarget의 20봉
 * 변동성 관측창)을 실전과 같은 상태로 미리 채운다. 워밍업 구간의 매매·인버스 전환은
 * 실제로 반영되지만 성과 집계(dailyReturns, tradeCount, 인버스 통계)에는 포함하지 않는다.
 */
public final class InverseSwitchWalkForwardRunner {

    /** ADR-8 안전규칙② — 인버스 보유기간 상한(거래일). 초과 지속되면 현금 복귀. */
    private static final int MAX_INVERSE_HOLD_DAYS = 20;

    private final CostModel costModel;
    private final BacktestRunner trainBacktestRunner;
    private final BacktestExecutionHandler executionHandler;
    private final PerformanceCalculator performanceCalculator = new PerformanceCalculator();

    public InverseSwitchWalkForwardRunner(CostModel costModel) {
        this.costModel = costModel;
        this.trainBacktestRunner = new BacktestRunner(costModel);
        this.executionHandler = new BacktestExecutionHandler(costModel);
    }

    /**
     * OOS(walk-forward) 결과와 함께, C3 대비 C4가 실제로 무엇을 했는지 진단할 수 있는
     * 인버스 보유 통계를 함께 담는다. 통계는 모두 <b>OOS(test) 구간만</b> 집계한다(워밍업 제외).
     *
     * @param walkForwardResult   {@link PortfolioBacktestRunner.SleeveRunner}에 그대로 넘길 수 있는
     *                            표준 walk-forward 결과(선택된 N, OOS 성과)
     * @param inverseHoldDaysTotal OOS 구간 동안 인버스를 보유한 일수 합계(창을 모두 합산)
     * @param inverseEntryCount    OOS 구간 동안 인버스 신규 진입(현금→인버스 전환) 횟수
     * @param inverseCapEvents     OOS 구간 동안 20거래일 상한 초과로 강제 청산(현금 복귀)된 횟수
     */
    public record InverseSwitchResult(
            WalkForwardResult<Integer> walkForwardResult,
            int inverseHoldDaysTotal,
            int inverseEntryCount,
            int inverseCapEvents
    ) {
    }

    /**
     * @param kodexCandles   069500 시간순 캔들 전체
     * @param inverseCandles 114800 시간순 캔들 전체(날짜로 매칭 — 정렬 순서는 무관)
     * @param nCandidates    TSM lookback N 후보
     * @param regimeByDate   {@link MarketRegime#compute}로 미리 계산한 날짜별 ON/OFF 맵
     * @param targetVol      변동성 타게팅 목표 연변동성
     * @param trainSize      각 창의 훈련 구간 길이(거래일)
     * @param testSize       각 창의 테스트(OOS) 구간 길이(거래일)
     * @param warmupCandles  각 구간 시작 앞에 붙일 워밍업 캔들 수
     * @param initialCapital 이 슬리브에 배정된 자본
     */
    public InverseSwitchResult run(List<Candle> kodexCandles, List<Candle> inverseCandles,
                                    List<Integer> nCandidates, Map<LocalDate, Boolean> regimeByDate,
                                    double targetVol, int trainSize, int testSize, int warmupCandles,
                                    BigDecimal initialCapital) {
        if (nCandidates.isEmpty()) {
            throw new IllegalArgumentException("N 후보 목록이 비어 있음");
        }

        Map<LocalDate, Candle> inverseByDate = new LinkedHashMap<>();
        for (Candle c : inverseCandles) {
            inverseByDate.put(c.date(), c);
        }

        Function<Integer, BacktestStrategy> strategyFactory = n -> new VolatilityTargetingStrategy(
                new RegimeFilteredStrategy(new TimeSeriesMomentumStrategy(n), regimeByDate), targetVol);

        List<Integer> selectedParams = new ArrayList<>();
        List<Double> oosDailyReturns = new ArrayList<>();
        List<Double> trainSharpes = new ArrayList<>();
        int oosTradeCount = 0;
        int windowCount = 0;
        int inverseHoldDaysTotal = 0;
        int inverseEntryCount = 0;
        int inverseCapEvents = 0;

        int start = 0;
        while (start + trainSize + testSize <= kodexCandles.size()) {
            int trainEnd = start + trainSize;
            int testStart = trainEnd;
            int testEnd = testStart + testSize;

            // ── 1) train 단계 — C3와 완전히 동일(인버스 전환은 실행 단계에만 관여) ──
            int trainWarmupStart = Math.max(0, start - warmupCandles);
            int trainWarmupActual = start - trainWarmupStart;
            List<Candle> trainCandles = kodexCandles.subList(trainWarmupStart, trainEnd);

            double bestSharpe = Double.NEGATIVE_INFINITY;
            int bestN = nCandidates.get(0);
            for (int n : nCandidates) {
                BacktestResult trainResult = trainBacktestRunner.run(
                        trainCandles, strategyFactory.apply(n), initialCapital, trainWarmupActual, 1, 0.0);
                trainSharpes.add(trainResult.sharpe());
                if (trainResult.sharpe() > bestSharpe) {
                    bestSharpe = trainResult.sharpe();
                    bestN = n;
                }
            }
            selectedParams.add(bestN);

            // ── 2) test(OOS) 단계 — 069500은 C3와 동일 규칙, OFF 구간만 인버스로 대체 ──
            int testWarmupStart = Math.max(0, testStart - warmupCandles);
            int testWarmupActual = testStart - testWarmupStart;
            List<Candle> testKodexSlice = kodexCandles.subList(testWarmupStart, testEnd);

            DayLoopResult testResult = runDayLoop(
                    testKodexSlice, inverseByDate, regimeByDate, strategyFactory.apply(bestN),
                    testWarmupActual, initialCapital);

            oosDailyReturns.addAll(testResult.dailyReturns());
            oosTradeCount += testResult.tradeCount();
            inverseHoldDaysTotal += testResult.inverseHoldDaysTotal();
            inverseEntryCount += testResult.inverseEntryCount();
            inverseCapEvents += testResult.inverseCapEvents();

            windowCount++;
            start += testSize;
        }

        int trials = nCandidates.size() * windowCount;
        double trialsVariance = populationVariance(trainSharpes);
        BigDecimal oosFinalEquity = compound(initialCapital, oosDailyReturns);

        BacktestResult oosResult = performanceCalculator.calculate(
                oosDailyReturns, initialCapital, oosFinalEquity, oosTradeCount, trials, trialsVariance);

        WalkForwardResult<Integer> wfr = new WalkForwardResult<>(List.copyOf(selectedParams), oosResult, trials);
        return new InverseSwitchResult(wfr, inverseHoldDaysTotal, inverseEntryCount, inverseCapEvents);
    }

    /** 일별 루프 결과(워밍업 제외 집계). */
    private record DayLoopResult(
            List<Double> dailyReturns,
            int tradeCount,
            int inverseHoldDaysTotal,
            int inverseEntryCount,
            int inverseCapEvents
    ) {
    }

    /** 매매 원장 — 069500 포지션과 114800 포지션은 설계상 동시에 보유되지 않는다(항상 배타적). */
    private static final class SwitchLedger {
        BigDecimal cash;
        long kodexQty;
        long inverseQty;
        int inverseHoldDaysConsecutive;
        boolean cashLocked; // 이번 OFF 구간에서 20거래일 상한을 넘겨 강제 청산된 뒤 현금 대기 중
        int tradeCount; // 069500 + 114800 합산 체결 횟수
        BigDecimal lastKnownInverseClose = BigDecimal.ZERO; // 인버스 캔들 날짜 결측 시 이월용

        SwitchLedger(BigDecimal initialCash) {
            this.cash = initialCash;
        }
    }

    private DayLoopResult runDayLoop(List<Candle> kodexSlice, Map<LocalDate, Candle> inverseByDate,
                                      Map<LocalDate, Boolean> regimeByDate, BacktestStrategy innerStrategy,
                                      int warmupActual, BigDecimal initialCapital) {
        List<Double> dailyReturns = new ArrayList<>();
        SwitchLedger ledger = new SwitchLedger(initialCapital);
        BigDecimal prevEquity = initialCapital;
        int reportedTradeCount = 0;
        int reportedInverseHoldDaysTotal = 0;
        int reportedInverseEntryCount = 0;
        int reportedInverseCapEvents = 0;

        for (int i = 0; i < kodexSlice.size(); i++) {
            Candle todayKodex = kodexSlice.get(i);
            boolean warmingUp = i < warmupActual;
            LocalDate date = todayKodex.date();
            boolean regimeOn = regimeByDate.getOrDefault(date, true);
            int tradeCountBeforeToday = ledger.tradeCount;
            boolean capEventToday = false;
            boolean entryEventToday = false;

            // ── 1) ON 복귀 시 어제까지 보유하던 인버스를 즉시 청산(오늘 인버스 가격으로) ──
            if (regimeOn && ledger.inverseQty > 0) {
                Candle todayInv = inverseByDate.get(date);
                if (todayInv != null) {
                    applySell(ledger, todayInv, todayInv.open(), false);
                }
                ledger.inverseHoldDaysConsecutive = 0;
            }
            if (regimeOn) {
                ledger.cashLocked = false; // 다음 OFF 구간을 위해 잠금 해제
            }

            // ── 2) 069500 — C3의 내부 전략을 그대로 호출(체결 규칙도 BacktestRunner와 동일) ──
            BacktestStrategy.PortfolioState state = new BacktestStrategy.PortfolioState(
                    ledger.kodexQty, BigDecimal.ZERO, ledger.cash);
            List<TradeIntent> intents = innerStrategy.onCandle(todayKodex, state);

            if (ledger.kodexQty > 0) {
                TradeIntent stop = triggeredSellStop(intents, todayKodex);
                if (stop != null) {
                    applySell(ledger, todayKodex, stop.price().min(todayKodex.open()), true);
                } else {
                    TradeIntent scheduledExit = firstOfKind(intents, TradeIntent.Kind.SELL_NEXT_OPEN);
                    if (scheduledExit != null) {
                        applySell(ledger, todayKodex, todayKodex.open(), true);
                    }
                }
            }
            if (ledger.kodexQty == 0) {
                TradeIntent buy = triggeredBuyStop(intents, todayKodex);
                if (buy != null && applyBuy(ledger, todayKodex, buy.price().max(todayKodex.open()), buy.fraction(), true)) {
                    TradeIntent pairedStop = triggeredSellStop(intents, todayKodex);
                    if (pairedStop != null) {
                        applySell(ledger, todayKodex, pairedStop.price().min(todayKodex.open()), true);
                    }
                }
            }

            // ── 3) OFF이고 069500 미보유면 인버스 보유 상태 갱신 ──
            if (!regimeOn && ledger.kodexQty == 0) {
                Candle todayInv = inverseByDate.get(date);
                if (todayInv != null) {
                    if (ledger.inverseQty == 0 && !ledger.cashLocked) {
                        if (applyBuy(ledger, todayInv, todayInv.open(), 1.0, false)) {
                            ledger.inverseHoldDaysConsecutive = 1;
                            entryEventToday = true;
                        }
                    } else if (ledger.inverseQty > 0) {
                        ledger.inverseHoldDaysConsecutive++;
                        if (ledger.inverseHoldDaysConsecutive > MAX_INVERSE_HOLD_DAYS) {
                            applySell(ledger, todayInv, todayInv.open(), false);
                            ledger.cashLocked = true;
                            ledger.inverseHoldDaysConsecutive = 0;
                            capEventToday = true;
                        }
                    }
                    ledger.lastKnownInverseClose = todayInv.close();
                }
            }

            // ── 4) 오늘 종가 기준 평가자산 마킹 ──
            BigDecimal kodexValue = todayKodex.close().multiply(BigDecimal.valueOf(ledger.kodexQty));
            BigDecimal inverseValue = ledger.inverseQty > 0
                    ? ledger.lastKnownInverseClose.multiply(BigDecimal.valueOf(ledger.inverseQty))
                    : BigDecimal.ZERO;
            BigDecimal equityToday = ledger.cash.add(kodexValue).add(inverseValue);
            double dailyReturn = prevEquity.signum() == 0
                    ? 0.0
                    : equityToday.subtract(prevEquity).divide(prevEquity, 12, RoundingMode.HALF_UP).doubleValue();

            if (!warmingUp) {
                dailyReturns.add(dailyReturn);
                reportedTradeCount += ledger.tradeCount - tradeCountBeforeToday;
                if (ledger.inverseQty > 0) {
                    reportedInverseHoldDaysTotal++;
                }
                if (entryEventToday) {
                    reportedInverseEntryCount++;
                }
                if (capEventToday) {
                    reportedInverseCapEvents++;
                }
            }
            prevEquity = equityToday;
        }

        return new DayLoopResult(dailyReturns, reportedTradeCount,
                reportedInverseHoldDaysTotal, reportedInverseEntryCount, reportedInverseCapEvents);
    }

    /** 매도 체결 — kodex 플래그로 069500/114800 어느 쪽 포지션을 청산하는지 구분한다. */
    private void applySell(SwitchLedger ledger, Candle today, BigDecimal referencePrice, boolean kodex) {
        long qty = kodex ? ledger.kodexQty : ledger.inverseQty;
        OrderRequest order = buildOrder(today, Side.SELL, qty);
        Fill fill = executionHandler.execute(order, referencePrice);
        BigDecimal notional = fill.fillPrice().multiply(BigDecimal.valueOf(fill.filledQuantity()));
        ledger.cash = ledger.cash.add(notional).subtract(costModel.sellFee(notional));
        if (kodex) {
            ledger.kodexQty = 0;
        } else {
            ledger.inverseQty = 0;
        }
        ledger.tradeCount++;
    }

    /** 매수 체결 — {@link BacktestRunner#applyBuy}와 동일한 사이징 규약(fraction, 소진형 수량 계산). */
    private boolean applyBuy(SwitchLedger ledger, Candle today, BigDecimal referencePrice, double fraction, boolean kodex) {
        BigDecimal execPreview = costModel.slippageAdjustedBuyPrice(referencePrice);
        BigDecimal budget = ledger.cash.multiply(BigDecimal.valueOf(fraction));
        long qty = affordableQuantity(budget, execPreview);
        if (qty <= 0) {
            return false;
        }
        OrderRequest order = buildOrder(today, Side.BUY, qty);
        Fill fill = executionHandler.execute(order, referencePrice);
        BigDecimal notional = fill.fillPrice().multiply(BigDecimal.valueOf(fill.filledQuantity()));
        ledger.cash = ledger.cash.subtract(notional).subtract(costModel.buyFee(notional));
        if (kodex) {
            ledger.kodexQty = qty;
        } else {
            ledger.inverseQty = qty;
        }
        ledger.tradeCount++;
        return true;
    }

    private long affordableQuantity(BigDecimal cash, BigDecimal execPrice) {
        if (execPrice.signum() <= 0) {
            return 0;
        }
        long qty = cash.divide(execPrice, 0, RoundingMode.DOWN).longValue();
        while (qty > 0) {
            BigDecimal notional = execPrice.multiply(BigDecimal.valueOf(qty));
            BigDecimal totalCost = notional.add(costModel.buyFee(notional));
            if (totalCost.compareTo(cash) <= 0) {
                break;
            }
            qty--;
        }
        return qty;
    }

    private OrderRequest buildOrder(Candle candle, Side side, long qty) {
        return new OrderRequest(
                UUID.randomUUID().toString(),
                "backtest-c4",
                candle.symbol(),
                side,
                qty,
                candle.open(),
                candle.date().atStartOfDay().toInstant(ZoneOffset.UTC)
        );
    }

    private TradeIntent triggeredSellStop(List<TradeIntent> intents, Candle today) {
        for (TradeIntent intent : intents) {
            if (intent.kind() == TradeIntent.Kind.SELL_STOP && today.low().compareTo(intent.price()) <= 0) {
                return intent;
            }
        }
        return null;
    }

    private TradeIntent triggeredBuyStop(List<TradeIntent> intents, Candle today) {
        for (TradeIntent intent : intents) {
            if (intent.kind() == TradeIntent.Kind.BUY_STOP && today.high().compareTo(intent.price()) >= 0) {
                return intent;
            }
        }
        return null;
    }

    private TradeIntent firstOfKind(List<TradeIntent> intents, TradeIntent.Kind kind) {
        for (TradeIntent intent : intents) {
            if (intent.kind() == kind) {
                return intent;
            }
        }
        return null;
    }

    /** OOS 일별 수익률을 이어붙여 최종 평가자산을 복리로 재구성한다. */
    private BigDecimal compound(BigDecimal initialCapital, List<Double> dailyReturns) {
        double multiplier = 1.0;
        for (double r : dailyReturns) {
            multiplier *= (1 + r);
        }
        return initialCapital.multiply(BigDecimal.valueOf(multiplier));
    }

    /** 모분산(분모 n) — WalkForwardRunner와 같은 관례. */
    private double populationVariance(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        double mean = 0.0;
        for (double v : values) {
            mean += v;
        }
        mean /= values.size();

        double sumSq = 0.0;
        for (double v : values) {
            double d = v - mean;
            sumSq += d * d;
        }
        return sumSq / values.size();
    }
}
