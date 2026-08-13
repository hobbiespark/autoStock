package com.autostock.backtest;

import java.util.List;

/**
 * Walk-forward 실행 결과.
 *
 * <p>파라미터 타입을 {@code <P>}로 일반화했다 — 변동성 돌파류 전략은 k(Double) 하나를,
 * 시계열 모멘텀은 N(Integer)을 파라미터로 쓰는 등 전략마다 튜닝 파라미터의 타입이 다르기
 * 때문이다({@link WalkForwardRunner}의 일반화된 run 메서드 참고). "전략 재설계 실험"에서
 * 서로 다른 전략들을 같은 파이프라인으로 공정 비교하려면 이 결과 타입도 특정 파라미터
 * 타입(옛 Double)에 묶여 있으면 안 된다.
 *
 * @param selectedParams 각 창(window)에서 train 구간 최고 샤프로 선택된 파라미터 목록 (창 순서대로)
 * @param oosResult       모든 test(OOS, Out-Of-Sample) 구간의 일별 수익률을 이어붙여 계산한 성과.
 *                        {@link WalkForwardRunner} 클래스 설명 참고 — 최종 판단은 반드시 이 값으로 한다.
 * @param trials          DSR 계산에 사용된 총 시도 횟수 = 파라미터 후보 수 × 창(window) 수.
 *                        train 단계 파라미터 선택 편향 보정용 N이다 — {@code oosResult().dsrConfidence()}가
 *                        바로 이 trials로 계산되어 있으므로, 최종 게이트(전략 계열 간 비교) 판정에는
 *                        쓰지 말고 {@link PerformanceCalculator#deflatedSharpeAcrossFamilies}를 따로
 *                        호출해서 나온 값을 써야 한다({@link BacktestResult#dsrConfidence()} 참고).
 */
public record WalkForwardResult<P>(
        List<P> selectedParams,
        BacktestResult oosResult,
        int trials
) {
}
