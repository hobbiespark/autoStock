package com.autostock.execution;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 계좌 잔고 도메인 표현 — {@link BrokerPort#balance()}의 반환값.
 *
 * <p><b>TODO 실측</b>: kt00018(계좌평가잔고내역) 응답의 정확한 필드 구조는 아직 실측되지
 * 않았다(AccountService 흡수 당시 balance()는 Map을 그대로 반환하는 수준이었다).
 * 여기 있는 필드명·매핑은 TR명("계좌평가잔고내역")과 이미 실측된 인접 TR(kt10000 응답의
 * "A" 종목코드 접두 등)에서 유추한 합리적 추정이다. mockapi 응답을 직접 받아본 뒤
 * {@code KiwoomBrokerAdapter#balance()}의 필드 매핑과 함께 확정해야 한다.
 *
 * @param totalEvaluationAmount 총평가금액(추정 필드, TODO 실측)
 * @param totalPurchaseAmount   총매입금액(추정 필드, TODO 실측)
 * @param totalProfitLoss       총평가손익금액(추정 필드, TODO 실측)
 * @param holdings              종목별 보유 내역 원본(TODO: 실측 후 typed record로 세분화)
 */
public record BrokerBalance(
        BigDecimal totalEvaluationAmount,
        BigDecimal totalPurchaseAmount,
        BigDecimal totalProfitLoss,
        List<Map<String, Object>> holdings
) {
}
