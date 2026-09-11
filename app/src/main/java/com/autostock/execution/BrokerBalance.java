package com.autostock.execution;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 계좌 잔고 도메인 표현 — {@link BrokerPort#balance()}의 반환값.
 *
 * <p><b>실측 확정 (2026-09-11, mockapi kt00018 — PowerShell 실측)</b>: 최상위 필드는
 * {@code tot_pur_amt}(총매입), {@code tot_evlt_amt}(총평가), {@code tot_evlt_pl}(총평가손익 —
 * 기존 추정 {@code tot_evlt_pl_amt}는 오답), {@code prsm_dpst_aset_amt}(추정예탁자산),
 * {@code acnt_evlt_remn_indv_tot}(종목별 보유 배열). 보유 종목이 없으면 tot_evlt_amt는 0이고
 * 현금은 prsm_dpst_aset_amt에만 반영된다 — 이것이 equity=0 매수 불가 사고의 원인이었다.
 *
 * <p><b>추정예탁자산의 의미</b>: 키움 HTS/REST 관례상 "추정예탁자산 = 예수금 + 보유 평가금액"
 * (즉 계좌 총자산)이다. 사이징의 equity로는 이 값을 단독 사용한다(총평가와 더하면 이중계산).
 * 보유 종목이 있는 상태의 값 구성은 아직 미관측 — 모의 운영 중 보유 발생 시 로그로 재확인(TODO 실측).
 *
 * @param totalEvaluationAmount 총평가금액 (tot_evlt_amt — 보유 주식 평가액 합, 실측 확정)
 * @param totalPurchaseAmount   총매입금액 (tot_pur_amt, 실측 확정)
 * @param totalProfitLoss       총평가손익 (tot_evlt_pl, 실측 확정)
 * @param estimatedDepositAsset 추정예탁자산 (prsm_dpst_aset_amt — 계좌 총자산, equity 산정 기준)
 * @param holdings              종목별 보유 내역 원본 (acnt_evlt_remn_indv_tot — 실측 후 typed record로 세분화 TODO)
 */
public record BrokerBalance(
        BigDecimal totalEvaluationAmount,
        BigDecimal totalPurchaseAmount,
        BigDecimal totalProfitLoss,
        BigDecimal estimatedDepositAsset,
        List<Map<String, Object>> holdings
) {
}
