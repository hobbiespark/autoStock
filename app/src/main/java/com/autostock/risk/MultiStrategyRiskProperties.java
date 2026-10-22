package com.autostock.risk;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 다전략 운용 한도(2026-10-16, aiDoc/multi-strategy-paper-2026-10-16.md 1절) — {@code risk.multi-strategy.*}.
 *
 * <p><b>슬리브</b>: 전략마다 계좌의 정해진 비율만 쓴다. 슬리브가 있는 전략의 매수는
 * <ul>
 *   <li>1종목 예산 = 계좌 평가액 × {@link Sleeve#fraction} ÷ {@link Sleeve#maxPositions} × 신호 비중(confidence)</li>
 *   <li>보유 중복·동시 보유·미체결 매수는 <b>그 전략 장부</b>(portfolio.StrategyBook)로 본다 — 같은 종목을 다른 전략이
 *       들고 있어도 살 수 있다</li>
 * </ul>
 * 매도는 그 전략이 든 수량만 판다. 슬리브가 없는 전략 ID(대시보드 수동 주문 등)는 예전 규칙 그대로다.
 *
 * <p><b>손실 한도 킬스위치 중 매도</b>({@link #allowReducingSellOnLossLimit}, D-20 (b)): 일 손실 한도로 켜진 킬스위치라면
 * 보유 수량 이내 매도는 통과시킨다. 시세 단절·인증 실패·수동 정지 등 다른 사유면 지금처럼 전부 막는다. 기본 꺼짐 —
 * 모의 프로필에서만 켠다(단타·종가 전략의 정해진 시각 청산이 막히지 않게).
 *
 * @param allowReducingSellOnLossLimit 일 손실 한도 킬스위치 중 보유 이내 매도 허용(D-20 b)
 * @param sleeves                      전략별 슬리브. 비어 있으면 모든 전략이 예전 규칙
 */
@ConfigurationProperties(prefix = "risk.multi-strategy")
public record MultiStrategyRiskProperties(
        @DefaultValue("false") boolean allowReducingSellOnLossLimit,
        List<Sleeve> sleeves
) {

    public MultiStrategyRiskProperties {
        sleeves = sleeves == null ? List.of() : List.copyOf(sleeves);
        double total = 0;
        for (Sleeve s : sleeves) {
            total += s.fraction();
        }
        if (total > 1.0 + 1e-9) {
            throw new IllegalArgumentException("슬리브 합이 100%를 넘는다: " + total);
        }
    }

    /** 슬리브 없음(예전 규칙) — 시험·기본값용. */
    public static MultiStrategyRiskProperties none() {
        return new MultiStrategyRiskProperties(false, List.of());
    }

    /** 전략 ID의 슬리브(대소문자 무시 — ClientOrderId는 대문자로 바꾼다). */
    public Optional<Sleeve> sleeveOf(String strategyId) {
        if (strategyId == null) {
            return Optional.empty();
        }
        String key = strategyId.toUpperCase(Locale.ROOT);
        return sleeves.stream().filter(s -> s.strategy().toUpperCase(Locale.ROOT).equals(key)).findFirst();
    }

    /**
     * @param strategy     전략 ID(예: C3-MOMENTUM, VB-DAY, ON-CLOSE, TA-H1)
     * @param fraction     계좌 대비 슬리브(0 초과 1 이하)
     * @param maxPositions 이 전략의 동시 보유 상한(1 이상)
     */
    public record Sleeve(String strategy, double fraction, int maxPositions) {
        public Sleeve {
            if (strategy == null || strategy.isBlank()) {
                throw new IllegalArgumentException("슬리브 전략 ID가 비었다");
            }
            if (!(fraction > 0.0 && fraction <= 1.0)) {
                throw new IllegalArgumentException("슬리브 비율은 (0, 1]: " + strategy + "=" + fraction);
            }
            if (maxPositions < 1) {
                throw new IllegalArgumentException("동시 보유 상한은 1 이상: " + strategy + "=" + maxPositions);
            }
        }

        /** 1종목 예산 비율(계좌 대비, 신호 비중을 곱하기 전). */
        public double perPositionFraction() {
            return fraction / maxPositions;
        }
    }
}
