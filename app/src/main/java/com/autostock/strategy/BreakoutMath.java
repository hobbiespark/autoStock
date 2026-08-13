package com.autostock.strategy;

import java.math.BigDecimal;

/**
 * 변동성 돌파(Volatility Breakout) 전략의 목표가(target price) 계산 — 순수 함수 하나짜리 클래스.
 *
 * <p>왜 별도 클래스로 뽑아냈는가? PLAN의 "백테스트=라이브 동형" 원칙 때문이다. 목표가 계산식이
 * 백테스트 쪽과 라이브 전략 쪽에 각자 따로 구현되면, 반올림·부호 실수 같은 미세한 차이가
 * 생겼을 때 "백테스트에서는 잘 되던 전략이 실전에서는 다르게 동작하는" 사고가 난다. 그래서
 * 이 계산은 이 클래스 하나에만 존재하고, {@code backtest} 모듈의 VolatilityBreakoutStrategy와
 * 라이브 매매 시 {@code strategy} 모듈이 쓰는 판단 로직이 똑같이 이 메서드를 호출해야 한다.
 *
 * <h2>룩어헤드(lookahead) 금지</h2>
 * {@link #target(BigDecimal, BigDecimal, BigDecimal, double)} 계산에는 "오늘 시가"와
 * "전일(어제)" 데이터만 쓴다. 오늘 아직 지나지 않은 시간대의 고가/저가/종가를 넣으면
 * 미래 정보를 미리 아는 셈이 되어, 백테스트에서는 잘 나오지만 실전에서는 재현 불가능한
 * 가짜 성과가 나온다.
 */
public final class BreakoutMath {

    private BreakoutMath() {
        // 정적 유틸리티 — 인스턴스화 금지
    }

    /**
     * 변동성 돌파 목표가 = 오늘 시가 + k × (전일 고가 − 전일 저가)
     *
     * <p>쉬운 설명: 어제 하루 동안 가격이 얼마나 크게 흔들렸는지(고가-저가, 변동폭)를 보고,
     * 오늘도 그 흔들림의 k배만큼 오늘 시가보다 더 오르면 "추세가 시작됐다"고 보고 매수한다.
     *
     * @param todayOpen 오늘 시가 (룩어헤드 금지: 오늘 데이터 중 이 값만 사용 가능하다)
     * @param prevHigh  전일 고가
     * @param prevLow   전일 저가
     * @param k         변동성 계수 (통상 0~1 사이. 클수록 더 강한 돌파가 있어야 진입 — 보수적)
     * @return 목표가 — 오늘 장중 이 가격 이상에 도달하면 돌파로 보고 매수 신호를 낸다
     */
    public static BigDecimal target(BigDecimal todayOpen, BigDecimal prevHigh, BigDecimal prevLow, double k) {
        BigDecimal range = prevHigh.subtract(prevLow);
        return todayOpen.add(range.multiply(BigDecimal.valueOf(k)));
    }
}
