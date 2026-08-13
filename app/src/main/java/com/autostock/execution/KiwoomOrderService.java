package com.autostock.execution;

import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.Side;
import com.autostock.kiwoom.KiwoomApiException;
import com.autostock.kiwoom.KiwoomRestClient;
import com.autostock.kiwoom.TrId;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 키움 주문 API 어댑터 (LIVE 모드).
 *
 * <p>실측 검증 완료(2026-08-13, mockapi, 일반 모의계좌):
 * <pre>
 *   매수 kt10000 / 매도 kt10001, POST /api/dostk/ordr
 *   요청: {"dmst_stex_tp":"KRX","stk_cd":"005930","ord_qty":"1","ord_uv":"","trde_tp":"3","cond_uv":""}
 *   응답: {"ord_no":"0121751","dmst_stex_tp":"KRX","return_code":0,"return_msg":"모의투자 매수주문완료"}
 *   trde_tp: "0" 보통(지정가, ord_uv 필수) / "3" 시장가(ord_uv 빈값)
 *   장중 시장가는 즉시 체결되어 잔고(kt00018)의 acnt_evlt_remn_indv_tot에 반영됨.
 *   주의: 잔고 응답의 종목코드는 "A005930"처럼 A 접두가 붙는다 — 비교 시 정규화 필요.
 * </pre>
 */
@Service
public class KiwoomOrderService {

    private static final String ORDER_PATH = "/api/dostk/ordr";

    private final KiwoomRestClient client;

    public KiwoomOrderService(KiwoomRestClient client) {
        this.client = client;
    }

    /** @return 브로커 주문번호 */
    public String placeOrder(OrderRequest request) {
        TrId trId = request.side() == Side.BUY ? TrId.ORDER_BUY : TrId.ORDER_SELL;
        Map<String, Object> response = client.call(trId, ORDER_PATH, Map.of(
                "dmst_stex_tp", "KRX",
                "stk_cd", request.symbol(),
                "ord_qty", String.valueOf(request.quantity()),
                "ord_uv", request.limitPrice().toPlainString(),
                "trde_tp", "0"          // 보통(지정가) — 문서 실측 후 확정
        ));
        Object orderNo = response.get("ord_no");
        if (orderNo == null) {
            throw new KiwoomApiException("주문 응답에 주문번호 없음: " + response);
        }
        return orderNo.toString();
    }
}
