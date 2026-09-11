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
 * <p><b>실측 확정 2026-09-11</b> — docs/measured/ws_probe_20260911_intraday.txt에서
 * 005930 모의투자 계좌로 시장가 매수 1주 → 매도 1주를 직접 체결시켜 "접수"→"체결"
 * 왕복 4건(FID 값 포함)을 확보했다. 아래 필드 매핑은 문서 기반 추정이 아니라 그
 * 실측 전문으로 검증된 값이다. 단 "취소"/"거부" 상태 문자열은 이번 실측에서
 * 관측되지 않아 여전히 TODO 실측(미확정)이다.
 *
 * @param brokerOrderId    브로커 주문번호 (FID 9203, 실측 확정 — REST ord_no와 동일 포맷
 *                         "0119433"류) — execution의 brokerOrderId → OrderRequest 맵을
 *                         조회하는 키로 쓰인다.
 * @param symbol           종목코드 (FID 9001, 실측 확정 — "A" 접두 없이 "005930" 그대로)
 * @param status           주문상태 원문 (FID 913, 실측 확정 — 관측된 값은 정확히
 *                         "접수"/"체결" 두 가지뿐이다. "취소"/"거부" 등은 미관측이라
 *                         원문 그대로 보관하고 의미 해석은 소비자(OrderNoticeHandler)가
 *                         방어적으로 처리한다(TODO 실측).
 * @param filledQuantity   이번 통보 1건의 체결 수량 (FID 911, 실측 확정) — 부분체결이
 *                         통보마다 "이번에 체결된 분"만 담기는지는 이번 실측(수량 1주
 *                         단건 체결)으로는 검증되지 않았다(TODO 실측 — 부분체결 시나리오).
 * @param fillPrice        이번 통보의 체결가 (FID 910, 실측 확정) — 접수 통보처럼 체결이
 *                         아닌 경우 빈 문자열로 와서 null로 정규화된다.
 * @param remainingQuantity 미체결 잔량 (FID 902, 실측 확정) — 접수 시 주문수량과 동일하게
 *                         시작해 체결 시 0으로 내려온다("902 미체결=0이면 FILLED"로 쓰인다).
 *                         FID가 아예 없는 메시지(과거 테스트 픽스처 등)는 -1(알 수 없음)로
 *                         정규화해, 이 값을 모르는 경우와 실제로 0인 경우를 구분한다.
 * @param rawType          원본 WS type 값 ("00" 고정) — 디버깅·추적용으로 원본을 함께 보관한다.
 * @param timestamp        이벤트 수신 시각 (수신 측 로컬 시각, 서버 타임스탬프 아님)
 */
public record OrderNotice(
        String brokerOrderId,
        String symbol,
        String status,
        long filledQuantity,
        BigDecimal fillPrice,
        long remainingQuantity,
        String rawType,
        Instant timestamp
) {
}
