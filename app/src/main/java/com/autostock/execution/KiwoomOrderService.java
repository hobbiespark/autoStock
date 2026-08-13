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
 * TODO Phase 2 검증: 앱키 발급 후 모의투자에서 필드명·응답 포맷 실측 확인.
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
