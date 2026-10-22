package com.autostock.backtest;

import com.autostock.common.event.Candle;
import com.autostock.strategy.DailyRuleBook;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 차트 기법 5종(트랙 H)의 라이브 실행용 규칙 묶음 — {@link DailyRuleBook} 구현(2026-10-16,
 * aiDoc/multi-strategy-paper-2026-10-16.md 3-3).
 *
 * <p><b>백테스트 클래스를 그대로 쓴다.</b> 규칙 인스턴스를 매번 새로 만들어 완료된 봉을 순서대로 넣고, 그 사이 체결은
 * {@link BacktestRunner}와 같은 규칙으로 판정해 "그림자 포지션"을 굴린다({@link Shadow}). 마지막에 어제 종가 시점의
 * 그림자 상태로 오늘 시가 봉(시가만 의미 있음 — 규칙은 오늘 봉에서 시가만 읽는다, {@link BacktestStrategy} 규약)을 넣어
 * 오늘의 주문 의사를 받는다. 돈(현금·수량)은 세지 않는다 — 규칙은 보유 여부와 평단만 본다.
 *
 * <p>그림자 체결 규칙(= BacktestRunner 2·3단계): 보유 중이면 손절 스탑 먼저(저가 ≤ 스탑),
 * 아니면 시가 매도. 미보유면 매수 스탑(고가 ≥ 트리거, 평단 = max(트리거, 시가) + 슬리피지), 진입 당일 짝지어 걸린 손절도
 * 같은 날 확인. 돈이 모자라 못 사는 경우는 없다고 본다(러너는 현금이 있으면 항상 산다).
 * 둘이 같음은 {@code TaRuleBookTest}가 러너와 대조해 확인한다.
 */
@Component
public class TaRuleBook implements DailyRuleBook {

    private final Map<String, Rule> rules = new LinkedHashMap<>();

    private record Rule(String name, Supplier<BacktestStrategy> factory) {
    }

    public TaRuleBook() {
        rules.put("TA-H1", new Rule("일목균형표", IchimokuCloudStrategy::new));
        rules.put("TA-H2", new Rule("볼린저 스퀴즈", BollingerSqueezeStrategy::new));
        rules.put("TA-H3", new Rule("슈퍼트렌드", SuperTrendStrategy::new));
        rules.put("TA-H4", new Rule("RSI2 평균회귀", Rsi2MeanReversionStrategy::new));
        rules.put("TA-H5", new Rule("돌파 리테스트", BreakoutRetestStrategy::new));
    }

    @Override
    public List<String> ruleIds() {
        return List.copyOf(rules.keySet());
    }

    @Override
    public String ruleName(String ruleId) {
        Rule rule = rules.get(ruleId);
        return rule == null ? ruleId : rule.name();
    }

    @Override
    public RulePlan plan(String ruleId, String symbol, List<Candle> completed, LocalDate today, BigDecimal todayOpen) {
        Rule rule = rules.get(ruleId);
        if (rule == null) {
            throw new IllegalArgumentException("모르는 규칙: " + ruleId);
        }
        BacktestStrategy strategy = rule.factory().get();
        Shadow shadow = new Shadow();
        for (Candle candle : completed) {
            shadow.apply(strategy.onCandle(candle, shadow.state()), candle);
        }
        Candle todayCandle = new Candle(symbol, today, todayOpen, todayOpen, todayOpen, todayOpen, 0L);
        List<TradeIntent> intents = strategy.onCandle(todayCandle, shadow.state());
        List<RuleOrder> orders = new ArrayList<>();
        for (TradeIntent intent : intents) {
            switch (intent.kind()) {
                case BUY_STOP -> orders.add(intent.price().compareTo(todayOpen) <= 0
                        ? new RuleOrder(RuleOrder.Kind.BUY_AT_OPEN, null)
                        : new RuleOrder(RuleOrder.Kind.BUY_STOP, intent.price()));
                case SELL_NEXT_OPEN -> orders.add(new RuleOrder(RuleOrder.Kind.SELL_AT_OPEN, null));
                case SELL_STOP -> orders.add(new RuleOrder(RuleOrder.Kind.SELL_STOP, intent.price()));
            }
        }
        return new RulePlan(shadow.holding, shadow.holding ? shadow.avgPrice : null, orders);
    }

    /**
     * BacktestRunner의 보유 판정만 떼어 낸 그림자 장부(1단위, 현금 무한). 평단은 러너와 같이 슬리피지를 얹은 체결가다
     * (트랙 H 실험과 같은 {@link CostModel#defaults()} — 볼린저 손절이 평단 × 0.97이라 평단이 같아야 판정이 같다).
     */
    static final class Shadow {
        private static final BigDecimal UNLIMITED_CASH = new BigDecimal("1000000000000");
        private static final CostModel COST = CostModel.defaults();
        boolean holding;
        BigDecimal avgPrice = BigDecimal.ZERO;

        BacktestStrategy.PortfolioState state() {
            return new BacktestStrategy.PortfolioState(holding ? 1 : 0, holding ? avgPrice : BigDecimal.ZERO,
                    UNLIMITED_CASH);
        }

        void apply(List<TradeIntent> intents, Candle today) {
            if (holding) {
                TradeIntent stop = triggeredSellStop(intents, today);
                if (stop != null) {
                    sell();
                } else if (has(intents, TradeIntent.Kind.SELL_NEXT_OPEN)) {
                    sell();
                }
            }
            if (!holding) {
                TradeIntent buy = triggeredBuyStop(intents, today);
                if (buy != null) {
                    holding = true;
                    avgPrice = COST.slippageAdjustedBuyPrice(buy.price().max(today.open()));
                    if (triggeredSellStop(intents, today) != null) {
                        sell();
                    }
                }
            }
        }

        private void sell() {
            holding = false;
            avgPrice = BigDecimal.ZERO;
        }

        private static TradeIntent triggeredSellStop(List<TradeIntent> intents, Candle today) {
            for (TradeIntent intent : intents) {
                if (intent.kind() == TradeIntent.Kind.SELL_STOP && today.low().compareTo(intent.price()) <= 0) {
                    return intent;
                }
            }
            return null;
        }

        private static TradeIntent triggeredBuyStop(List<TradeIntent> intents, Candle today) {
            for (TradeIntent intent : intents) {
                if (intent.kind() == TradeIntent.Kind.BUY_STOP && today.high().compareTo(intent.price()) >= 0) {
                    return intent;
                }
            }
            return null;
        }

        private static boolean has(List<TradeIntent> intents, TradeIntent.Kind kind) {
            return intents.stream().anyMatch(i -> i.kind() == kind);
        }
    }
}
