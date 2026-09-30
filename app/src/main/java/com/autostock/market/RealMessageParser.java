package com.autostock.market;

import com.autostock.common.event.MarketTick;
import com.autostock.common.event.OrderNotice;
import com.autostock.common.util.BrokerOrderId;
import com.autostock.common.util.KiwoomNumbers;
import com.autostock.common.util.MarketConstants;
import com.autostock.common.util.Price;
import com.autostock.common.util.StockCode;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

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
 * <p><b>실측 확정 2026-09-11</b> — docs/measured/ws_probe_20260911_intraday.txt에
 * 캡처한 장중(11:56~11:57 KST) 005930 실시간 수신 283건(0B) + 체결통보 4건(00)으로
 * 필드 매핑을 검증했다. 이전 "문서 기반 추정" 단계는 해소됐다(TODO Phase 2 해소).
 */
final class RealMessageParser {

    private static final Logger log = LoggerFactory.getLogger(RealMessageParser.class);

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
    static Object parse(JsonNode data, Clock clock) {
        String type = data.path("type").asText("");
        return switch (type) {
            case TYPE_TICK -> parseTick(data, clock);
            case TYPE_ORDER_NOTICE -> parseOrderNotice(data, clock);
            default -> null; // 아직 다루지 않는 실시간 타입 — 조용히 무시
        };
    }

    /**
     * 실측 확정 2026-09-11: FID 10(현재가, 부호 접두 절대값 처리)/15(체결량, 부호는
     * 매수·매도 체결 주체를 뜻하므로 MarketTick.volume은 부호를 벗긴 크기로 정규화)/
     * 20(체결시간 HHMMSS, 당일 KST 기준으로 Instant 변환). FID 13(누적거래량)도 이번
     * 실측으로 확정됐으나 MarketTick에는 누적치를 담을 필드가 없어 아직 사용하지 않는다
     * (필요해지면 MarketTick 확장 또는 별도 이벤트로 다룰 것).
     */
    private static MarketTick parseTick(JsonNode data, Clock clock) {
        String rawSymbol = data.path("item").asText("").trim();
        JsonNode values = data.path("values");
        String priceRaw = values.path("10").asText("");
        if (rawSymbol.isEmpty() || priceRaw.isEmpty()) {
            return null;
        }
        if (!rawSymbol.matches(StockCode.PATTERN)) {
            // 시세는 초당 여러 건이라 WARN이면 로그가 넘친다. 소비자는 감사 기록·trace 로그뿐이라 버려도 매매 영향이 없다
            log.debug("시세 종목코드 형식 오류 — 버림: '{}'", rawSymbol);
            return null;
        }
        StockCode symbol = new StockCode(rawSymbol);
        // 키움 REST/WS는 등락 부호(+/-)를 숫자 앞에 붙여 보내는 경우가 있어 제거 후 파싱한다.
        // 부호 정규화는 KiwoomNumbers로 공통화했다(market REST 파서와 중복 제거, PLAN ADR-5).
        BigDecimal price = KiwoomNumbers.toBigDecimal(priceRaw);
        if (price.signum() <= 0) {
            // 가격 0인 시세는 쓸 데가 없다(소비자는 감사 기록뿐). 종목코드 형식 오류와 같이 DEBUG로 버린다
            log.debug("시세 가격 0 — 버림: {} '{}'", symbol, priceRaw);
            return null;
        }
        // FID 15(체결량)도 같은 이유로 부호가 붙는다(+매수 주도/-매도 주도) — MarketTick.volume은
        // 방향이 아니라 크기만 의미하므로 부호를 벗긴다.
        long volume = KiwoomNumbers.toLongOrZero(values.path("15").asText(""));
        Instant timestamp = parseTickTimestamp(values.path("20").asText(""), clock);
        return new MarketTick(symbol, new Price(price), volume, timestamp, MarketTick.Source.LIVE);
    }

