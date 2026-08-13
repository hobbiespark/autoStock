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
 * <p>TODO Phase 2 실측: 응답 JSON에서 미체결 목록이 정확히 어떤 필드 키에
 * 담기는지(예: "oso" 등) 문서만으로는 확정할 수 없었다. 그래서 응답에서
 * List 타입인 첫 값을 찾아 반환하는 방어적 방식을 쓴다 — 실측 후 정확한
 * 필드명으로 고정할 것.
 */
@Service
public class OutstandingOrderService {

    private static final String OUTSTANDING_PATH = "/api/dostk/acnt";

    private final KiwoomRestClient client;

    public OutstandingOrderService(KiwoomRestClient client) {
        this.client = client;
    }

    /** @return 미체결 주문 목록 (각 원소는 키움 응답의 원본 필드를 그대로 담은 맵) */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> outstandingOrders() {
        // all_stk_tp=0(전체 종목 유형), trde_tp=0(전체 매매구분), stk_cd=""(전 종목),
        // stex_tp=0(전체 거래소) — 문서 기반 추정, 실측 TODO
        Map<String, Object> response = client.call(TrId.OUTSTANDING_ORDERS, OUTSTANDING_PATH, Map.of(
                "all_stk_tp", "0",
                "trde_tp", "0",
                "stk_cd", "",
                "stex_tp", "0"));

        for (Object value : response.values()) {
            if (value instanceof List<?> list) {
                // 실측 전이라 정확한 키를 모르므로, 응답에서 List 타입인 첫 값을 미체결 목록으로 간주한다
                return (List<Map<String, Object>>) list;
            }
        }
        return List.of();
    }
}
