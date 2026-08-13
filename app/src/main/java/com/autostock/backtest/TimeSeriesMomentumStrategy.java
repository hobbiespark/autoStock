package com.autostock.backtest;

import com.autostock.common.event.Candle;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 시계열 모멘텀(Time-Series Momentum) 전략 — 저빈도 대표 전략.
 *
 * <h2>설계 판단 — 왜 "시계열" 모멘텀인가 (크로스섹션 모멘텀 대신)</h2>
 * 학계·업계에서 "모멘텀"이라고 하면 보통 크로스섹션 모멘텀(여러 종목을 동시에 비교해
 * 상대적으로 강한 종목을 사고 약한 종목을 파는 방식)이나 저변동성 팩터(변동성이 낮은
 * 종목을 골라 담는 방식)를 먼저 떠올린다. 하지만 이 프로젝트의 {@link BacktestRunner}는
 * "단일 종목" 캔들 목록을 순서대로 재생하는 구조라서, 여러 종목을 한 번에 놓고 비교하는
 * 로직을 표현할 수 없다(종목 간 상대 비교, 포트폴리오 리밸런싱 등은 완전히 다른 러너가
 * 필요하다). 그래서 이번 실험에서는 크로스섹션 모멘텀/저변동성 팩터 대신, 한 종목만으로도
 * 성립하는 <b>시계열 모멘텀</b>(자기 자신의 과거 가격과 비교해 상승 추세면 보유, 하락
 * 추세면 청산)으로 대체했다 — "다종목이 필요한 전략을 단일종목 러너에서 억지로 흉내내지
 * 않는다"는 설계 판단이다.
 *
 * <h2>가설 — 왜 이 전략이 비용 드래그에 구조적으로 강할 것으로 기대하는가</h2>
 * 무필터 변동성 돌파는 거의 매일 진입/청산 여부를 판단하고 짧으면 하루이틀 만에
 * 포지션을 정리하기 때문에 연 250~300회의 왕복 매매가 나온다(PLAN 배경). 반면 이
 * 전략은 21거래일(대략 한 달)에 딱 한 번만 "보유할지 말지"를 판단하고, 판단이 바뀌지
 * 않는 한(같은 방향이 이어지는 한) 그 사이에는 아무 매매도 하지 않는다. 판단 자체의
 * 빈도가 구조적으로 (연 거래일 수 / 21) ≈ 연 12회로 상한이 걸려 있으므로, 최악의 경우
 * (매달 판단이 바뀌어 매번 매수+매도)라도 연 24회 왕복을 넘을 수 없다 — 무필터 돌파
 * 대비 한 자릿수 배 이상 거래 횟수가 줄어들고, 그만큼 비용 드래그(횟수 × 왕복당 비용률)도
 * 구조적으로 줄어든다는 가설이다.
 *
 * <h2>로직</h2>
 * <ul>
 *   <li>21봉(약 월 1회)마다만 판단한다. 판단일이 아니면 아무 것도 하지 않는다 — 보유
 *       중이면 그대로 보유를 유지하고, 미보유면 그대로 미보유를 유지한다.</li>
 *   <li>판단일에는 "어제 종가 &gt; N봉 전 종가"(N=lookback)면 상승 추세로 보고,
 *       미보유 상태였다면 {@link TradeIntent#buyAtOpen(BigDecimal)}으로 진입한다(이미
 *       보유 중이면 그대로 들고 간다 — 재진입 불필요).</li>
 *   <li>그렇지 않으면(하락 추세) 하락 추세로 보고, 보유 중이었다면
 *       {@link TradeIntent#sellNextOpen()}으로 청산한다(이미 미보유면 아무 것도 안 함).</li>
 * </ul>
 * 손절은 쓰지 않는다 — 이 전략은 애초에 "짧은 손실을 빨리 끊는" 전략이 아니라 "추세
 * 판단을 월 단위로만 재검토하는" 전략이라, 장중 손절가를 걸어두는 것은 설계 취지와
 * 어긋난다(급락 방어는 다음 판단일의 청산으로 이뤄진다).
 *
 * <h2>룩어헤드 방지</h2>
 * 판단에 쓰는 "어제 종가"와 "N봉 전 종가"는 모두 오늘 이전에 이미 확정된 값이다. 종가
 * 이력({@link #closeHistory})에는 항상 "오늘 판단이 끝난 뒤" 오늘 종가를 추가하므로,
 * 판단 시점에는 결코 오늘 데이터가 섞여 들어가지 않는다.
 */
public final class TimeSeriesMomentumStrategy implements BacktestStrategy {

    /** 판단 주기(봉 수) — 약 한 달(월 1회 리밸런싱)에 해당하는 국내 주식시장 거래일수 근사치. */
    private static final int REBALANCE_INTERVAL = 21;

    private final int lookback; // N

    /** 최근 확정 종가를 최대 (lookback+1)개 보관 — 창의 맨 앞이 "N봉 전 종가", 맨 뒤가 "어제 종가". */
    private final Deque<BigDecimal> closeHistory = new ArrayDeque<>();

    /** 이번 전략 인스턴스가 처리한 캔들 수(0부터 시작) — 21봉마다 판단하기 위한 카운터. */
    private int dayIndex = -1;

    /**
     * @param lookback 모멘텀 비교에 쓰는 과거 시점(N봉 전). walk-forward 후보: {60, 120, 200}.
     */
    public TimeSeriesMomentumStrategy(int lookback) {
        if (lookback <= 0) {
            throw new IllegalArgumentException("lookback(N)은 1 이상이어야 함: " + lookback);
        }
        this.lookback = lookback;
    }

    @Override
    public List<TradeIntent> onCandle(Candle today, PortfolioState state) {
        dayIndex++;

        List<TradeIntent> intents = List.of(); // 기본값: 판단일이 아니면(또는 방향 유지면) 아무 것도 안 함

        boolean enoughHistory = closeHistory.size() >= lookback + 1;
        boolean judgmentDay = dayIndex > 0 && dayIndex % REBALANCE_INTERVAL == 0 && enoughHistory;

        if (judgmentDay) {
            BigDecimal yesterdayClose = closeHistory.peekLast();   // 어제 종가
            BigDecimal closeNBarsAgo = closeHistory.peekFirst();   // N봉 전 종가
            boolean uptrend = yesterdayClose.compareTo(closeNBarsAgo) > 0;

            if (uptrend && !state.hasPosition()) {
                intents = List.of(TradeIntent.buyAtOpen(today.open()));
            } else if (!uptrend && state.hasPosition()) {
                intents = List.of(TradeIntent.sellNextOpen());
            }
            // uptrend && 이미 보유 → 그대로 유지(재진입 불필요)
            // !uptrend && 미보유 → 그대로 유지(청산할 것이 없음)
        }

        // 오늘 종가는 오늘 판단이 끝난 뒤에만 이력에 반영한다(룩어헤드 방지).
        closeHistory.addLast(today.close());
        if (closeHistory.size() > lookback + 1) {
            closeHistory.removeFirst();
        }

        return intents;
    }
}
