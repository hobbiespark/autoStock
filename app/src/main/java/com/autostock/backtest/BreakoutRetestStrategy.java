package com.autostock.backtest;

import com.autostock.common.event.Candle;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * H5. 돌파 후 리테스트(S/R 플립) 전략 (트랙 H — TA 인기 기법 trial, 사전 선언 2026-09-11).
 *
 * <p>TradingView 아이디어에서 가장 흔한 재량 패턴 — "저항 돌파 → 그 레벨이 지지로
 * 바뀌는지 되돌림에서 확인 후 진입" — 을 상태기계로 기계화한 것. 돌파 추격을 배제하고
 * 리테스트 확인을 요구하는 것이 원 기법의 핵심이므로 그대로 옮겼다. 파라미터 단일 고정
 * (전고 60일 / 리테스트 대기 15일 / 보유 상한 20일 / 손절 레벨 -3%) — 스윕 없음.
 *
 * <h2>상태기계 (확정 캔들만 사용)</h2>
 * <pre>
 *   대기 → [어제 종가가 직전 60일 최고가(어제 제외) 돌파] → 무장(level=그 최고가, 최대 15일)
 *   무장 → [어제 저가 ≤ level ≤ 어제 종가 — 레벨 터치 후 지지 확인] → 오늘 시가 매수
 *          + level×0.97 sellStop / 15일 내 리테스트 없으면 해제
 *   보유 → [어제 종가 &lt; level(플립 실패) 또는 보유 20일 초과] → 익일 시가 매도
 *          그 외에는 level×0.97 sellStop 유지
 * </pre>
 */
public final class BreakoutRetestStrategy implements BacktestStrategy {

    private static final int HIGH_LOOKBACK = 60;
    private static final int ARM_MAX_DAYS = 15;
    private static final int HOLD_MAX_DAYS = 20;
    private static final double STOP_BELOW_LEVEL = 0.97;

    private final List<Candle> history = new ArrayList<>();
    private Candle prevCandle;

    private double armedLevel = Double.NaN;
    private int armedDays;
    private double heldLevel = Double.NaN;
    private int holdDays;

    @Override
    public List<TradeIntent> onCandle(Candle today, PortfolioState state) {
        if (prevCandle != null) {
            history.add(prevCandle);
        }

        List<TradeIntent> intents = List.of();
        int n = history.size();

        if (state.hasPosition()) {
            holdDays++;
            double yesterdayClose = history.get(n - 1).close().doubleValue();
            if (yesterdayClose < heldLevel || holdDays > HOLD_MAX_DAYS) {
                intents = List.of(TradeIntent.sellNextOpen());
            } else {
                intents = List.of(TradeIntent.sellStop(
                        BigDecimal.valueOf(heldLevel * STOP_BELOW_LEVEL)));
            }
        } else {
            heldLevel = Double.NaN;
            holdDays = 0;

            // ── 1) 무장 상태면 어제 캔들로 리테스트 성사 여부부터 판정 ──
            if (!Double.isNaN(armedLevel) && n >= 1) {
                armedDays++;
                Candle yesterday = history.get(n - 1);
                boolean touched = yesterday.low().doubleValue() <= armedLevel;
                boolean heldAbove = yesterday.close().doubleValue() >= armedLevel;
                if (touched && heldAbove) {
                    // 리테스트 확인 — 오늘 시가 진입 + 레벨 -3% 손절
                    intents = List.of(
                            TradeIntent.buyAtOpen(today.open()),
                            TradeIntent.sellStop(BigDecimal.valueOf(armedLevel * STOP_BELOW_LEVEL)));
                    heldLevel = armedLevel;
                    holdDays = 0;
                    armedLevel = Double.NaN;
                } else if (armedDays > ARM_MAX_DAYS) {
                    armedLevel = Double.NaN; // 대기 만료 — 패턴 무효
                }
            }

            // ── 2) 새 돌파 감지(무장 갱신) — 어제 종가가 직전 60일(어제 제외) 최고가 돌파 ──
            if (Double.isNaN(armedLevel) && intents.isEmpty() && n >= HIGH_LOOKBACK + 1) {
                double priorHigh = Double.NEGATIVE_INFINITY;
                for (int i = n - 1 - HIGH_LOOKBACK; i <= n - 2; i++) {
                    priorHigh = Math.max(priorHigh, history.get(i).high().doubleValue());
                }
                if (history.get(n - 1).close().doubleValue() > priorHigh) {
                    armedLevel = priorHigh;
                    armedDays = 0;
                }
            }
        }

        prevCandle = today;
        return intents;
    }
}
