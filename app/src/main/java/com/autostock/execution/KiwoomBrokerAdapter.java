package com.autostock.execution;

import com.autostock.common.event.OrderRequest;
import com.autostock.common.event.Side;
import com.autostock.common.util.KiwoomNumbers;
import com.autostock.kiwoom.KiwoomApiException;
import com.autostock.kiwoom.KiwoomRestClient;
import com.autostock.kiwoom.TrId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link BrokerPort}의 키움 REST 구현체 — 유일한 Kiwoom 어댑터(ARCHITECTURE.md 4절).
 *
 * <p>기존에 흩어져 있던 {@code KiwoomOrderService}(주문 제출) · {@code AccountService}
 * (잔고 조회) · {@code OutstandingOrderService}(미체결 조회)를 이 클래스 하나로 흡수했다.
 * Hexagonal 경계 규칙상 "Kiwoom 응답 Map은 어댑터 밖으로 노출 금지"이므로, 세 서비스가
 * 각자 Map을 반환하던 것을 이제는 이 클래스 안에서만 Map을 다루고 바깥에는 도메인
 * record(BrokerOrderResult/BrokerOutstandingOrder/BrokerBalance)만 돌려준다.
 *
 * <p>실측 검증 완료(2026-08-13, mockapi, 일반 모의계좌) — placeOrder():
 * <pre>
 *   매수 kt10000 / 매도 kt10001, POST /api/dostk/ordr
 *   요청: {"dmst_stex_tp":"KRX","stk_cd":"005930","ord_qty":"1","ord_uv":"","trde_tp":"3","cond_uv":""}
 *   응답: {"ord_no":"0121751","dmst_stex_tp":"KRX","return_code":0,"return_msg":"모의투자 매수주문완료"}
 *   trde_tp: "0" 보통(지정가, ord_uv 필수) / "3" 시장가(ord_uv 빈값)
 * </pre>
 * outstandingOrders()도 실측 완료(응답의 "oso" 키에 배열). cancelOrder()/balance()도
 * 2026-09-11 실측 확정 — 남은 TODO 실측은 ka10075 응답 원소의 세부 필드뿐(toOutstandingOrder 참고).
 */
@Service
public class KiwoomBrokerAdapter implements BrokerPort {

    private static final Logger log = LoggerFactory.getLogger(KiwoomBrokerAdapter.class);

    private static final String ORDER_PATH = "/api/dostk/ordr";
    private static final String ACCOUNT_PATH = "/api/dostk/acnt";

    /** 실측 확정된 미체결 목록 응답 키(ka10075). */
    private static final String OUTSTANDING_LIST_KEY = "oso";

    /**
     * TODO 실측: kt00018 응답에서 보유 종목 리스트가 담기는 키.
     * KiwoomOrderService 옛 Javadoc에서 "잔고(kt00018)의 acnt_evlt_remn_indv_tot에 반영됨"이라고
     * 언급된 필드명을 그대로 채택했다 — 실제 응답으로 검증되지 않았다.
     */
    private static final String BALANCE_HOLDINGS_KEY = "acnt_evlt_remn_indv_tot";

    private final KiwoomRestClient client;

    public KiwoomBrokerAdapter(KiwoomRestClient client) {
        this.client = client;
    }

