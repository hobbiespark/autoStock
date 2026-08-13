package com.autostock.backtest;

import com.autostock.common.event.Candle;
import com.autostock.strategy.BreakoutMath;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 필터 돌파(Filtered Breakout) 전략 — {@link VolatilityBreakoutStrategy}의 무필터 진입 조건에
 * 두 가지 "진입 억제" 필터를 추가해 거래 빈도 자체를 줄인 전략.
 *
 * <h2>왜 별도 클래스인가 (VolatilityBreakoutStrategy를 확장하지 않는 이유)</h2>
 * 무필터 돌파 전략(A안)과 필터 돌파 전략(B안)을 같은 walk-forward 파이프라인으로
 * "공정 비교"하는 것이 이번 실험의 목적이다. 만약 B가 A를 상속(확장)하면, A 쪽 코드를
 * 조금만 손대도 B의 동작이 같이 바뀌어버려 "정확히 필터 유무만 다르다"는 비교의 전제가
 * 흔들릴 수 있다. 그래서 A는 절대 건드리지 않고, 이 클래스가 A와 동일한 진입/청산 로직을
 * (BreakoutMath라는 공통 순수함수를 통해) 그대로 재사용하면서 필터만 새로 얹는 방식으로
 * 완전히 독립된 클래스로 구현했다.
 *
 * <h2>실험 배경 — 왜 필터를 추가하는가</h2>
 * 무필터 변동성 돌파는 실데이터 walk-forward에서 연 250~300회의 왕복 매매가 발생해
 * 매매당 ~0.35%의 비용 드래그를 감당하지 못하고 게이트①에서 FAIL했다(PLAN 배경 참고).
 * 이 전략은 "돌파가 났다고 무조건 사지 말고, 상황이 좋을 때만 사자"는 가설로 진입
 * 조건을 좁혀 거래 횟수 자체를 줄이는 접근이다.
 *
 * <h2>필터 두 가지</h2>
 * <ul>
 *   <li><b>추세 필터</b>: 오늘 시가가 직전 20봉 종가의 단순이동평균(SMA20)보다 높을 때만
 *       진입을 허용한다 — "추세가 이미 우상향인 국면에서만 돌파를 신뢰한다"는 생각.
 *       SMA는 반드시 "오늘 이전"의 종가만으로 계산한다(당일 데이터 사용 금지 — 룩어헤드
 *       방지). 아직 20개의 과거 종가가 쌓이지 않았으면(백테스트 구간 초반) 데이터 부족을
 *       이유로 보수적으로 진입을 막는다.</li>
 *   <li><b>전일 캔들 필터</b>: 전일 캔들이 양봉(전일 종가 &gt; 전일 시가)일 때만 진입을
 *       허용한다 — "전날 매수세가 매도세를 이겼던 날의 다음날만 돌파를 신뢰한다"는 생각.</li>
 * </ul>
 * 두 필터는 생성자 플래그로 개별 on/off 할 수 있다 — 필터 하나씩의 기여도를 따로
 * 실험하고 싶을 때를 위해서다. 이번 비교 실험(RealDataStrategyComparisonTest)에서는
 * 두 필터를 모두 켠 조합만 쓴다.
 *
 * <h2>진입/청산 로직 자체는 원본과 동일</h2>
 * 필터를 모두 통과했을 때만 {@link VolatilityBreakoutStrategy}와 완전히 같은 방식으로
 * buyStop(+ 손절 sellStop)을 건다. 청산도 동일하게 "보유 중이면 익일 시가 매도"다.
 */
public final class FilteredBreakoutStrategy implements BacktestStrategy {

    /** 추세 필터에 쓰는 이동평균 창 길이(봉 수). */
    private static final int SMA_WINDOW = 20;

    private final double k;
    private final Double stopLossPct; // null이면 손절 미사용
    private final boolean useTrendFilter;
    private final boolean usePrevCandleFilter;

    /** 직전 최대 20개의 "확정된" 종가(오늘 이전) — 추세 필터의 SMA20 계산용. */
    private final Deque<BigDecimal> closeWindow = new ArrayDeque<>();

