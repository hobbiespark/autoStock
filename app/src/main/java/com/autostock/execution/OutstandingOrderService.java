package com.autostock.execution;

import com.autostock.kiwoom.KiwoomRestClient;
import com.autostock.kiwoom.TrId;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 미체결 주문 조회 (ka10075).
 *
 * <p>왜 필요한가 — 두 가지 사고 시나리오의 기반이 된다.
 * <pre>
 *   1. 재시작 복원: 앱이 재시작되면 ExecutionService의 인메모리
 *      brokerOrderId 맵이 사라진다. 재시작 직후 미체결 목록을 REST로
 *      다시 물어보면 "지금 살아있는 주문이 뭔지"를 복원할 실마리가 된다.
 *   2. 타임아웃 취소: 오래 미체결 상태인 주문을 자동 취소하려면
 *      먼저 "지금 뭐가 미체결인지"를 알아야 한다.
 * </pre>
 * 이 클래스는 아직 "조회"만 한다 — 위 두 가지 활용은 TODO(Phase 2 후반)로 남긴다.
 *
 * <p>실측 확정(2026-08-13, mockapi): 미체결 목록은 응답의 {@code "oso"} 키에 배열로 담긴다.
 * 예: {@code {"oso":[],"return_code":0,"return_msg":" 조회가 완료되었습니다."}}
 */
@Service
public class OutstandingOrderService {

    private static final String OUTSTANDING_PATH = "/api/dostk/acnt";

    private final KiwoomRestClient client;

    public OutstandingOrderService(KiwoomRestClient client) {
        this.client = client;
    }

    /** 실측 확정된 미체결 목록 응답 키. */
    private static final String OUTSTANDING_LIST_KEY = "oso";

    /** @return 미체결 주문 목록 (각 원소는 키움 응답의 원본 필드를 그대로 담은 맵) */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> outstandingOrders() {
        // all_stk_tp=1(전체), trde_tp=0(전체 매매구분), stex_tp=0(전체 거래소) — 실측 통과 파라미터
        Map<String, Object> response = client.call(TrId.OUTSTANDING_ORDERS, OUTSTANDING_PATH, Map.of(
                "all_stk_tp", "1",
                "trde_tp", "0",
                "stex_tp", "0"));

        Object list = response.get(OUTSTANDING_LIST_KEY);
        if (list instanceof List<?> orders) {
            return (List<Map<String, Object>>) orders;
        }
        return List.of();
    }
}
