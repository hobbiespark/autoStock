package com.autostock.execution;

import com.autostock.common.event.PositionRestored;
import com.autostock.common.util.KiwoomNumbers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

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
 * "005930 19주 @ 259,974"가 실체결 평단과 일치함을 확인했다. 종목코드는 "A005930"처럼
 * A 접두가 붙는다(KiwoomBrokerAdapter#normalizeSymbol과 동일 규칙). 후보 키 탐색 구조는
 * 다른 계좌 유형 방어용으로 유지한다.
 */
@Component
public class PositionRestorer {

    private static final Logger log = LoggerFactory.getLogger(PositionRestorer.class);

    private final BrokerPort brokerPort;
    private final ApplicationEventPublisher publisher;
    private final boolean liveMode;

    public PositionRestorer(BrokerPort brokerPort,
                            ApplicationEventPublisher publisher,
                            @Value("${execution.mode:SIM}") String executionMode) {
        this.brokerPort = brokerPort;
        this.publisher = publisher;
        this.liveMode = "LIVE".equalsIgnoreCase(executionMode);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restore() {
        if (!liveMode) {
            return;
        }
        BrokerBalance balance;
        try {
            balance = brokerPort.balance();
        } catch (Exception e) {
            // 기동 복원은 best-effort — 실패해도 앱은 계속 뜬다(기동 대사 안전화와 동일 원칙).
            // 이 경우 보유 종목 매도는 체결로 포지션이 재생성되기 전까지 거부될 수 있다.
            log.warn("포지션 복원용 잔고 조회 실패 — 복원 생략: {}", e.getMessage());
            return;
        }
        boolean keysLogged = false;
        for (Map<String, Object> holding : balance.holdings()) {
            if (!keysLogged) {
                log.info("[실측] kt00018 보유 원소 키: {}", holding.keySet());
                keysLogged = true;
            }
            String symbol = normalizeSymbol(firstText(holding, "stk_cd", "stock_cd"));
            long quantity = firstLong(holding, "rmnd_qty", "evlt_rmnd_qty", "hldg_qty", "qty");
            BigDecimal avgPrice = firstPrice(holding, "pur_pric", "pchs_avg_pric", "avg_prc", "pur_avg_pric");
            if (symbol.isEmpty() || quantity <= 0) {
                log.warn("포지션 복원 원소 해석 실패(TODO 실측 — 위 키 로그 참고): symbol={}, qty={}",
                        symbol, quantity);
                continue;
            }
            publisher.publishEvent(new PositionRestored(symbol, quantity, avgPrice));
        }
    }

    private static String firstText(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object raw = map.get(key);
            if (raw != null && !String.valueOf(raw).isBlank()) {
                return String.valueOf(raw).trim();
            }
        }
        return "";
    }

    private static long firstLong(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object raw = map.get(key);
            if (raw != null && !String.valueOf(raw).isBlank()) {
                long value = KiwoomNumbers.toLongOrZero(raw);
                if (value > 0) {
                    return value;
                }
            }
        }
        return 0L;
    }

    private static BigDecimal firstPrice(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object raw = map.get(key);
            if (raw != null && !String.valueOf(raw).isBlank()) {
                BigDecimal value = KiwoomNumbers.toBigDecimal(raw).abs();
                if (value.signum() > 0) {
                    return value;
                }
            }
        }
        return BigDecimal.ZERO;
    }

    private static String normalizeSymbol(String symbol) {
        return symbol.startsWith("A") ? symbol.substring(1) : symbol;
    }
}
