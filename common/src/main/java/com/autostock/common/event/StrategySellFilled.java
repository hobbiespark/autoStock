package com.autostock.common.event;

import com.autostock.common.util.StockCode;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 전략 장부에 들어간 매도 체결 한 건과 그 결과(2026-10-23) — 매도 알림에 평단 대비 수익률을 싣기 위한 이벤트.
 *
 * <p>배경: 사용자 10/23 "매도시 수익율 포함해서 메시지 필요". 체결({@link Fill})에는 평단이 없고, 체결 알림 리스너가 장부를
 * 직접 읽으면 장부 갱신과의 순서가 정해져 있지 않아 이미 줄어든(또는 지워진) 장부를 볼 수 있다. 그래서 portfolio.StrategyBook이
 * 매도 체결을 장부에 반영하는 그 자리에서, 반영 전 평단과 실현손익을 담아 이 이벤트를 낸다. 체결 주문번호에서 전략 ID를 읽은
 * 매도 체결마다 한 번 나온다(평단을 모르면 {@code avgPrice}·{@code realizedPnl}이 null).
 *
 * @param strategyId        전략 ID(주문번호에서 읽은 값, 대문자)
 * @param symbol            종목
 * @param quantity          이번 체결 수량
 * @param fillPrice         이번 체결 단가(부분 체결 증분 단가는 소수일 수 있다)
 * @param avgPrice          반영 전 이 전략의 평단 — 모르면 null(이 전략 보유가 없던 매도 포함)
 * @param realizedPnl       이번 체결의 실현손익(수수료·거래세 추정 차감) — 평단을 모르면 null
 * @param remainingQuantity 반영 뒤 이 전략에 남은 수량
 * @param exit              이 체결로 청산이 끝났으면 청산 전체 요약, 아니면 null
 * @param timestamp         체결 시각
 */
public record StrategySellFilled(
        String strategyId,
        StockCode symbol,
        long quantity,
        BigDecimal fillPrice,
        BigDecimal avgPrice,
        BigDecimal realizedPnl,
        long remainingQuantity,
        Exit exit,
        Instant timestamp
) {

    /**
     * 청산 요약 — 같은 (전략, 종목) 매도 체결을 청산이 끝날 때까지 모은 값. 앱이 청산 도중 재기동하면 그 뒤 체결만 센다.
     *
     * @param quantity     청산 수량 합
     * @param avgPrice     평단(매도 전)
     * @param avgSellPrice 평균 매도가(매도 금액 ÷ 수량)
     * @param proceeds     매도 금액 합(비용 전)
     * @param realizedPnl  실현손익 합(수수료·거래세 추정 차감)
     * @param fills        체결 건수
     */
    public record Exit(long quantity, BigDecimal avgPrice, BigDecimal avgSellPrice, BigDecimal proceeds,
                       BigDecimal realizedPnl, int fills) {
    }
}
