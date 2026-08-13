package com.autostock.backtest;

import com.autostock.common.event.Candle;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 시장 국면(ON/OFF) 필터를 얹는 전략 데코레이터 — 내부 전략을 감싸서, 시장이 "하락/횡보
 * 국면(OFF)"으로 판정된 날에는 아예 매매 판단을 내부 전략에게 넘기지 않고 강제로 무포지션
 * 상태를 유지시킨다.
 *
 * <h2>왜 필요한가 — MDD 절감 가설</h2>
 * 시계열 모멘텀(C)이 게이트①(MDD&lt;15%)에서 떨어진 이유 중 하나는, 지수 자체가 큰
 * 하락장일 때도 21봉 판단 주기 특성상 반응이 느려 손실 구간을 온전히 떠안기 때문이다
 * (PLAN 배경 — OOS MDD 29.2%). 시장 지수가 이미 장기 추세(SMA200) 아래로 꺾인 국면에서는
 * 개별 종목 모멘텀 신호와 무관하게 아예 진입을 막고 보유 중이면 강제 청산해, "국면이 나쁠
 * 때는 쉬어간다"는 규칙으로 낙폭을 줄이려는 시도다.
 *
 * <h2>OFF일 때의 동작 — 왜 내부 전략을 호출하지 않는가</h2>
 * OFF로 판정된 날에는 내부 전략의 {@code onCandle}을 아예 호출하지 않는다(위임하지 않음).
 * 그 결과 내부 전략이 날짜/봉 카운터나 종가 이력을 내부에 들고 있는 경우(예:
 * {@link TimeSeriesMomentumStrategy}의 21봉 판단 주기, N봉 종가 이력), OFF로 보낸 기간
 * 동안은 그 내부 시계가 함께 멈춘다 — "국면이 나쁜 동안은 모멘텀 판단 자체를 쉰다"는
 * 의도적 설계 판단이다. 대안(내부 전략은 매번 호출해 상태만 최신으로 유지하되 반환하는
 * 인텐트만 무시하는 방식)도 가능하지만, 그러면 내부 전략이 "국면과 무관하게 계속 판단해온
 * 셈"이 되어 국면 필터가 실제로 무엇을 막았는지 해석이 흐려진다. 이 프로젝트의 실험 목적
 * (국면 필터 자체의 효과를 단순하고 명확하게 확인)에는 "OFF면 아예 관여하지 않는다"는
 * 단순한 규칙이 더 적합하다고 판단했다.
 *
 * <h2>ON일 때의 동작</h2>
 * 내부 전략에 그대로 위임한다 — 판단·인텐트 모두 내부 전략의 것을 그대로 반환한다.
 */
public final class RegimeFilteredStrategy implements BacktestStrategy {

    private final BacktestStrategy inner;
    private final Map<LocalDate, Boolean> regimeByDate;

    /**
     * @param inner        국면 필터로 감쌀 내부 전략
     * @param regimeByDate {@link MarketRegime#compute(List)}로 미리 계산해둔 날짜별 ON/OFF 맵.
     *                     맵에 없는 날짜(지수 데이터가 없는 날 등)는 보수적으로 ON(중립)으로 취급한다.
     */
    public RegimeFilteredStrategy(BacktestStrategy inner, Map<LocalDate, Boolean> regimeByDate) {
        this.inner = inner;
        this.regimeByDate = regimeByDate;
    }

    @Override
    public List<TradeIntent> onCandle(Candle today, PortfolioState state) {
        boolean on = regimeByDate.getOrDefault(today.date(), true);

        if (!on) {
            // OFF(하락/횡보 국면) — 보유 중이면 강제 청산, 미보유면 신규 진입 자체를 막는다.
            // 내부 전략은 호출하지 않는다(클래스 설명의 "OFF일 때의 동작" 참고).
            return state.hasPosition() ? List.of(TradeIntent.sellNextOpen()) : List.of();
        }

        return inner.onCandle(today, state);
    }
}
