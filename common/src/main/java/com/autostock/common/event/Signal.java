package com.autostock.common.event;

import com.autostock.common.util.Price;
import com.autostock.common.util.StockCode;

import java.time.Instant;

/**
 * 전략이 발행하는 매매 시그널. (스키마 v4 — v2 fixedQuantity, v3 benchmarkPrice, v4 tranche 추가)
 * 시그널은 주문이 아니다 — risk 모듈 게이트 통과 후에만 OrderRequest가 된다.
 *
 * <p><b>fixedQuantity (2026-09-11 추가, 운영 1일차 ⑦)</b>: 대시보드 수동 시그널처럼
 * "사람이 수량을 직접 지정"하는 경우에만 채운다. null이면(전략 발행 시그널의 기본)
 * RiskGate가 기존대로 자동 사이징한다. 값이 있어도 RiskGate의 상한(예산 캡·보유량 캡)을
 * 넘을 수 없다 — 수동 지정은 "자동 산정치 이하로 줄이는" 용도이지 리스크 한도를 우회하는
 * 수단이 아니다. 과거 이벤트(v1, 필드 없음)는 역직렬화 시 null로 채워져 하위호환된다.
 *
 * <p><b>symbol</b>: {@link StockCode} 값 객체다. JSON에서는 기존과 같은 문자열로
 * 직렬화되므로(JacksonConfig) event_store 형식은 바뀌지 않는다.
 *
 * <p><b>refPrice</b>: {@link Price} 값 객체이고 <b>null일 수 있다</b>(기준가 없음 — 예: 평단 미상 포지션, 호가 조회
 * 실패). 매수는 사이징에서, 매도는 지정가 산출에서 RiskGate가 거부한다. JSON은 기존과 같은 숫자다.
 *
 * <p><b>benchmarkPrice (2026-10-22 추가, 스키마 v3)</b>: 슬리피지를 잴 기준가 — 전략이 "이 값에 사고팔 생각이었다"는 가격
 * (차트 기법은 백테스트 체결가인 오늘 시가·돌파선·손절선, 단타는 목표가·청산 계기 체결가, 종가→익일은 15:15 현재가·아침 매수호가).
 * refPrice는 체결을 위해 여유를 둔 지정가(×1.05·×0.98 등)라 슬리피지 기준으로 쓰면 여유폭만 잰다(10/21·10/22 로그 −200~−550bps).
 * null이면 지정가로 잰다(C3·수동 주문 — 지정가 자체가 최우선 호가다). 과거 이벤트는 null로 역직렬화된다.
 *
 * <p><b>tranche (2026-10-30 추가, 스키마 v4)</b>: 3분할 매수의 차수(1~3). null이면 분할 없는 예전 매수다. 1차는 새 보유
 * (종목 예산의 1/3), 2·3차는 그 전략이 이미 든 종목에 더 사는 매수다 — RiskGate가 "이미 보유 중" 차단 대신 차수 규칙으로
 * 본다(aiDoc/split-buy-swing-carry-2026-10-30.md). 매도에는 쓰지 않는다. 과거 이벤트는 null로 역직렬화된다.
 */
public record Signal(
        String strategyId,
        StockCode symbol,
        Side side,
        Price refPrice,
        double confidence,
        Long fixedQuantity,
        Instant timestamp,
        Price benchmarkPrice,
        Integer tranche
) {

    /** v3 호환 생성자 — 분할 차수 없이(예전 매수·모든 매도). */
    public Signal(String strategyId, StockCode symbol, Side side, Price refPrice, double confidence,
                  Long fixedQuantity, Instant timestamp, Price benchmarkPrice) {
        this(strategyId, symbol, side, refPrice, confidence, fixedQuantity, timestamp, benchmarkPrice, null);
    }

    /** v2 호환 생성자 — benchmarkPrice 없이(지정가로 슬리피지를 잰다). */
    public Signal(String strategyId, StockCode symbol, Side side, Price refPrice, double confidence,
                  Long fixedQuantity, Instant timestamp) {
        this(strategyId, symbol, side, refPrice, confidence, fixedQuantity, timestamp, null, null);
    }

    /** v1 호환 생성자 — 기존 호출부(전략·테스트)는 fixedQuantity 없이 그대로 컴파일된다. */
    public Signal(String strategyId, StockCode symbol, Side side, Price refPrice,
                  double confidence, Instant timestamp) {
        this(strategyId, symbol, side, refPrice, confidence, null, timestamp, null, null);
    }
}