    /**
     * FID 20(체결시간, HHMMSS)을 당일 KST 자정 기준 Instant로 변환한다.
     * 형식이 없거나 깨진 경우(테스트 픽스처 등 FID 20을 안 보내는 경우 포함)는
     * 수신 시각으로 방어적으로 대체한다.
     */
    private static Instant parseTickTimestamp(String hhmmss, Clock clock) {
        if (hhmmss == null || hhmmss.length() != 6) {
            return clock.instant();
        }
        try {
            int hour = Integer.parseInt(hhmmss.substring(0, 2));
            int minute = Integer.parseInt(hhmmss.substring(2, 4));
            int second = Integer.parseInt(hhmmss.substring(4, 6));
            LocalDate today = LocalDate.now(clock.withZone(MarketConstants.KST));
            return today.atTime(hour, minute, second).atZone(MarketConstants.KST).toInstant();
        } catch (NumberFormatException | java.time.DateTimeException e) {
            return clock.instant();
        }
    }

    /**
     * 실측 확정 2026-09-11: FID 9203(주문번호)/9001(종목코드, "A" 접두 없음)/913(주문상태,
     * "접수"/"체결" 실측)/911(체결량)/910(체결가)/902(미체결 잔량) 매핑을 실제 모의투자
     * 왕복(매수 접수→체결, 매도 접수→체결) 4건으로 검증했다. "취소"/"거부" 상태 문자열은
     * 이번 실측에서 관측되지 않아 여전히 TODO 실측(미확정) — 원문 그대로 보관하고 소비자
     * (OrderNoticeHandler)가 방어적으로 처리한다.
     */
    private static OrderNotice parseOrderNotice(JsonNode data, Clock clock) {
        JsonNode values = data.path("values");
        String rawBrokerOrderId = values.path("9203").asText("").trim();
        if (rawBrokerOrderId.isEmpty()) {
            // 주문번호가 없으면 어떤 주문의 통보인지 알 수 없어 매핑 불가능 — 버린다
            return null;
        }
        if (rawBrokerOrderId.chars().anyMatch(Character::isWhitespace)) {
            log.warn("주문체결통보 주문번호 형식 오류 — 버림: '{}'", rawBrokerOrderId);
            return null;
        }
        BrokerOrderId brokerOrderId = new BrokerOrderId(rawBrokerOrderId);
        // 종목코드는 비거나 형식이 틀려도 통보를 버리지 않는다 — 체결 유실이 더 위험하다(소비자는 DB 주문 종목을 쓴다)
        String rawSymbol = values.path("9001").asText("").trim();
        StockCode symbol = rawSymbol.matches(StockCode.PATTERN) ? new StockCode(rawSymbol) : null;
        String status = values.path("913").asText("");
        long filledQuantity = KiwoomNumbers.toLongOrZero(values.path("911").asText(""));
        Price fillPrice = fillPriceOf(values.path("910").asText(""), rawBrokerOrderId);
        // FID 902(미체결 잔량) — 필드가 아예 없으면(과거 픽스처 등) -1(알 수 없음)로 구분한다.
        // "0"으로 실제로 온 경우와 필드 자체가 없는 경우를 섞으면 안 되기 때문.
        String remainingRaw = values.path("902").asText("");
        long remainingQuantity = remainingRaw.isEmpty() ? -1L : KiwoomNumbers.toLongOrZero(remainingRaw);
        return new OrderNotice(brokerOrderId, symbol, status, filledQuantity, fillPrice,
                remainingQuantity, TYPE_ORDER_NOTICE, clock.instant());
    }

    /**
     * FID 910(누적 평균 체결가) 번역 — 빈 값(접수 통보, 실측)·0은 "체결가 없음"(null)이다. 형식이 깨져도 통보는 살린다:
     * 체결 통보를 잃으면 포지션·손익이 틀어지고, 체결가가 없으면 소비자(OrderNoticeHandler)가 지정가로 근사한다.
     */
    private static Price fillPriceOf(String raw, String brokerOrderId) {
        if (raw.isEmpty()) {
            return null;
        }
        try {
            BigDecimal value = KiwoomNumbers.toBigDecimal(raw);
            return value.signum() > 0 ? new Price(value) : null;
        } catch (NumberFormatException e) {
            log.warn("주문체결통보 체결가 형식 오류 — 체결가 없이 통보만 전달: {} '{}'", brokerOrderId, raw);
            return null;
        }
    }
}
