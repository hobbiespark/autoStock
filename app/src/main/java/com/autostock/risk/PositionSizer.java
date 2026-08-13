package com.autostock.risk;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 포지션 사이징 — "한 종목에 얼마나 걸까?"의 답.
 *
 * <p>현재 방식: <b>고정비율(fixed fractional)</b>.
 * <pre>
 *   수량 = ⌊ (계좌 평가액 × 종목당 최대 비중) ÷ 주가 ⌋
 *
 *   예) 계좌 1,000만원, 비중 10%, 삼성전자 70,000원
 *       → 100만원 ÷ 70,000원 = 14.28... → 14주 (내림)
 * </pre>
 *
 * <p>왜 켈리(Kelly) 공식이 아니라 고정비율인가?
 * 켈리는 "내 전략의 승률과 손익비"를 정확히 알아야 최적이 되는데,
 * 거래 이력이 없는 초기에는 그 추정치가 부정확해서 오히려 위험하다.
 * 연구 결과도 거래 50~100건 전에는 보수적 고정비율이 낫다고 본다. (PLAN 2절 (3))
 * 이력이 쌓이면 이 클래스를 fractional Kelly 구현체로 교체한다 —
 * 교체가 쉽도록 사이징 로직을 이 한 클래스에 격리해 두었다.
 */
@Component
public class PositionSizer {

    private final RiskProperties properties;

    public PositionSizer(RiskProperties properties) {
        this.properties = properties;
    }

    /**
     * 매수 가능 수량을 계산한다.
     *
     * <p><b>confidence 반영(2026-08-13)</b>: 예산 = 계좌 평가액 × 종목당 최대 비중 ×
     * confidence. C3 같은 사이징 전략(변동성 타게팅)은 {@code Signal.confidence()}에
     * "투입 비중"을 실어 보내는데, 그 값을 여기서 예산에 곱해 실제 매수 수량을 줄인다
     * (예: confidence=0.5면 원래 살 수량의 절반만 산다). confidence=1.0(기본 시그널)이면
     * 기존 계산과 완전히 동일한 수치가 나온다 — 기존 호출부(테스트 포함)는 무수정으로
     * 그대로 통과해야 한다. confidence 값 자체의 유효성 검사(0~1 범위 클램프)는
     * {@link RiskGate}가 호출 전에 이미 끝낸 책임이므로, 여기서는 그대로 곱하기만 한다.
     *
     * @param equity     계좌 평가액 (KRW)
     * @param price      기준가 (보통 시그널 시점의 현재가)
     * @param confidence 투입 비중(0 초과 1 이하로 이미 클램프된 값). 1.0이면 종목당 한도
     *                   전액을 그대로 쓴다(기존 동작과 동일).
     * @return 매수 수량. 0이면 "이 가격에 1주도 살 수 없음" — 주문하지 않는다.
     */
    public long sizeBuy(BigDecimal equity, BigDecimal price, double confidence) {
        // 방어적 검사: 가격이나 평가액이 0/음수/null이면 계산 자체가 무의미
        if (price == null || price.signum() <= 0 || equity == null || equity.signum() <= 0) {
            return 0;
        }
        BigDecimal budget = equity
                .multiply(BigDecimal.valueOf(properties.maxPositionPctPerSymbol()))
                .multiply(BigDecimal.valueOf(confidence));
        // RoundingMode.DOWN(내림)인 이유: 올림하면 예산을 초과해서 사게 된다
        return budget.divide(price, 0, RoundingMode.DOWN).longValue();
    }
}