    /**
     * @return 브로커 주문번호를 담은 결과
     * @throws BrokerRejectedException 브로커가 명시 거부 응답을 준 경우(RC4027·800033 등 실측) —
     *         호출자(TradingService)가 UNKNOWN이 아닌 REJECTED로 분류할 수 있다(운영 1일차 ②)
     */
    @Override
    public BrokerOrderResult placeOrder(OrderRequest request) {
        TrId trId = request.side() == Side.BUY ? TrId.ORDER_BUY : TrId.ORDER_SELL;
        Map<String, Object> response;
        try {
            response = client.call(trId, ORDER_PATH, Map.of(
                    "dmst_stex_tp", "KRX",
                    "stk_cd", request.symbol(),
                    "ord_qty", String.valueOf(request.quantity()),
                    "ord_uv", request.limitPrice().toPlainString(),
                    "trde_tp", "0"          // 보통(지정가) — 문서 실측 후 확정
            ));
        } catch (KiwoomApiException e) {
            // KiwoomApiException = API 계층 오류 응답을 "수신"한 경우 — 결과 불명이 아니라
            // 명시 거부다. 타임아웃/네트워크 예외(WebClient 계열)는 그대로 위로 던져져
            // TradingService에서 UNKNOWN 처리된다.
            throw new BrokerRejectedException(e.getMessage(), e);
        }
        Object orderNo = response.get("ord_no");
        if (orderNo == null) {
            // 정상 코드인데 주문번호가 없는 기형 응답 — 접수 여부를 단정할 수 없어 불명으로 남긴다
            throw new KiwoomApiException("주문 응답에 주문번호 없음: " + response);
        }
        return new BrokerOrderResult(orderNo.toString());
    }

    /**
     * 미체결 주문을 취소 요청한다(kt10003, 주식 취소주문).
     *
     * <p><b>실측 확정 (2026-09-11, mockapi — 미체결 0186358 실취소)</b>: 요청 바디
     * {@code {dmst_stex_tp, orig_ord_no, stk_cd, cncl_qty}}가 그대로 유효하며,
     * {@code cncl_qty:"0"} = 잔량 전량 취소로 동작함을 확인(응답 cncl_qty에 실제 취소 수량
     * 000000000019가 옴). 응답: {@code {ord_no(취소주문 자체의 새 주문번호),
     * base_orig_ord_no(원주문번호), cncl_qty, return_code:0, return_msg:"모의투자 취소주문완료"}}.
     */
    @Override
    public void cancelOrder(String brokerOrderId, String symbol, long quantity) {
        Map<String, Object> response;
        try {
            response = client.call(TrId.ORDER_CANCEL, ORDER_PATH, Map.of(
                    "dmst_stex_tp", "KRX",
                    "orig_ord_no", brokerOrderId,              // 실측 확정: 원주문번호
                    "stk_cd", symbol,
                    "cncl_qty", String.valueOf(quantity)        // 실측 확정: "0"이면 잔량 전량 취소
            ));
        } catch (KiwoomApiException e) {
            // 명시 거부(예: 이미 전량 체결됨) — placeOrder와 같은 번역 규칙(운영 1일차 ②)
            throw new BrokerRejectedException(e.getMessage(), e);
        }
        log.info("[LIVE] 취소 요청: brokerOrderId={} symbol={} qty={} → {}",
                brokerOrderId, symbol, quantity, response.get("return_msg"));
    }

    /** 실측 확정(2026-08-13, mockapi): 미체결 목록은 응답의 {@code "oso"} 키에 배열로 담긴다. */
    @Override
    @SuppressWarnings("unchecked")
    public List<BrokerOutstandingOrder> outstandingOrders() {
        // all_stk_tp=1(전체), trde_tp=0(전체 매매구분), stex_tp=0(전체 거래소) — 실측 통과 파라미터
        Map<String, Object> response = client.call(TrId.OUTSTANDING_ORDERS, ACCOUNT_PATH, Map.of(
                "all_stk_tp", "1",
                "trde_tp", "0",
                "stex_tp", "0"));

        Object list = response.get(OUTSTANDING_LIST_KEY);
        if (!(list instanceof List<?> orders)) {
            return List.of();
        }
        List<BrokerOutstandingOrder> result = new ArrayList<>();
        for (Object element : orders) {
            if (element instanceof Map<?, ?> raw) {
                result.add(toOutstandingOrder((Map<String, Object>) raw));
            }
        }
        return result;
    }

