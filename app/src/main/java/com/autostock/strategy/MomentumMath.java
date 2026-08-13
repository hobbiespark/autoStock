package com.autostock.strategy;

import java.math.BigDecimal;
import java.util.List;

/**
 * 시계열 모멘텀(Time-Series Momentum) 판단 — "어제 종가가 N봉 전 종가보다 높은가"만 보는
 * 순수 함수 하나짜리 클래스.
 *
 * <p>{@link BreakoutMath}와 같은 이유로 별도 클래스로 뽑았다 — 백테스트의
 * {@code backtest.TimeSeriesMomentumStrategy}와 라이브의 {@code strategy.C3LiveStrategy}가
 * "보유할지 말지"를 판단하는 계산식이 서로 다른 곳에 중복 구현되면, 아주 사소한 부등호
 * 방향이나 인덱스 실수 하나로 백테스트와 실전이 다르게 동작하는 사고로 이어진다. 그래서
 * 이 판단은 이 클래스 하나에만 존재하고, 두 모듈이 똑같이 이 메서드를 호출한다.
 *
 * <h2>룩어헤드 금지</h2>
 * 판단에 쓰는 두 값(어제 종가, N봉 전 종가)은 모두 "오늘"이 시작되기 전에 이미 확정된
 * 과거 값이어야 한다. 호출자는 오늘자 캔들의 시가/고가/저가/종가를 이 메서드에 절대
 * 넘기지 말아야 한다.
 */
public final class MomentumMath {

    private MomentumMath() {
        // 정적 유틸리티 — 인스턴스화 금지
    }

    /**
     * 상승 추세(보유 유지/신규 진입 조건)인지 판단한다: 어제 종가 &gt; N봉 전 종가.
     *
     * @param closes    시간순(오름차순)으로 정렬된 확정 종가 목록. 목록의 마지막 원소가
     *                  "어제 종가", 마지막에서 (lookbackN+1)번째 원소가 "N봉 전 종가"다.
     *                  최소 (lookbackN + 1)개가 있어야 한다.
     * @param lookbackN 비교 기준이 되는 과거 시점(N봉 전). 1 이상이어야 한다.
     * @return true면 상승 추세(보유/진입), false면 하락 추세(청산/미진입)
     */
    public static boolean shouldHold(List<BigDecimal> closes, int lookbackN) {
        if (lookbackN <= 0) {
            throw new IllegalArgumentException("lookbackN(N)은 1 이상이어야 함: " + lookbackN);
        }
        if (closes == null || closes.size() < lookbackN + 1) {
            throw new IllegalArgumentException(
                    "종가가 최소 " + (lookbackN + 1) + "개 필요함(어제 + N봉 전): 실제 "
                            + (closes == null ? 0 : closes.size()) + "개");
        }
        BigDecimal yesterdayClose = closes.get(closes.size() - 1);            // 어제 종가
        BigDecimal closeNBarsAgo = closes.get(closes.size() - 1 - lookbackN); // N봉 전 종가
        return yesterdayClose.compareTo(closeNBarsAgo) > 0;
    }
}
