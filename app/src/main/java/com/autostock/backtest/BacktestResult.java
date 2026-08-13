package com.autostock.backtest;

import java.math.BigDecimal;
import java.util.List;

/**
 * 백테스트 한 번 실행 결과 요약.
 *
 * @param dailyReturns  일별 수익률 시계열 (전일 대비 평가자산 변화율). 인덱스 0은 항상
 *                       0.0 — 첫날은 아직 포지션 진입 전이므로 변화가 없다({@link BacktestRunner}).
 * @param finalEquity   마지막 날 종가 기준 평가자산 (현금 + 보유 포지션 시가)
 * @param totalReturn   총수익률 = (finalEquity / initialCapital) − 1
 * @param cagr          연환산복리수익률(Compound Annual Growth Rate). 거래일수를 252일 기준으로 연환산.
 * @param mdd           최대낙폭(Maximum Drawdown). 자산곡선의 고점 대비 최대 하락폭(0~1).
 * @param sharpe        연율화 샤프비율 (무위험수익률 0 가정): mean(일수익률)/std(일수익률) × √252
 * @param tradeCount    체결(매수+매도) 횟수
 * @param dsrConfidence DSR(Deflated Sharpe Ratio) — "이 샤프비율이 여러 번 시도 중 우연히
 *                       나온 최댓값이 아니라 진짜 실력일 확률"을 0~1 신뢰수준으로 표현한 값.
 */
public record BacktestResult(
        List<Double> dailyReturns,
        BigDecimal finalEquity,
        double totalReturn,
        double cagr,
        double mdd,
        double sharpe,
        int tradeCount,
        double dsrConfidence
) {
}
