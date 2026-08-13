package com.autostock.backtest;

import java.util.List;

/**
 * Walk-forward 실행 결과.
 *
 * @param selectedParams 각 창(window)에서 train 구간 최고 샤프로 선택된 k값 목록 (창 순서대로)
 * @param oosResult       모든 test(OOS, Out-Of-Sample) 구간의 일별 수익률을 이어붙여 계산한 성과.
 *                        {@link WalkForwardRunner} 클래스 설명 참고 — 최종 판단은 반드시 이 값으로 한다.
 * @param trials          DSR 계산에 사용된 총 시도 횟수 = 파라미터 후보 수 × 창(window) 수
 */
public record WalkForwardResult(
        List<Double> selectedParams,
        BacktestResult oosResult,
        int trials
) {
}
