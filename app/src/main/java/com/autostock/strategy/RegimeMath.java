package com.autostock.strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 시장 국면(ON/OFF) 판정 — SMA200 계산의 순수 함수부.
 *
 * <p>원래 {@code backtest.MarketRegime} 안에 있던 계산이었으나, {@link MomentumMath}와
 * 같은 "백테스트=라이브 동형" 원칙에 따라 이 클래스로 옮겼다. {@code backtest.MarketRegime}은
 * 이제 지수 캔들 전체를 훑으며 날짜별로 이 메서드를 호출해 맵을 만드는 "사전 계산" 역할만
 * 하고, 실제 판정식(SMA200과 전일 종가 비교)은 여기 하나에만 있다. 라이브 전략
 * ({@code strategy.C3LiveStrategy})은 하루치 판단만 필요하므로 맵을 만들 필요 없이 이
 * 메서드를 직접 호출한다.
 *
 * <h2>판정 규칙 — 룩어헤드 방지</h2>
 * 판정에는 <b>전일까지</b>의 종가만 쓴다: 전일 종가와, 전일까지의 200일 단순이동평균
 * (SMA200)을 비교한다. 전일 종가가 SMA200을 초과하면 ON(상승 국면), 그렇지 않으면(이하)
 * OFF(하락/횡보 국면)로 본다. 오늘 캔들의 시가/고가/저가/종가는 전혀 쓰지 않는다.
 *
 * <p>SMA200을 계산할 만큼 과거 데이터(200봉)가 아직 쌓이지 않았으면 "국면을 판단할 근거가
 * 없다"는 뜻이므로 보수적으로 막지 않고 ON(중립 — 필터가 개입하지 않음)으로 처리한다.
 */
public final class RegimeMath {

    /** SMA 창 길이(봉 수) — 약 1년(거래일 기준) 추세를 보는 장기 이동평균. */
    public static final int SMA_WINDOW = 200;

    private RegimeMath() {
        // 정적 유틸리티 — 인스턴스화 금지
    }

    /**
     * @param closesUpToYesterday 판정하려는 날짜의 "전일까지" 종가 이력, 시간순(오름차순).
     *                            마지막 원소가 전일 종가다. SMA200 계산에는 이 목록의
     *                            마지막 {@link #SMA_WINDOW}개(전일 종가 포함)를 쓴다.
     * @return true=ON(상승 국면), false=OFF(하락/횡보 국면). 데이터 부족(200개 미만)이면
     *         판단 근거 부족으로 true(중립) 반환.
     */
    public static boolean isOn(List<BigDecimal> closesUpToYesterday) {
        if (closesUpToYesterday == null || closesUpToYesterday.size() < SMA_WINDOW) {
            return true; // 판단 근거(200봉) 부족 — 중립 ON 처리
        }
        int n = closesUpToYesterday.size();
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = n - SMA_WINDOW; i < n; i++) {
            sum = sum.add(closesUpToYesterday.get(i));
        }
        BigDecimal sma200 = sum.divide(BigDecimal.valueOf(SMA_WINDOW), 10, RoundingMode.HALF_UP);
        BigDecimal yesterdayClose = closesUpToYesterday.get(n - 1);
        return yesterdayClose.compareTo(sma200) > 0; // 전일 종가 > SMA200 → 상승 국면(ON)
    }
}
