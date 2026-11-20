package com.autostock.risk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * 전략별 "1주 상한" — {@link RiskGate}가 최소 유의미 수량을 맞출 때 쓰는 종목 원가 상한
 * ({@link TradingCapitalProperties#meaningfulOrderCap})을 전략이 신호를 내기 전에 미리 계산한다(VALUNIVO 917, 데브 11-20
 * "예산에 맞춰 거르기"). 1주 값이 이 상한을 넘는 종목은 관문이 언제나 {@code 무의미 거래}로 거부하므로, 전략이 그 종목을
 * 후보에서 빼 다음 후보에 자리를 주거나 보류로 한 번만 기록한다. 판정의 마지막은 여전히 {@link RiskGate}다.
 *
 * <p>종목 예산 = 사이징 기준(min(계좌 평가액, 운용 자금)) × 슬리브 비율 ÷ 최대 종목 수(신호 비중 1.0 기준 — 세 전략 모두 1.0을 낸다).
 * 최소 유의미 수량 규칙이 꺼져 있거나, 슬리브가 없거나, 평가액을 모르면 상한을 모른다(빈 값) — 그때 전략은 거르지 않는다.
 */
@Component
public class OneShareCap {

    private final MultiStrategyRiskProperties sleeves;
    private final TradingCapitalProperties capital;
    private final EquitySource equitySource;

    public OneShareCap(MultiStrategyRiskProperties sleeves, TradingCapitalProperties capital, EquitySource equitySource) {
        this.sleeves = sleeves;
        this.capital = capital;
        this.equitySource = equitySource;
    }

    /** 이 전략의 1주 상한(원). 모르면 빈 값. */
    public Optional<BigDecimal> capFor(String strategyId) {
        if (!capital.meaningfulRuleOn()) {
            return Optional.empty();
        }
        Optional<MultiStrategyRiskProperties.Sleeve> sleeve = sleeves.sleeveOf(strategyId);
        if (sleeve.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal base = capital.sizingBase(equitySource.equity());
        if (base == null || base.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal perPosition = base.multiply(BigDecimal.valueOf(sleeve.get().fraction()))
                .divide(BigDecimal.valueOf(sleeve.get().maxPositions()), 10, RoundingMode.HALF_UP);
        return Optional.of(capital.meaningfulOrderCap(perPosition));
    }

    /** 1주 값(지정가 기준)이 이 전략의 상한을 넘나. 상한을 모르면 false(거르지 않는다). */
    public boolean exceeds(String strategyId, BigDecimal oneSharePrice) {
        if (oneSharePrice == null || oneSharePrice.signum() <= 0) {
            return false;
        }
        return capFor(strategyId).map(cap -> oneSharePrice.compareTo(cap) > 0).orElse(false);
    }
}
