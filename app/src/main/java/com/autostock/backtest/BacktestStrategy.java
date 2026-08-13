package com.autostock.backtest;

import java.math.BigDecimal;
import java.util.List;

/**
 * 백테스트가 하루치 캔들을 넘겨줄 때마다 "어떤 조건부 주문을 걸어둘지"를 결정하는 전략 함수.
 *
 * <p>전략·리스크 코드를 라이브와 그대로 재사용한다는 원칙(PLAN "백테스트=라이브 동형") 아래,
 * 이 인터페이스는 실제 {@code strategy} 모듈의 신호 로직을 감싸는 아주 얇은 어댑터로 쓰인다 —
 * 백테스트 전용 로직을 새로 만들지 말고, 기존 전략이 신호를 낼 때 쓰는 판단 함수를
 * 여기 연결하기만 하면 된다.
 *
 * <h2>중요 — {@code today}는 "오늘 시가까지만" 알려진 상태로 취급할 것</h2>
 * 러너({@link BacktestRunner})는 구현상 당일 캔들 전체({@link Candle#high()}, {@link Candle#low()},
 * {@link Candle#close()}까지 전부 채워진 값)를 이 메서드에 넘긴다. 하지만 이것은 순전히
 * 구현 편의(캔들을 미리 잘라서 넘기지 않기 위함)일 뿐이며, <b>규약상 전략은 오늘 캔들에서
 * {@link Candle#open()}만 읽어야 한다.</b> 오늘의 고가/저가/종가를 판단에 쓰면 아직 일어나지
 * 않은 미래를 미리 아는 셈이 되는 룩어헤드 버그다. (반대로 "어제까지"의 캔들은 이미 확정된
 * 과거이므로 전략이 내부에 기억해뒀다가 자유롭게 써도 된다 — {@code VolatilityBreakoutStrategy}가
 * 전일 고가/저가를 저장해두는 방식 참고.)
 *
 * <p>실제 체결 여부(오늘 고가/저가가 트리거 가격에 닿았는지)는 전략이 아니라 러너가
 * 판단한다 — 전략은 "무엇을 걸어둘지"({@link TradeIntent} 목록)만 정하면 된다.
 */
@FunctionalInterface
public interface BacktestStrategy {

    /**
     * @param today 오늘자 캔들. 규약상 {@link Candle#open()}만 판단에 사용할 것(위 클래스 설명 참고).
     * @param state 오늘 캔들 처리 시점(오늘 개장 직전)의 포트폴리오 상태(보유 수량/평단/현금)
     * @return 오늘 걸어둘 조건부 주문 목록. 아무 것도 안 하면 빈 리스트({@link List#of()}).
     */
    List<TradeIntent> onCandle(Candle today, PortfolioState state);

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
