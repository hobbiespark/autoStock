package com.autostock.backtest;

import com.autostock.common.event.Candle;

import java.util.ArrayList;
import java.util.List;

/**
 * H4. RSI(2) 평균회귀 전략 — Connors 방식 (트랙 H — TA 인기 기법 trial, 사전 선언 2026-09-11).
 *
 * <p>단기 기법 중 학술·실무 검증 시도가 가장 많은 축에 드는 Connors RSI(2) 규칙의 표준형.
 * 파라미터 단일 고정(RSI 2 / 진입 임계 10 / 장기추세 SMA200 / 청산 SMA5) — 스윕 없음.
 *
 * <h2>규칙 (확정 캔들만 사용)</h2>
 * <ul>
 *   <li><b>진입</b>: 미보유 + 어제 RSI(2) &lt; 10 + 어제 종가 &gt; SMA200(장기 상승 추세에서만
 *       역추세 매수) → 오늘 시가 매수.</li>
 *   <li><b>청산</b>: 보유 + 어제 종가 &gt; SMA5 → 익일(오늘) 시가 매도(평균 복귀 완료로 간주).</li>
 * </ul>
 * 손절 없음 — 원 규칙 그대로(짧은 보유·SMA200 필터가 방어를 대신한다는 설계).
 */
public final class Rsi2MeanReversionStrategy implements BacktestStrategy {

    private static final int RSI_PERIOD = 2;
    private static final double ENTRY_RSI = 10.0;
    private static final int TREND_SMA = 200;
    private static final int EXIT_SMA = 5;

    private final List<Double> closes = new ArrayList<>();
    private Candle prevCandle;

    // Wilder RSI 상태
    private double avgGain;
    private double avgLoss;
    private int rsiSamples;

    @Override
    public List<TradeIntent> onCandle(Candle today, PortfolioState state) {
        if (prevCandle != null) {
            addConfirmedClose(prevCandle.close().doubleValue());
        }

        List<TradeIntent> intents = List.of();
        if (closes.size() >= TREND_SMA && rsiSamples >= RSI_PERIOD) {
            double yesterdayClose = closes.get(closes.size() - 1);
            if (state.hasPosition()) {
                if (yesterdayClose > sma(EXIT_SMA)) {
                    intents = List.of(TradeIntent.sellNextOpen());
                }
            } else if (rsi() < ENTRY_RSI && yesterdayClose > sma(TREND_SMA)) {
                intents = List.of(TradeIntent.buyAtOpen(today.open()));
            }
        }

        prevCandle = today;
        return intents;
    }

    private void addConfirmedClose(double close) {
        if (!closes.isEmpty()) {
            double change = close - closes.get(closes.size() - 1);
            double gain = Math.max(change, 0.0);
            double loss = Math.max(-change, 0.0);
            if (rsiSamples < RSI_PERIOD) {
                avgGain += gain / RSI_PERIOD;
                avgLoss += loss / RSI_PERIOD;
            } else {
                avgGain = (avgGain * (RSI_PERIOD - 1) + gain) / RSI_PERIOD;
                avgLoss = (avgLoss * (RSI_PERIOD - 1) + loss) / RSI_PERIOD;
            }
            rsiSamples++;
        }
        closes.add(close);
    }

    private double rsi() {
        if (avgLoss == 0.0) {
            return avgGain == 0.0 ? 50.0 : 100.0;
        }
        double rs = avgGain / avgLoss;
        return 100.0 - 100.0 / (1.0 + rs);
    }

    private double sma(int window) {
        double sum = 0.0;
        for (int i = closes.size() - window; i < closes.size(); i++) {
            sum += closes.get(i);
        }
        return sum / window;
    }
}
