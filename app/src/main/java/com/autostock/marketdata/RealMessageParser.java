package com.autostock.marketdata;

import com.autostock.common.event.MarketTick;
import com.autostock.common.event.OrderNotice;
import com.autostock.common.util.KiwoomNumbers;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * WS REAL 메시지의 {@code data[]} 배열 원소 하나를 도메인 이벤트로 바꾸는 순수 파서.
 *
 * <p>왜 KiwoomWebSocketClient에서 떼어냈나 — 파싱 로직은 "JSON 문자열 → 이벤트
 * 레코드" 순수 함수라서 WebSocket 세션·구독 상태 없이도 단위테스트할 수 있어야
 * 한다. 클래스 안에 묻어두면 실제 WS 연결 없이는 검증할 방법이 없다.
 *
 * <p>type 필드로 갈래를 나눈다:
 * <pre>
 *   0B : 주식체결(시세)        → MarketTick
 *   00 : 주문체결통보(계좌 이벤트) → OrderNotice
 *   그 외: 아직 다루지 않음 → null (호출자는 null을 무시하면 된다)
 * </pre>
 *
 * <p><b>TODO Phase 2 실측 확정 필요</b> — 필드 번호(FID)는 키움 OpenAPI+ 문서
 * 체계를 준용한 추정치다. scripts/ws_probe.py로 실제 응답을 캡처한 뒤 확정할 것.
 */
final class RealMessageParser {

    /** 주식체결(시세) type 코드. */
    static final String TYPE_TICK = "0B";

    /** 주문체결통보(계좌 이벤트) type 코드. */
    static final String TYPE_ORDER_NOTICE = "00";

    private RealMessageParser() {
        // 순수 정적 유틸리티 — 인스턴스화 불필요
    }

    /**
     * REAL data[] 원소 하나를 파싱한다.
     *
     * @return type이 0B면 MarketTick, 00이면 OrderNotice, 그 외/필수값 누락이면 null
     */
    static Object parse(JsonNode data) {
        String type = data.path("type").asText("");
        return switch (type) {
            case TYPE_TICK -> parseTick(data);
            case TYPE_ORDER_NOTICE -> parseOrderNotice(data);
            default -> null; // 아직 다루지 않는 실시간 타입 — 조용히 무시
        };
    }

    /** TODO Phase 2 실측 확정: 실시간 필드 번호(10=현재가, 15=거래량 등) 확인. */
    private static MarketTick parseTick(JsonNode data) {
        String symbol = data.path("item").asText("");
        String priceRaw = data.path("values").path("10").asText("");
        if (symbol.isEmpty() || priceRaw.isEmpty()) {
            return null;
        }
        // 키움 REST/WS는 등락 부호(+/-)를 숫자 앞에 붙여 보내는 경우가 있어 제거 후 파싱한다.
        // 부호 정규화는 KiwoomNumbers로 공통화했다(marketdata REST 파서와 중복 제거, PLAN ADR-5).
        BigDecimal price = KiwoomNumbers.toBigDecimal(priceRaw);
        long volume = data.path("values").path("15").asLong(0);
        return new MarketTick(symbol, price, volume, Instant.now(), MarketTick.Source.LIVE);
    }

    /**
     * TODO Phase 2 실측 확정: FID 9203(주문번호)/9001(종목코드)/913(주문상태)/
     * 911(체결량)/910(체결가) 매핑은 문서 기반 추정 — 실측 후 값 확정 필요.
     */
    private static OrderNotice parseOrderNotice(JsonNode data) {
        JsonNode values = data.path("values");
        String brokerOrderId = values.path("9203").asText("");
        if (brokerOrderId.isEmpty()) {
            // 주문번호가 없으면 어떤 주문의 통보인지 알 수 없어 매핑 불가능 — 버린다
            return null;
        }
        String symbol = values.path("9001").asText("");
        String status = values.path("913").asText("");
        // 필드 번호가 아직 실측 미확정(문서 기반 추정)이라 방어적으로 0 처리하는 toLongOrZero를 쓴다.
        long filledQuantity = KiwoomNumbers.toLongOrZero(values.path("911").asText(""));
        String fillPriceRaw = values.path("910").asText("");
        BigDecimal fillPrice = fillPriceRaw.isEmpty() ? null : KiwoomNumbers.toBigDecimal(fillPriceRaw);
        return new OrderNotice(brokerOrderId, symbol, status, filledQuantity, fillPrice,
                TYPE_ORDER_NOTICE, Instant.now());
    }
}
