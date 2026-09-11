package com.autostock.backtest;

import com.autostock.common.event.Candle;

import java.util.List;

/**
 * H3. SuperTrend(10, 3) 추세 추종 전략 (트랙 H — TA 인기 기법 trial, 사전 선언 2026-09-11).
 *
 * <p>TradingView 내장 지표 중 사용량 최상위권인 SuperTrend의 표준 구현.
 * ATR(10) × 배수 3.0의 상/하 밴드가 래칫(ratchet) 방식으로 조여들다가 종가가 밴드를
 * 넘으면 추세가 뒤집히는 고전적 정의를 그대로 따른다. 파라미터 단일 고정 — 스윕 없음.
 *
 * <h2>규칙 (확정 캔들만 사용 — 지표는 어제 종가까지로 갱신)</h2>
 * <ul>
 *   <li><b>진입</b>: 미보유 + 어제 기준 추세가 상승(trend up) → 오늘 시가 매수.</li>
 *   <li><b>청산</b>: 보유 + 어제 기준 추세가 하락 전환 → 익일(오늘) 시가 매도.</li>
 * </ul>
 * 별도 손절은 없다 — SuperTrend 자체가 추세 반전 시 청산하는 트레일링 스탑 역할을 겸한다
 * (지표의 표준 사용법).
 */
public final class SuperTrendStrategy implements BacktestStrategy {

    private static final int ATR_PERIOD = 10;
    private static final double MULTIPLIER = 3.0;

    private Candle prevCandle;

    // ── 지표 상태(어제 캔들까지 반영된 값) ──
    private int confirmedCount;
    private double atr;                 // Wilder ATR
    private double atrSeedSum;          // 초기 SMA 시드 누적
    private double prevProcessedClose;  // TR 계산용 직전 종가
    private double finalUpper = Double.NaN;
    private double finalLower = Double.NaN;
    private boolean trendUp;
    private boolean trendInitialized;

    @Override
    public List<TradeIntent> onCandle(Candle today, PortfolioState state) {
        if (prevCandle != null) {
            update(prevCandle);
        }

        List<TradeIntent> intents = List.of();
        if (trendInitialized) {
            if (state.hasPosition()) {
                if (!trendUp) {
                    intents = List.of(TradeIntent.sellNextOpen());
                }
            } else if (trendUp) {
                intents = List.of(TradeIntent.buyAtOpen(today.open()));
            }
        }

        prevCandle = today;
        return intents;
    }

    /** 확정 캔들 하나를 지표에 반영한다 — 표준 SuperTrend 갱신식. */
    private void update(Candle c) {
        double high = c.high().doubleValue();
        double low = c.low().doubleValue();
        double close = c.close().doubleValue();

        confirmedCount++;
        if (confirmedCount == 1) {
            prevProcessedClose = close;
            atrSeedSum = high - low;
            return;
        }

        double tr = Math.max(high - low,
                Math.max(Math.abs(high - prevProcessedClose), Math.abs(low - prevProcessedClose)));
        if (confirmedCount <= ATR_PERIOD) {
            atrSeedSum += tr;
            if (confirmedCount == ATR_PERIOD) {
                atr = atrSeedSum / ATR_PERIOD;
            }
            prevProcessedClose = close;
            return;
        }
        atr = (atr * (ATR_PERIOD - 1) + tr) / ATR_PERIOD; // Wilder smoothing

        double mid = (high + low) / 2.0;
        double basicUpper = mid + MULTIPLIER * atr;
        double basicLower = mid - MULTIPLIER * atr;

        // 래칫: 상단은 내려가기만, 하단은 올라가기만 한다(직전 종가가 밴드를 넘었으면 리셋)
        double newUpper = (Double.isNaN(finalUpper) || basicUpper < finalUpper || prevProcessedClose > finalUpper)
                ? basicUpper : finalUpper;
        double newLower = (Double.isNaN(finalLower) || basicLower > finalLower || prevProcessedClose < finalLower)
                ? basicLower : finalLower;

        if (!trendInitialized) {
            trendUp = close > newUpper;
            trendInitialized = true;
        } else if (trendUp && close < newLower) {
            trendUp = false;
        } else if (!trendUp && close > newUpper) {
            trendUp = true;
        }

        finalUpper = newUpper;
        finalLower = newLower;
        prevProcessedClose = close;
    }
}
