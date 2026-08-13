package com.autostock.backtest;

import com.autostock.common.event.Side;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 백테스트가 하루치 캔들을 넘겨줄 때마다 "사고/팔고/아무것도 안 함"을 결정하는 전략 함수.
 *
 * <p>전략·리스크 코드를 라이브와 그대로 재사용한다는 원칙(PLAN "백테스트=라이브 동형") 아래,
 * 이 인터페이스는 실제 {@code strategy} 모듈의 신호 로직을 감싸는 아주 얇은 어댑터로 쓰인다 —
 * 백테스트 전용 로직을 새로 만들지 말고, 기존 전략이 신호를 낼 때 쓰는 판단 함수를
 * 여기 연결하기만 하면 된다.
 *
 * <p>중요: 이 메서드에 넘어오는 {@link Candle}은 "그날 하루가 끝난 뒤의 확정된 캔들"이다.
 * 즉 그날의 시가/고가/저가/종가를 모두 알고 있는 상태에서 판단하지만, 실제 체결은
 * 다음날 시가에 이뤄진다({@link BacktestRunner} 참고) — 여기서 반환하는 값은 "판단"일 뿐
 * "체결"이 아니다.
 */
@FunctionalInterface
public interface BacktestStrategy {

    /**
     * @param candle 오늘자 확정 캔들
     * @param state  오늘 캔들 처리 시점의 포트폴리오 상태(보유 수량/평단/현금)
     * @return 매수/매도 의견. 포지션 유지(아무 것도 안 함)면 {@link Optional#empty()}.
     */
    Optional<Side> onCandle(Candle candle, PortfolioState state);

    /**
     * 전략 판단 시점의 포트폴리오 상태 — RiskGate의 사이징 판단과 마찬가지로
     * "이미 보유 중인가"를 전략이 알아야 물타기/중복매수 여부를 스스로 판단할 수 있다.
     *
     * @param positionQuantity 보유 수량 (0이면 미보유)
     * @param avgPrice         평균 매수 단가 (미보유면 0)
     * @param cash             가용 현금
     */
    record PortfolioState(long positionQuantity, BigDecimal avgPrice, BigDecimal cash) {
        public boolean hasPosition() {
            return positionQuantity > 0;
        }
    }
}
