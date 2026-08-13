package com.autostock.backtest;

import com.autostock.common.event.Candle;
import com.autostock.strategy.BreakoutMath;

import java.math.BigDecimal;
import java.util.List;

/**
 * 변동성 돌파(Volatility Breakout) 전략 v1 — Larry Williams의 고전 변동성 돌파 기법을
 * 국내 주식 일봉에 적용한 형태다.
 *
 * <p>국내 실증 연구(한국콘텐츠학회 논문지에 실린 변동성 돌파 전략 유효성 검증 연구 등)에서도
 * k값 0.5 부근에서 코스피/코스닥 종목에 통계적으로 유의한 초과수익이 보고된 바 있다.
 * 다만 그 연구들은 대개 거래비용을 낙관적으로 가정하는 경우가 많아, 이 구현은 반드시
 * {@link CostModel}과 {@link PerformanceCalculator}의 DSR을 함께 봐야 한다(PLAN 2절
 * 과최적화 경고) — 논문의 초과수익이 이 프로젝트의 비용모델 안에서도 살아남는지가 진짜 검증이다.
 *
 * <h2>로직</h2>
 * <ul>
 *   <li><b>미보유</b>: 전일 캔들의 고가/저가와 오늘 시가로 {@link BreakoutMath#target}
 *       목표가를 계산해 {@link TradeIntent#buyStop(BigDecimal)}을 건다. {@code stopLossPct}가
 *       설정돼 있으면(null이 아니면) 목표가 대비 손절가로 {@link TradeIntent#sellStop(BigDecimal)}도
 *       함께 건다 — 진입과 동시에 최악의 경우를 대비한다.</li>
 *   <li><b>보유</b>: 다음 판단 시점(오늘 개장 직전, 즉 진입일 기준 "익일")의 시가에 무조건
 *       전량 매도한다({@link TradeIntent#sellNextOpen()}) — 하루~이틀의 짧은 보유 기간을
 *       가정하는 단기 추세추종 전략이다.</li>
 * </ul>
 *
 * <p>전일 데이터가 아직 없는 첫 캔들에서는 목표가를 계산할 수 없으므로 아무 것도 하지 않는다.
 */
public final class VolatilityBreakoutStrategy implements BacktestStrategy {

    private final double k;
    private final Double stopLossPct; // null이면 손절 미사용

    private Candle prevCandle;

    /** 기본 파라미터: k=0.5, 손절 -3%. */
    public VolatilityBreakoutStrategy() {
        this(0.5, -0.03);
    }

    /**
     * @param k           변동성 계수. {@link BreakoutMath#target} 참고.
     * @param stopLossPct 손절 비율(음수, 예: -0.03 = -3%). null이면 손절 미사용.
     */
    public VolatilityBreakoutStrategy(double k, Double stopLossPct) {
        this.k = k;
        this.stopLossPct = stopLossPct;
    }

    @Override
    public List<TradeIntent> onCandle(Candle today, PortfolioState state) {
        List<TradeIntent> intents;
        if (state.hasPosition()) {
            // 보유 중 — "익일 시가 매도"를 오늘 시점에서 다시 신청한다. 개장 직전에 판단하므로
            // 이 시가가 바로 오늘 시가이고, 그 즉시 러너가 체결시킨다(BacktestRunner 타이밍 모델 참고).
            intents = List.of(TradeIntent.sellNextOpen());
        } else if (prevCandle != null) {
            // 미보유 — 전일(어제) 고가/저가로 오늘의 돌파 목표가를 계산한다. 오늘 데이터는
            // 시가만 사용한다(룩어헤드 금지, BreakoutMath 참고).
            BigDecimal target = BreakoutMath.target(today.open(), prevCandle.high(), prevCandle.low(), k);
            if (stopLossPct != null) {
                BigDecimal stopPrice = target.multiply(BigDecimal.valueOf(1 + stopLossPct));
                intents = List.of(TradeIntent.buyStop(target), TradeIntent.sellStop(stopPrice));
            } else {
                intents = List.of(TradeIntent.buyStop(target));
            }
        } else {
            // 첫 캔들 — 아직 "전일" 데이터가 없어 목표가를 계산할 수 없다.
            intents = List.of();
        }
        // 오늘 캔들은 이 호출이 끝나면 "확정된 과거"가 되므로, 다음 호출(내일)에서
        // "전일" 데이터로 안전하게 참조할 수 있다 — 오늘 이 메서드 안에서는 아직 안 쓴다.
        prevCandle = today;
        return intents;
    }
}
