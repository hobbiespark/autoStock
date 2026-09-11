package com.autostock.backtest;

import com.autostock.common.event.Candle;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * H2. 볼린저 밴드 스퀴즈 돌파 전략 (트랙 H — TA 인기 기법 trial, 사전 선언 2026-09-11).
 *
 * <p>"밴드폭이 극도로 수축(스퀴즈)한 뒤에는 강한 추세가 나온다"는 대중적 가설의 기계화.
 * 파라미터 표준 고정: BB(20, 2.0), 스퀴즈 판정 = 밴드폭이 직전 120일 밴드폭의 하위 20% 이내,
 * 손절 -3%(A/B 계열과 동일 관례). 스윕 없음.
 *
 * <h2>규칙 (확정 캔들만 사용)</h2>
 * <ul>
 *   <li><b>진입</b>: 미보유 + 어제 기준 스퀴즈 상태 → 어제 상단 밴드 가격에 buyStop
 *       (오늘 장중 상단 돌파 시 체결) + 진입가 -3% sellStop 동시 등록.</li>
 *   <li><b>청산</b>: 보유 중 어제 종가 &lt; 어제 중간 밴드(SMA20) → 익일 시가 매도.
 *       그 외에는 평단 -3% 트레일 아닌 고정 sellStop 유지.</li>
 * </ul>
 */
public final class BollingerSqueezeStrategy implements BacktestStrategy {

    private static final int WINDOW = 20;
    private static final double NUM_SD = 2.0;
    private static final int BANDWIDTH_LOOKBACK = 120;
    private static final double SQUEEZE_QUANTILE = 0.20;
    private static final double STOP_LOSS_PCT = -0.03;

    /** 확정 종가(어제까지). */
    private final List<Double> closes = new ArrayList<>();
    /** 확정 밴드폭 이력((upper-lower)/middle). */
    private final List<Double> bandwidths = new ArrayList<>();
    private Candle prevCandle;

    @Override
    public List<TradeIntent> onCandle(Candle today, PortfolioState state) {
        if (prevCandle != null) {
            closes.add(prevCandle.close().doubleValue());
            if (closes.size() >= WINDOW) {
                double middle = sma(closes, WINDOW);
                double sd = stdDev(closes, WINDOW, middle);
                double upper = middle + NUM_SD * sd;
                double lower = middle - NUM_SD * sd;
                bandwidths.add(middle == 0.0 ? 0.0 : (upper - lower) / middle);
            }
        }

        List<TradeIntent> intents = List.of();
        if (closes.size() >= WINDOW) {
            double middle = sma(closes, WINDOW);
            double sd = stdDev(closes, WINDOW, middle);
            double upper = middle + NUM_SD * sd;
            double yesterdayClose = closes.get(closes.size() - 1);

            if (state.hasPosition()) {
                if (yesterdayClose < middle) {
                    intents = List.of(TradeIntent.sellNextOpen());
                } else {
                    // 보유 유지 — 평단 기준 고정 손절만 걸어둔다
                    BigDecimal stop = state.avgPrice().multiply(BigDecimal.valueOf(1 + STOP_LOSS_PCT));
                    intents = List.of(TradeIntent.sellStop(stop));
                }
            } else if (isSqueezed()) {
                BigDecimal trigger = BigDecimal.valueOf(upper);
                BigDecimal stop = trigger.multiply(BigDecimal.valueOf(1 + STOP_LOSS_PCT));
                intents = List.of(TradeIntent.buyStop(trigger), TradeIntent.sellStop(stop));
            }
        }

        prevCandle = today;
        return intents;
    }

    /** 어제 밴드폭이 직전 120개 밴드폭의 하위 20% 이내인가. 이력이 부족하면 보수적으로 false. */
    private boolean isSqueezed() {
        if (bandwidths.size() < BANDWIDTH_LOOKBACK) {
            return false;
        }
        double latest = bandwidths.get(bandwidths.size() - 1);
        List<Double> window = bandwidths.subList(bandwidths.size() - BANDWIDTH_LOOKBACK, bandwidths.size());
        int below = 0;
        for (double bw : window) {
            if (bw <= latest) {
                below++;
            }
        }
        return (double) below / BANDWIDTH_LOOKBACK <= SQUEEZE_QUANTILE;
    }

    private static double sma(List<Double> values, int window) {
        double sum = 0.0;
        for (int i = values.size() - window; i < values.size(); i++) {
            sum += values.get(i);
        }
        return sum / window;
    }

    private static double stdDev(List<Double> values, int window, double mean) {
        double sumSq = 0.0;
        for (int i = values.size() - window; i < values.size(); i++) {
            double d = values.get(i) - mean;
            sumSq += d * d;
        }
        return Math.sqrt(sumSq / window);
    }
}
