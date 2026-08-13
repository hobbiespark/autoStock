package com.autostock.common.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * WS 주문체결통보(type 00)를 정규화한 이벤트. (스키마 v1)
 *
 * <p>키움 WS가 실시간으로 밀어주는 "계좌 단위" 이벤트를 표준 필드로 옮겨 담은 것이다.
 * 시세(MarketTick)는 "종목이 지금 얼마냐"를 알려주지만, 이 이벤트는 "내 주문이
 * 지금 어떻게 됐냐"를 알려준다 — 완전히 다른 계열이라 별도 레코드로 분리했다.
 * execution 모듈의 {@code OrderNoticeHandler}가 이 이벤트를 받아 실제
 * 체결(Fill)로 이어지는지 판단한다.
 *
 * <p><b>TODO Phase 2 실측 확정 필요</b> — 아래 필드 매핑은 키움 OpenAPI+ FID(필드 ID)
 * 체계를 준용한 "문서 기반 추정"이며, 실제 서버 응답으로 검증되지 않았다.
 * scripts/ws_probe.py로 모의투자 WS를 직접 열어 받은 REAL(type=00) 메시지의
 * 실제 JSON을 확인한 뒤 이 Javadoc과 KiwoomWebSocketClient/RealMessageParser의
 * 매핑을 확정해야 한다.
 *
 * @param brokerOrderId  브로커 주문번호 (FID 9203 추정) — execution의
 *                       brokerOrderId → OrderRequest 맵을 조회하는 키로 쓰인다.
 * @param symbol         종목코드 (FID 9001 추정)
 * @param status         주문상태 원문 (FID 913 추정) — "접수"/"체결"/"취소" 등 원문 그대로 보관.
 *                       여기서 의미를 해석하지 않는 이유: 실측 전이라 정확한 값 집합을
 *                       모르기 때문에, 원문을 그대로 넘기고 판단은 소비자(OrderNoticeHandler)에게 맡긴다.
 * @param filledQuantity 이번 통보 1건의 체결 수량 (FID 911 추정) — 부분체결이면 통보마다
 *                       "이번에 체결된 분"만 담긴다고 가정 (누적치 아님, 실측 필요).
 * @param fillPrice      이번 통보의 체결가 (FID 910 추정) — 체결이 아닌 통보(접수/취소 등)는
 *                       null일 수 있다.
 * @param rawType        원본 WS type 값 ("00" 고정) — 디버깅·추적용으로 원본을 함께 보관한다.
 * @param timestamp      이벤트 수신 시각 (수신 측 로컬 시각, 서버 타임스탬프 아님)
 */
public record OrderNotice(
        String brokerOrderId,
        String symbol,
        String status,
        long filledQuantity,
        BigDecimal fillPrice,
        String rawType,
        Instant timestamp
) {
}
