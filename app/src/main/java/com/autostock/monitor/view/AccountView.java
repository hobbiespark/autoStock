package com.autostock.monitor.view;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 계좌 잔고 View DTO(2026-10-23) — 사용자 "계좌 잔고 표시(보유종목 없으면 계좌금액) 되어야 함".
 *
 * <p>값은 monitor.AccountSummaryService가 kt00018 잔고를 거래일 30초(휴장일 10분)마다 한 번 읽어 채운다 — 대시보드는 2초마다
 * 부르지만 브로커 조회는 그 주기를 넘지 않는다. 조회가 실패하면 마지막 성공값을 그대로 보이고 {@code note}에 사유를 적는다.
 *
 * @param available         잔고를 한 번이라도 읽었나(SIM이거나 아직 못 읽었으면 false — 금액 칸은 null)
 * @param totalAsset        계좌 총자산 = 추정예탁자산(prsm_dpst_aset_amt) — 보유가 없으면 이 값이 곧 계좌 금액
 * @param holdingsValue     보유 평가금액 합(tot_evlt_amt)
 * @param holdingsCost      보유 매입금액 합(tot_pur_amt)
 * @param holdingsPnl       보유 평가손익 합(tot_evlt_pl, 손실이면 음수)
 * @param asOf              잔고를 읽은 시각
 * @param note              SIM·조회 실패 등 알릴 말 — 없으면 null
 * @param capitalLimitKrw   운용 자금(원, 0이면 꺼짐) — 2026-10-21
 * @param capitalHeldCost   운용 자금 사용 중 — 계좌 보유 매수 원가(앱 장부 기준, RiskGate와 같은 셈)
 * @param capitalPendingBuy 운용 자금 사용 중 — 미체결 매수 남은 금액(못 세면 null)
 */
public record AccountView(
        boolean available,
        BigDecimal totalAsset,
        BigDecimal holdingsValue,
        BigDecimal holdingsCost,
        BigDecimal holdingsPnl,
        Instant asOf,
        String note,
        long capitalLimitKrw,
        BigDecimal capitalHeldCost,
        BigDecimal capitalPendingBuy
) {
}
