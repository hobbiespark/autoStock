package com.autostock.execution;

import com.autostock.common.event.PositionRestored;
import com.autostock.common.util.StockNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


/**
 * 재시작 시 브로커 잔고 기반 포지션 복원 (운영 1일차 ⑨).
 *
 * <p>배경(2026-09-11): PositionBook은 인메모리라 재기동하면 실보유 종목도 "미보유"가 되고,
 * RiskGate가 매도 시그널을 거부해 보유분을 시스템으로 청산할 수 없게 된다(19주 보유 상태로
 * 주말을 넘기며 실제 문제화). 기동 완료 시 kt00018 잔고의 보유 배열
 * (acnt_evlt_remn_indv_tot)을 읽어 종목당 {@link PositionRestored}를 발행한다.
 *
 * <p>LIVE 모드에서만 동작한다 — SIM은 브로커 잔고가 의미 없다(paper-equity 고정).
 *
 * <p><b>실측 확정 (2026-09-11, mockapi — 005930 19주 보유 상태 기동)</b>: 보유 원소 키는
 * {@code stk_cd, stk_nm, evltv_prft, prft_rt, pur_pric, pred_close_pric, rmnd_qty,
 * trde_able_qty, cur_prc, pred_buyq, pred_sellq, tdy_buyq, tdy_sellq, pur_amt, pur_cmsn,
 * evlt_amt, sell_cmsn, tax, sum_cmsn, poss_rt, crd_tp, crd_tp_nm, crd_loan_dt}.
 * 사용 필드: {@code rmnd_qty}=보유수량, {@code pur_pric}=매입가(평단) — 복원 결과
 * "005930 19주 @ 259,974"가 실체결 평단과 일치함을 확인했다. 해석(후보 키·A 접두 제거)은
 * 실행 계획 1.5부터 어댑터({@link KiwoomBrokerAdapter#toHolding})가 맡고, 이 클래스는
 * {@link BrokerHolding}만 본다.
 */
@Component
public class PositionRestorer {

    private static final Logger log = LoggerFactory.getLogger(PositionRestorer.class);

    private final BrokerPort brokerPort;
    private final ApplicationEventPublisher publisher;
    private final boolean liveMode;

    /** 복원 완료 여부 — 성공(잔고 조회 성공) 시 재시도를 멈춘다. */
    private final java.util.concurrent.atomic.AtomicBoolean restored =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public PositionRestorer(BrokerPort brokerPort,
                            ApplicationEventPublisher publisher,
                            @Value("${execution.mode:SIM}") String executionMode) {
        this.brokerPort = brokerPort;
        this.publisher = publisher;
        this.liveMode = "LIVE".equalsIgnoreCase(executionMode);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restore() {
        tryRestore("기동");
    }

    /**
     * 복원 재시도 (운영 2일차 결함, 2026-09-12 실측).
     *
     * <p>배경: 토요일 기동 시 키움 모의 서버가 주말 점검으로 닫혀 잔고 조회가 실패했고,
     * 기존 구현은 기동 1회만 시도했으므로 <b>포지션이 영구히 미복원</b>으로 남았다. 이 상태로
     * 월요일 장을 맞으면 실보유 19주가 "미보유"로 취급돼 매도 시그널이 전부 거부된다
     * (RiskGate.sizeSell) — 앱을 수동 재기동해야만 풀리는 함정. 복원에 성공할 때까지
     * 5분마다 재시도해 서버가 열리는 즉시 스스로 회복하게 한다.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void retryUntilRestored() {
        if (restored.get()) {
            return;
        }
        tryRestore("재시도");
    }

    private void tryRestore(String trigger) {
        if (!liveMode || restored.get()) {
            return;
        }
        BrokerBalance balance;
        try {
            balance = brokerPort.balance();
        } catch (Exception e) {
            // best-effort — 실패해도 앱은 계속 뜬다(기동 대사 안전화와 동일 원칙).
            // 실패는 종결이 아니다: retryUntilRestored가 5분마다 다시 시도한다.
            log.warn("포지션 복원용 잔고 조회 실패({}) — 5분 후 재시도: {}", trigger, e.getMessage());
            return;
        }
        restored.set(true); // 잔고 조회 성공 = 복원 완료(보유 0건이어도 성공이다)
        // 원소 해석(키움 필드)은 어댑터가 한다(실행 계획 1.5, BrokerHolding) — 해석 실패 원소는 어댑터가 경고 후 뺐다.
        for (BrokerHolding holding : balance.holdings()) {
            // 잔고 원소의 종목명(stk_nm, 실측 키)을 사전에 남긴다 — 보유 종목 이름은 추가 조회 없이 바로 표시된다
            // (2026-10-02 "종목코드와 종목명은 항상 같이 표시", aiDoc/stock-names.md)
            StockNames.learn(holding.symbol().value(), holding.name(), StockNames.Source.KIWOOM);
            if (holding.avgPrice() == null) {
                log.warn("포지션 복원: {} 매입가를 찾지 못해 평단 미상으로 복원(강제 청산 등 평단 기반 주문은 RiskGate가 거부)",
                        StockNames.label(holding.symbol()));
            }
            publisher.publishEvent(new PositionRestored(holding.symbol(), holding.quantity(), holding.avgPrice()));
        }
    }
}
