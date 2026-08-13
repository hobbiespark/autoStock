package com.autostock.backtest;

import com.autostock.common.event.Candle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 변동성 타게팅(volatility targeting) 전략 데코레이터 — 내부 전략이 매수 진입을 낼 때,
 * 최근 실현 변동성에 반비례해 투입 비중({@link TradeIntent#fraction()})을 줄인다.
 *
 * <h2>왜 필요한가 — MDD 절감 가설</h2>
 * 국면 필터가 "언제 쉴지"를 정한다면, 변동성 타게팅은 "얼마나 걸지"를 정한다. 최근
 * 가격 변동이 격했던(실현 변동성이 목표치보다 높은) 시기에 전액을 투입하면, 같은 방향
 * 전환 신호라도 낙폭이 그만큼 커진다. 그래서 최근 변동성이 목표(연 20%)보다 높으면
 * 투입 비중을 줄이고, 낮으면(또는 관측이 부족하면) 전액(1.0)을 그대로 투입한다.
 *
 * <h2>계산식</h2>
 * <pre>
 *   realizedVol = stdDev(최근 20봉 일간수익률) × sqrt(252)   (연환산 실현 변동성)
 *   fraction    = min(1.0, targetVol / realizedVol)
 * </pre>
 * 일간수익률은 종가 기준 전일 대비 변화율이다. 관측치가 20개 미만(백테스트 초반)이면
 * 판단 근거 부족으로 보수적이지 않고(=제한하지 않고) fraction=1.0을 그대로 쓴다 — 이
 * 전략은 "위험을 낮추는" 것이 목적이지 "무조건 적게 사는"것이 목적이 아니므로, 데이터가
 * 없을 때 기본값은 "내부 전략을 있는 그대로 신뢰"하는 쪽이 자연스럽다.
 *
 * <h2>룩어헤드 방지</h2>
 * 변동성 계산에 쓰는 20개의 일간수익률은 모두 "오늘 이전"에 이미 확정된 종가들로만
 * 구한다. 종가 이력({@link #closeHistory})에는 항상 "오늘 판단이 끝난 뒤" 오늘 종가를
 * 추가하므로, 판단 시점(오늘 fraction 계산)에는 결코 오늘 종가가 섞여 들어가지 않는다.
 *
 * <h2>합성 방식 — 기존 fraction과 곱한다</h2>
 * 내부 전략이 이미 어떤 fraction을 갖고 있었다면(다른 사이징 데코레이터와 중첩되는 경우
 * 등) 그 값에 새로 계산한 비중을 곱한다({@code intent.fraction() * fraction}) — 데코레이터를
 * 어떤 순서로 겹쳐도(예: 국면 필터 위에 변동성 타게팅을 얹거나 그 반대로) 비중이 서로를
 * 덮어쓰지 않고 누적되도록 하기 위함이다. 매도 계열 인텐트는 손대지 않는다(청산은 항상
 * 전량이므로 fraction이 의미가 없다).
 */
public final class VolatilityTargetingStrategy implements BacktestStrategy {

    /** 실현 변동성 계산에 쓰는 일간수익률 관측 창(봉 수). */
    private static final int VOL_WINDOW = 20;

    /** 연환산 거래일수 — PerformanceCalculator와 동일한 관례. */
    private static final int TRADING_DAYS_PER_YEAR = 252;

    /** 기본 목표 변동성(연 20%). */
    private static final double DEFAULT_TARGET_VOL = 0.20;

    private final BacktestStrategy inner;
    private final double targetVol;

    /** 최근 확정 종가를 최대 (VOL_WINDOW+1)개 보관 — 20개의 일간수익률을 만들려면 종가 21개가 필요. */
    private final Deque<BigDecimal> closeHistory = new ArrayDeque<>();

    public VolatilityTargetingStrategy(BacktestStrategy inner) {
        this(inner, DEFAULT_TARGET_VOL);
    }

    /**
     * @param inner     변동성 타게팅으로 감쌀 내부 전략
     * @param targetVol 목표 연변동성(예: 0.20 = 연 20%)
     */
    public VolatilityTargetingStrategy(BacktestStrategy inner, double targetVol) {
        this.inner = inner;
        this.targetVol = targetVol;
    }

    @Override
    public List<TradeIntent> onCandle(Candle today, PortfolioState state) {
        List<TradeIntent> innerIntents = inner.onCandle(today, state);

        List<TradeIntent> result = innerIntents;
        if (!innerIntents.isEmpty()) {
            double fraction = computeFraction();
            if (fraction < 1.0) {
                result = scaleBuyIntents(innerIntents, fraction);
            }
        }

        // 오늘 종가는 오늘 판단(computeFraction)이 끝난 뒤에만 이력에 반영한다(룩어헤드 방지).
        closeHistory.addLast(today.close());
        if (closeHistory.size() > VOL_WINDOW + 1) {
            closeHistory.removeFirst();
        }

        return result;
    }

    /** BUY_STOP 인텐트에만 fraction을 곱해 새로 만든다. 매도 계열은 그대로 둔다. */
    private List<TradeIntent> scaleBuyIntents(List<TradeIntent> intents, double fraction) {
        List<TradeIntent> adjusted = new ArrayList<>(intents.size());
        for (TradeIntent intent : intents) {
            if (intent.kind() == TradeIntent.Kind.BUY_STOP) {
                double combined = Math.min(1.0, intent.fraction() * fraction);
                adjusted.add(TradeIntent.buyStop(intent.price(), combined));
            } else {
                adjusted.add(intent);
            }
        }
        return adjusted;
    }

    /** min(1.0, targetVol / realizedVol) — 관측 부족(20개 미만의 일간수익률)이면 1.0. */
    private double computeFraction() {
        if (closeHistory.size() < VOL_WINDOW + 1) {
            return 1.0;
        }

        List<BigDecimal> closes = new ArrayList<>(closeHistory);
        double[] dailyReturns = new double[VOL_WINDOW];
        for (int i = 1; i < closes.size(); i++) {
            BigDecimal prev = closes.get(i - 1);
            BigDecimal curr = closes.get(i);
            dailyReturns[i - 1] = prev.signum() == 0
                    ? 0.0
                    : curr.subtract(prev).divide(prev, 12, RoundingMode.HALF_UP).doubleValue();
        }

        double mean = mean(dailyReturns);
        double std = populationStd(dailyReturns, mean);
        double realizedVol = std * Math.sqrt(TRADING_DAYS_PER_YEAR);
        if (realizedVol == 0.0) {
            return 1.0; // 최근 변동이 전혀 없었으면 제한할 이유가 없음
        }
        return Math.min(1.0, targetVol / realizedVol);
    }

    private double mean(double[] values) {
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return values.length == 0 ? 0.0 : sum / values.length;
    }

    /** 모표준편차(분모 n) — PerformanceCalculator와 동일한 관례. */
    private double populationStd(double[] values, double mean) {
        double sumSq = 0.0;
        for (double v : values) {
            double d = v - mean;
            sumSq += d * d;
        }
        return Math.sqrt(sumSq / values.length);
    }
}