    private Candle prevCandle;

    /** 기본 파라미터: k=0.5, 손절 -3%, 두 필터 모두 켬. */
    public FilteredBreakoutStrategy() {
        this(0.5, -0.03, true, true);
    }

    /** 두 필터를 모두 켠 채로 k와 손절만 지정하는 편의 생성자. */
    public FilteredBreakoutStrategy(double k, Double stopLossPct) {
        this(k, stopLossPct, true, true);
    }

    /**
     * @param k                    변동성 계수. {@link BreakoutMath#target} 참고.
     * @param stopLossPct          손절 비율(음수, 예: -0.03 = -3%). null이면 손절 미사용.
     * @param useTrendFilter       true면 추세 필터(오늘 시가 &gt; SMA20)를 적용한다.
     * @param usePrevCandleFilter  true면 전일 캔들 필터(전일 양봉)를 적용한다.
     */
    public FilteredBreakoutStrategy(double k, Double stopLossPct, boolean useTrendFilter, boolean usePrevCandleFilter) {
        this.k = k;
        this.stopLossPct = stopLossPct;
        this.useTrendFilter = useTrendFilter;
        this.usePrevCandleFilter = usePrevCandleFilter;
    }

    @Override
    public List<TradeIntent> onCandle(Candle today, PortfolioState state) {
        // ── SMA20 창을 먼저 갱신한다 — "직전 20봉"에는 어제(prevCandle)까지 포함돼야 하므로,
        // 오늘 판단에 쓰기 전에 prevCandle의 종가를 창에 반영해둔다. prevCandle 자체는 아직
        // "오늘"로 갱신되지 않은 상태이므로 이 시점의 창에는 결코 오늘 데이터가 섞이지 않는다.
        if (prevCandle != null) {
            closeWindow.addLast(prevCandle.close());
            if (closeWindow.size() > SMA_WINDOW) {
                closeWindow.removeFirst();
            }
        }

        List<TradeIntent> intents;
        if (state.hasPosition()) {
            // 보유 중 — 원본과 동일하게 익일 시가 매도만 신청한다.
            intents = List.of(TradeIntent.sellNextOpen());
        } else if (prevCandle != null && passesTrendFilter(today) && passesPrevCandleFilter()) {
            // 미보유 + 두 필터 모두 통과 — 원본과 동일한 돌파 목표가 계산 및 진입.
            BigDecimal target = BreakoutMath.target(today.open(), prevCandle.high(), prevCandle.low(), k);
            if (stopLossPct != null) {
                BigDecimal stopPrice = target.multiply(BigDecimal.valueOf(1 + stopLossPct));
                intents = List.of(TradeIntent.buyStop(target), TradeIntent.sellStop(stopPrice));
            } else {
                intents = List.of(TradeIntent.buyStop(target));
            }
        } else {
            // 첫 캔들이거나(전일 데이터 없음), 필터 중 하나라도 막았으면 아무 것도 하지 않는다.
            intents = List.of();
        }

        prevCandle = today;
        return intents;
    }

    /**
     * 추세 필터: 오늘 시가 &gt; 직전 20봉 종가의 SMA. 아직 20개가 쌓이지 않았으면(초반 구간)
     * 보수적으로 진입을 막는다(데이터 부족을 "추세 불명"으로 취급).
     */
    private boolean passesTrendFilter(Candle today) {
        if (!useTrendFilter) {
            return true;
        }
        if (closeWindow.size() < SMA_WINDOW) {
            return false;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal close : closeWindow) {
            sum = sum.add(close);
        }
        BigDecimal sma = sum.divide(BigDecimal.valueOf(SMA_WINDOW), 10, RoundingMode.HALF_UP);
        return today.open().compareTo(sma) > 0;
    }

    /** 전일 캔들 필터: 전일 종가 &gt; 전일 시가(양봉)일 때만 통과. */
    private boolean passesPrevCandleFilter() {
        if (!usePrevCandleFilter) {
            return true;
        }
        return prevCandle.close().compareTo(prevCandle.open()) > 0;
    }
}
