package com.autostock.risk;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

/**
 * 운용 자금 한도(2026-10-21, 사용자 결정 — "계좌 금액을 다 쓰지 말고 200만~2,000만 원 안에서만 거래") —
 * {@code risk.capital.*}. aiDoc/multi-strategy-paper-2026-10-16.md 8절.
 *
 * <ul>
 *   <li><b>사이징 기준</b>: 슬리브·종목당 예산을 계좌 평가액 대신 min(계좌 평가액, 운용 자금)으로 센다
 *       ({@link #sizingBase}).</li>
 *   <li><b>전체 매수 원가 상한</b>: 계좌 보유 매수 원가 + 미체결 매수 남은 금액 + 이번 주문 금액이 운용 자금을 넘으면
 *       {@link RiskGate}가 수량을 줄이고, 한 주도 못 사면 거부한다. 수동 주문도 같다.</li>
 *   <li><b>1주 허용</b>({@link #allowsOneShare}): 슬리브 예산으로 0주가 나와도 1주 값이 예산의
 *       {@link #oneShareMaxBudgetMultiple}배 이하면 1주를 산다. 2,000만 원이면 차트 기법 종목당 16만 원이라
 *       고가주는 한 주도 못 산다(사용자 선택 10/21 — 모의 실측 거래 수 확보). 전체 상한은 그대로 지킨다.</li>
 *   <li><b>일 손실 한도 기준</b>: min(계좌 평가액, 운용 자금)과 오늘 가장 많이 들고 있던 금액(기준가 기준) 중 큰 값 —
 *       {@link DailyPnlTracker}. 운용 자금보다 큰 예전 보유를 정리하는 날 한도가 너무 작아지지 않게.</li>
 * </ul>
 *
 * <p>0이면 끈다(예전처럼 계좌 전체). 켤 때는 {@link #MIN_KRW}~{@link #MAX_KRW}만 받는다 — 사용자가 정한 범위 밖 값
 * (자릿수 실수 등)으로는 앱이 뜨지 않는다.
 *
 * @param limitKrw                  운용 자금(원). 0이면 끔
 * @param oneShareMaxBudgetMultiple 예산으로 0주일 때 1주를 허용하는 배수(1주 값 ≤ 예산 × 이 값). 0이면 끔
 */
@ConfigurationProperties(prefix = "risk.capital")
public record TradingCapitalProperties(
        @DefaultValue("0") long limitKrw,
        @DefaultValue("2.0") double oneShareMaxBudgetMultiple
) {

    /** 운용 자금 하한(원) — 사용자 범위 200만 원. */
    public static final long MIN_KRW = 2_000_000L;
    /** 운용 자금 상한(원) — 사용자 범위 2,000만 원. */
    public static final long MAX_KRW = 20_000_000L;

    public TradingCapitalProperties {
        if (limitKrw != 0 && (limitKrw < MIN_KRW || limitKrw > MAX_KRW)) {
            throw new IllegalArgumentException("risk.capital.limit-krw는 0(끔) 또는 %d~%d원이어야 한다: %d"
                    .formatted(MIN_KRW, MAX_KRW, limitKrw));
        }
        if (oneShareMaxBudgetMultiple < 0 || Double.isNaN(oneShareMaxBudgetMultiple)) {
            throw new IllegalArgumentException("risk.capital.one-share-max-budget-multiple은 0 이상: "
                    + oneShareMaxBudgetMultiple);
        }
    }

    /** 운용 자금 없음(계좌 전체, 1주 허용 없음) — 시험·예전 생성자용. */
    public static TradingCapitalProperties none() {
        return new TradingCapitalProperties(0, 0);
    }

    /** 운용 자금이 켜져 있나. */
    public boolean limited() {
        return limitKrw > 0;
    }

    /** 운용 자금(원). 꺼져 있으면 0. */
    public BigDecimal limit() {
        return BigDecimal.valueOf(limitKrw);
    }

    /**
     * 사이징 기준 금액 — 운용 자금이 켜져 있으면 min(계좌 평가액, 운용 자금), 아니면 계좌 평가액 그대로.
     * 계좌 평가액이 없거나 0 이하면 그대로 돌려준다(사이징이 0주로 거부한다 — 예전과 같다).
     */
    public BigDecimal sizingBase(BigDecimal accountEquity) {
        if (!limited() || accountEquity == null || accountEquity.signum() <= 0) {
            return accountEquity;
        }
        return accountEquity.min(limit());
    }

    /** 예산으로 0주일 때 1주를 사도 되나 — 1주 값이 예산 × {@link #oneShareMaxBudgetMultiple} 이하. */
    public boolean allowsOneShare(BigDecimal budget, BigDecimal price) {
        if (oneShareMaxBudgetMultiple <= 0 || budget == null || price == null || budget.signum() <= 0
                || price.signum() <= 0) {
            return false;
        }
        return price.compareTo(budget.multiply(BigDecimal.valueOf(oneShareMaxBudgetMultiple))) <= 0;
    }
}
