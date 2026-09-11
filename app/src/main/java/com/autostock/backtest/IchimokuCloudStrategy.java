package com.autostock.backtest;

import com.autostock.common.event.Candle;

import java.util.ArrayList;
import java.util.List;

/**
 * H1. 일목균형표 구름 돌파 전략 (트랙 H — TA 인기 기법 trial, 사전 선언 2026-09-11).
 *
 * <p>TradingView 아이디어에서 가장 자주 쓰이는 기법 중 하나인 일목균형표(Ichimoku)를
 * 기계적 규칙으로 고정한 것. 파라미터는 표준(9, 26, 52, 선행 26) <b>단일 고정</b> — 스윕 없음.
 *
 * <h2>규칙 (전부 확정 캔들 = 어제까지의 데이터만 사용, 룩어헤드 없음)</h2>
 * <ul>
 *   <li><b>진입</b>: 미보유 상태에서 어제 종가가 구름 상단(선행스팬 A/B 중 큰 값) 위 →
 *       오늘 시가 매수. 구름은 26봉 전에 계산된 스팬이 오늘 위치에 선행 표시된 표준 정의를
 *       그대로 따른다(스팬 계산 시점 t-26).</li>
 *   <li><b>청산</b>: 보유 중 어제 종가가 구름 하단 아래 → 익일(오늘) 시가 매도.
 *       구름 내부에서는 아무것도 하지 않는다(추세 불명 구간 홀드).</li>
 * </ul>
 */
public final class IchimokuCloudStrategy implements BacktestStrategy {

    private static final int TENKAN = 9;
    private static final int KIJUN = 26;
    private static final int SENKOU_B = 52;
    private static final int SHIFT = 26;

    /** 확정 캔들(어제까지) 이력. */
    private final List<Candle> history = new ArrayList<>();
    private Candle prevCandle;

    @Override
    public List<TradeIntent> onCandle(Candle today, PortfolioState state) {
        if (prevCandle != null) {
            history.add(prevCandle);
        }

        List<TradeIntent> intents = List.of();
        int n = history.size();
        if (n >= SENKOU_B + SHIFT) {
            int t = n - 1 - SHIFT; // 구름을 만든 시점(26봉 전) — 표준 선행 표시
            double spanA = (midpoint(t, TENKAN) + midpoint(t, KIJUN)) / 2.0;
            double spanB = midpoint(t, SENKOU_B);
            double cloudTop = Math.max(spanA, spanB);
            double cloudBottom = Math.min(spanA, spanB);
            double yesterdayClose = history.get(n - 1).close().doubleValue();

            if (state.hasPosition()) {
                if (yesterdayClose < cloudBottom) {
                    intents = List.of(TradeIntent.sellNextOpen());
                }
            } else if (yesterdayClose > cloudTop) {
                intents = List.of(TradeIntent.buyAtOpen(today.open()));
            }
        }

        prevCandle = today;
        return intents;
    }

    /** history[endIdx-len+1..endIdx] 구간의 (최고가+최저가)/2 — 일목 전 지표의 공통 공식. */
    private double midpoint(int endIdx, int len) {
        double hi = Double.NEGATIVE_INFINITY;
        double lo = Double.POSITIVE_INFINITY;
        for (int i = endIdx - len + 1; i <= endIdx; i++) {
            Candle c = history.get(i);
            hi = Math.max(hi, c.high().doubleValue());
            lo = Math.min(lo, c.low().doubleValue());
        }
        return (hi + lo) / 2.0;
    }
}
