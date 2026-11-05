package com.autostock.kiwoom;

import com.autostock.common.util.KiwoomNumbers;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 공모주 상장일 <b>실전</b> 매도 전용 키움 클라이언트(2026-11-05, aiDoc/ipo-automation.md 8절).
 *
 * <p>모의 서버용 {@link KiwoomRestClient}·{@link TokenManager} 빈과 <b>분리</b>된다 — 실전 도메인
 * {@link #LIVE_REST_BASE_URL} 고정, 실전 키({@code KIWOOM_LIVE_APP_KEY}/{@code KIWOOM_LIVE_APP_SECRET})로 받은 별도 토큰,
 * 별도 유량 제한기를 쓴다. 모의 키로 폴백하지 않는다(키가 비면 {@link #create}가 거절한다).
 *
 * <p>공개 메서드는 <b>지정가 매도·잔고 조회·미체결 조회</b> 셋뿐이다. 매수·정정·취소 메서드는 일부러 두지 않는다
 * (ArchitectureRulesTest가 이 클래스의 {@code ORDER_BUY} 접근을 막는다). 시장가 주문도 없다(trde_tp 0 지정가만).
 *
 * <p><b>가정(첫 상장일 실측 필요)</b>: 실전 주문·잔고·미체결 TR은 모의와 같은 api-id(kt10001·kt00018·ka10075)·요청·응답
 * 형식이라고 본다(키움 문서상 도메인만 다르다). 실전 서버로 보낸 적은 아직 없다.
 */
public final class KiwoomLiveSellClient {

    /** 실전 REST 도메인 — application.yml live 프로필과 같다. 설정으로 바꾸지 않는다(모의 서버로 새는 것을 막는다). */
    public static final String LIVE_REST_BASE_URL = "https://api.kiwoom.com";
    static final String LIVE_WS_URL = "wss://api.kiwoom.com:10000/api/dostk/websocket";

    private static final String ORDER_PATH = "/api/dostk/ordr";
    private static final String ACCOUNT_PATH = "/api/dostk/acnt";

    /** 실전 호출 창구 — 밖으로 내보내지 않는다(아무 TR이나 부를 수 있는 일반 call을 감춘다). */
    private final KiwoomRestClient rest;

    KiwoomLiveSellClient(KiwoomRestClient rest) {
        this.rest = Objects.requireNonNull(rest);
    }

    /**
     * 실전 키로 클라이언트를 만든다. 키가 비어 있으면 {@link IllegalStateException} — 호출자는 기능을 끈다.
     * 토큰은 첫 호출 때 실전 서버에서 받는다(만들 때 네트워크를 쓰지 않는다).
     */
    public static KiwoomLiveSellClient create(WebClient.Builder builder, String appKey, String appSecret,
                                              MeterRegistry meterRegistry, ApplicationEventPublisher publisher, Clock clock) {
        if (isBlank(appKey) || isBlank(appSecret)) {
            throw new IllegalStateException("live-key-missing");
        }
        KiwoomProperties live = new KiwoomProperties(LIVE_REST_BASE_URL, LIVE_WS_URL, appKey, appSecret);
        TokenManager tokens = new TokenManager(builder.clone(), live, publisher, clock);
        KiwoomRestClient rest = new KiwoomRestClient(builder.clone(), live, tokens, new TrRateLimiter(),
                meterRegistry, publisher, clock);
        return new KiwoomLiveSellClient(rest);
    }

    /** 키 두 개가 모두 있는가(값은 보지 않고 비었는지만 본다). */
    public static boolean hasKeys(String appKey, String appSecret) {
        return !isBlank(appKey) && !isBlank(appSecret);
    }

    /**
     * 지정가 매도(kt10001, trde_tp 0 보통). 응답의 주문번호를 돌려준다. 응답에 주문번호가 없으면 null(결과 불명으로 다룬다).
     *
     * @throws KiwoomTimeoutException 응답 없음 — 결과 불명. 다시 보내지 않는다
     * @throws KiwoomApiException     키움이 거부(논리 오류·HTTP 오류)
     */
    public String sellLimit(String symbol, long quantity, BigDecimal limitPrice) {
        if (symbol == null || !symbol.matches("\\d{6}")) {
            throw new IllegalArgumentException("symbol");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity");
        }
        if (limitPrice == null || limitPrice.signum() <= 0 || limitPrice.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("limitPrice");
        }
        Map<String, Object> response = rest.call(TrId.ORDER_SELL, ORDER_PATH, Map.of(
                "dmst_stex_tp", "KRX",
                "stk_cd", symbol,
                "ord_qty", String.valueOf(quantity),
                "ord_uv", limitPrice.toBigInteger().toString(),
                "trde_tp", "0",          // 보통(지정가) — 시장가(3)는 쓰지 않는다
                "cond_uv", ""));
        Object orderNo = response == null ? null : response.get("ord_no");
        String text = orderNo == null ? "" : String.valueOf(orderNo).trim();
        return text.isEmpty() ? null : text;
    }

    /** 실전 잔고(kt00018)의 그 종목 보유 수량과 매도 가능 수량. 없으면 0·0. */
    @SuppressWarnings("unchecked")
    public Holding holding(String symbol) {
        Map<String, Object> response = rest.call(TrId.ACCOUNT_BALANCE, ACCOUNT_PATH,
                Map.of("qry_tp", "1", "dmst_stex_tp", "KRX"));
        Object list = response == null ? null : response.get("acnt_evlt_remn_indv_tot");
        if (!(list instanceof List<?> rows)) {
            return new Holding(0, 0);
        }
        for (Object row : rows) {
            if (row instanceof Map<?, ?> raw && symbol.equals(stripA(raw.get("stk_cd")))) {
                Map<String, Object> m = (Map<String, Object>) raw;
                long qty = KiwoomNumbers.toLongOrZero(m.get("rmnd_qty"));
                long sellable = m.containsKey("trde_able_qty") ? KiwoomNumbers.toLongOrZero(m.get("trde_able_qty")) : qty;
                return new Holding(qty, Math.min(qty, sellable));
            }
        }
        return new Holding(0, 0);
    }

    /** 실전 미체결(ka10075) 중 그 종목 주문 — 주문번호·남은 수량. */
    public List<OpenOrder> outstandingOrders(String symbol) {
        Map<String, Object> response = rest.call(TrId.OUTSTANDING_ORDERS, ACCOUNT_PATH, Map.of(
                "all_stk_tp", "1",
                "trde_tp", "0",
                "stex_tp", "0"));
        Object list = response == null ? null : response.get("oso");
        if (!(list instanceof List<?> rows)) {
            return List.of();
        }
        List<OpenOrder> result = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof Map<?, ?> raw && symbol.equals(stripA(raw.get("stk_cd")))) {
                String orderNo = raw.get("ord_no") == null ? "" : String.valueOf(raw.get("ord_no")).trim();
                Object remaining = raw.containsKey("oso_qty") ? raw.get("oso_qty") : raw.get("ord_qty");
                result.add(new OpenOrder(orderNo, KiwoomNumbers.toLongOrZero(remaining)));
            }
        }
        return result;
    }

    /** 보유 수량과 매도 가능 수량(미체결 매도에 묶인 수량을 뺀 값). */
    public record Holding(long quantity, long sellable) {
    }

    /** 미체결 주문 — 주문번호와 남은 수량. */
    public record OpenOrder(String orderNo, long remaining) {
    }

    private static String stripA(Object raw) {
        String text = raw == null ? "" : String.valueOf(raw).trim();
        return text.startsWith("A") ? text.substring(1) : text;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