    /**
     * ka10075 응답 원소 하나를 도메인 record로 변환한다.
     *
     * <p><b>TODO 실측</b>: 필드명({@code ord_no}/{@code stk_cd}/{@code ord_qty} 정도만
     * 주문 TR과의 일관성으로 신뢰도 있게 추정되고, 매매방향·미체결잔량 필드명은 문서 기반
     * 추정이다. mockapi 미체결 주문 응답을 직접 받아본 뒤 확정해야 한다.
     */
    private BrokerOutstandingOrder toOutstandingOrder(Map<String, Object> raw) {
        String brokerOrderId = String.valueOf(raw.getOrDefault("ord_no", ""));
        String symbol = normalizeSymbol(String.valueOf(raw.getOrDefault("stk_cd", "")));
        Side side = parseSide(raw.get("trde_tp")); // TODO 실측: "1"=매도/"2"=매수 등 코드 체계 추정
        long quantity = KiwoomNumbers.toLongOrZero(raw.get("ord_qty"));
        // TODO 실측: 미체결잔량 필드명 추정("un_qty" 등 실제 값 미확인) — 없으면 주문수량 전량 미체결로 간주
        long remaining = raw.containsKey("un_qty")
                ? KiwoomNumbers.toLongOrZero(raw.get("un_qty"))
                : quantity;
        return new BrokerOutstandingOrder(brokerOrderId, symbol, side, quantity, remaining);
    }

    /** TODO 실측: 매매구분 코드 체계 추정. 알 수 없는 값은 안전하게 BUY로 간주하지 않고 로그만 남긴다. */
    private Side parseSide(Object raw) {
        String code = String.valueOf(raw);
        if ("2".equals(code)) {
            return Side.BUY;
        }
        if ("1".equals(code)) {
            return Side.SELL;
        }
        log.warn("미체결 주문의 매매구분 코드가 예상 밖 값입니다(TODO 실측 필요): {} — BUY로 간주", code);
        return Side.BUY;
    }

    /**
     * 계좌 잔고를 조회한다(kt00018).
     *
     * <p><b>실측 확정 (2026-09-11, mockapi)</b>: 최상위 필드 tot_pur_amt / tot_evlt_amt /
     * tot_evlt_pl(← 기존 추정 tot_evlt_pl_amt는 오답이었음) / prsm_dpst_aset_amt(추정예탁자산
     * = 계좌 총자산 — equity 산정 기준) / acnt_evlt_remn_indv_tot(보유 배열). 보유가 없으면
     * tot_evlt_amt=0이고 현금은 prsm_dpst_aset_amt에만 있다 — equity=0 매수 불가 사고의 원인.
     * 값이 없으면 0으로 안전 처리된다({@link KiwoomNumbers#toBigDecimal(Object)}).
     */
    @Override
    @SuppressWarnings("unchecked")
    public BrokerBalance balance() {
        Map<String, Object> response = client.call(TrId.ACCOUNT_BALANCE, ACCOUNT_PATH,
                Map.of("qry_tp", "1", "dmst_stex_tp", "KRX"));

        BigDecimal totalEvaluation = KiwoomNumbers.toBigDecimal(response.get("tot_evlt_amt"));
        BigDecimal totalPurchase = KiwoomNumbers.toBigDecimal(response.get("tot_pur_amt"));
        BigDecimal totalProfitLoss = KiwoomNumbers.toBigDecimal(response.get("tot_evlt_pl"));
        BigDecimal estimatedDepositAsset = KiwoomNumbers.toBigDecimal(response.get("prsm_dpst_aset_amt"));

        List<Map<String, Object>> holdings = new ArrayList<>();
        Object rawHoldings = response.get(BALANCE_HOLDINGS_KEY);
        if (rawHoldings instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof Map<?, ?> raw) {
                    holdings.add((Map<String, Object>) raw);
                }
            }
        }
        return new BrokerBalance(totalEvaluation, totalPurchase, totalProfitLoss,
                estimatedDepositAsset, List.copyOf(holdings));
    }

    /**
     * 잔고 응답의 종목코드는 "A005930"처럼 A 접두가 붙는다(실측 확인) — 비교 시 정규화 필요.
     */
    private String normalizeSymbol(String symbol) {
        return symbol.startsWith("A") ? symbol.substring(1) : symbol;
    }
}
