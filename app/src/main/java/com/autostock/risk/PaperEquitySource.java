package com.autostock.risk;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 모의(SIM) 모드 계좌 평가액 — {@link RiskProperties#paperEquity()} 설정값을 그대로 반환한다.
 *
 * <p>{@code execution.mode=SIM}이거나 아예 설정되지 않았을 때(matchIfMissing=true) 등록된다.
 * 프로젝트 기본값 자체가 SIM(application.yml)이므로, execution.mode를 명시하지 않는 테스트나
 * 최소 컨텍스트에서도 이 빈이 안전하게 뜨도록 matchIfMissing을 true로 뒀다 — "앱키 없이도
 * SIM으로 전체 루프 검증 가능"이라는 기존 원칙(ExecutionService Javadoc)과 동일한 취지다.
 */
@Component
@ConditionalOnProperty(prefix = "execution", name = "mode", havingValue = "SIM", matchIfMissing = true)
public class PaperEquitySource implements EquitySource {

    private final RiskProperties properties;

    public PaperEquitySource(RiskProperties properties) {
        this.properties = properties;
    }

    @Override
    public BigDecimal equity() {
        return BigDecimal.valueOf(properties.paperEquity());
    }
}
