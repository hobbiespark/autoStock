package com.autostock.monitor;

import com.autostock.common.util.MarketConstants;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * VALUNIVO 835(데브 11-12 "로케일 적용 안된거 같다", "함께.병기"): 니보·앱 봇에 돌려주는 조회 결과를 사람이 읽는 줄 글로 만든다.
 * JSON 원문 대신 한국어 이름표를 붙이고, 시각은 KST와 UTC를 나란히 쓰고({@code 2026-11-11 09:02:01 KST (2026-11-11 00:02:01 UTC)}),
 * 숫자는 끝의 0을 떼고 천 단위 쉼표를 넣는다. 앱 응답의 글(text)은 사람용이고 기계용 계약 칸·DB·화면 API는 그대로 UTC다(BE BC-12).
 * 모르는 칸은 원래 이름 그대로 보여 준다(새 칸이 생겨도 사라지지 않게).
 */
final class ReadableText {
    static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** ISO-8601 시각(시간대 있음). 날짜만 있는 값({@code LocalDate})은 바꾸지 않는다. */
    static final Pattern ISO_INSTANT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})");

    /** 칸 이름 → 한국어 이름표. 화면(대시보드)의 같은 칸 이름과 맞춘다. */
    static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("clientOrderId", "주문 ID"), Map.entry("symbol", "종목 코드"), Map.entry("symbolName", "종목명"),
            Map.entry("name", "종목명"), Map.entry("side", "구분"), Map.entry("quantity", "수량"),
            Map.entry("filledQuantity", "체결 수량"), Map.entry("limitPrice", "지정가"), Map.entry("status", "상태"),
            Map.entry("strategyId", "전략"), Map.entry("submittedAt", "접수 시각"), Map.entry("decidedAt", "판단 시각"),
            Map.entry("horizon", "기간"), Map.entry("conclusion", "결론"), Map.entry("reason", "이유"),
            Map.entry("available", "조회 가능"), Map.entry("totalAsset", "총자산"), Map.entry("holdingsValue", "보유 평가액"),
            Map.entry("holdingsCost", "보유 원가"), Map.entry("holdingsPnl", "보유 평가손익"), Map.entry("asOf", "기준 시각"),
            Map.entry("note", "메모"), Map.entry("capitalLimitKrw", "투자 한도(원)"), Map.entry("capitalHeldCost", "투자 중 원가"),
            Map.entry("capitalPendingBuy", "미체결 매수액"), Map.entry("avgPrice", "평균 단가"), Map.entry("currentPrice", "현재가"),
            Map.entry("evaluationPnl", "평가손익"), Map.entry("returnPct", "수익률(%)"), Map.entry("killSwitchEngaged", "킬스위치 작동"),
            Map.entry("todayOrderCount", "오늘 주문 수"), Map.entry("todayRealizedPnl", "오늘 실현손익"),
            Map.entry("conservativeMode", "보수 모드"), Map.entry("disclosureBlacklistCount", "공시 제외 종목 수"),
            Map.entry("dailyLossLimit", "일일 손실 한도"), Map.entry("tradeDate", "거래일"), Map.entry("realizedPnl", "실현손익"),
            Map.entry("orderCount", "주문 수"), Map.entry("fillCount", "체결 수"), Map.entry("avgSlippageBps", "평균 슬리피지(bp)"),
            Map.entry("maxSlippageBps", "최대 슬리피지(bp)"), Map.entry("type", "종류"), Map.entry("summary", "내용"),
            Map.entry("at", "시각"), Map.entry("account", "계좌"), Map.entry("trading", "거래 상태"),
            Map.entry("openPrice", "시가"), Map.entry("highPrice", "고가"), Map.entry("lowPrice", "저가"),
            Map.entry("bestAsk", "매도 1호가"), Map.entry("bestBid", "매수 1호가"));
    static final Map<String, String> SIDES = Map.of("BUY", "매수", "SELL", "매도");

    /**
     * VALUNIVO 848: 칸별 코드 값 → 한국어. 화면에 이름표가 있는 값(결론 {@code DecisionConclusionBadge}, 기간 {@code DecisionsPage},
     * 공모주 {@code IpoPage})은 화면 낱말을 그대로 쓴다. {@code status}는 주문(OrderStatus 11)·운영(TradingSystemStatus 6)·
     * 공모주(IpoStatus) 값이 서로 겹치지 않아 한 표로 둔다. 모르는 값은 원래 값 그대로.
     */
    static final Map<String, Map<String, String>> VALUES = Map.of(
            "side", SIDES,
            "status", Map.ofEntries(
                    Map.entry("CREATED", "생성"), Map.entry("VALIDATED", "검증 통과"), Map.entry("SUBMITTING", "전송 중"),
                    Map.entry("SUBMITTED", "접수"), Map.entry("ACCEPTED", "접수 확인"), Map.entry("PARTIALLY_FILLED", "일부 체결"),
                    Map.entry("FILLED", "체결 완료"), Map.entry("CANCEL_REQUESTED", "취소 요청"), Map.entry("CANCELLED", "취소됨"),
                    Map.entry("REJECTED", "거부"), Map.entry("UNKNOWN", "결과 확인 중"),
                    Map.entry("STOPPED", "정지"), Map.entry("STARTING", "시작 중"), Map.entry("RUNNING", "운영 중"),
                    Map.entry("STOPPING", "정지 중"), Map.entry("DEGRADED", "매매 차단(시스템은 동작)"), Map.entry("ERROR", "오류"),
                    Map.entry("UPCOMING", "청약 예정"), Map.entry("SUBSCRIBING", "청약 중"), Map.entry("LISTED", "상장 완료"),
                    Map.entry("PASSED", "청약 종료")),
            "conclusion", Map.of("BUY", "매수", "SELL", "매도", "HOLD", "보유유지", "SKIP", "보류", "REJECTED", "거부",
                    "CARRY", "이월", "WATCH", "관찰"),
            "horizon", Map.of("DAY", "단타", "SWING", "스윙", "MID", "중기", "LONG", "장기", "TEST", "테스트"),
            "type", Map.of("SIGNAL", "신호", "ORDER", "주문", "FILL", "체결"));

    private final JsonMapper json;

    ReadableText(JsonMapper json) {
        this.json = json;
    }

    String render(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, json.valueToTree(value), "", null);
        return out.toString().stripTrailing();
    }

    private void write(StringBuilder out, JsonNode node, String indent, String field) {
        if (node.isArray()) {
            int i = 0;
            for (JsonNode item : node) {
                i++;
                if (item.isObject()) {
                    if (i > 1) out.append('\n');
                    out.append(indent).append(i).append(".\n");
                    write(out, item, indent + "  ", null);
                } else out.append(indent).append("- ").append(scalar(item, field)).append('\n');
            }
            return;
        }
        if (node.isObject()) {
            for (var entry : node.properties()) {
                String name = entry.getKey();
                JsonNode child = entry.getValue();
                String label = LABELS.getOrDefault(name, name);
                if (child.isObject() || (child.isArray() && !child.isEmpty())) {
                    out.append(indent).append(label).append(":\n");
                    write(out, child, indent + "  ", name);
                } else out.append(indent).append(label).append(": ").append(scalar(child, name)).append('\n');
            }
            return;
        }
        out.append(indent).append(scalar(node, field)).append('\n');
    }

    static String scalar(JsonNode node, String field) {
        if (node == null || node.isNull() || node.isMissingNode()) return "-";
        if (node.isArray()) return "없음";
        if (node.isBoolean()) return node.booleanValue() ? "예" : "아니오";
        if (node.isNumber()) return number(node.decimalValue());
        String text = node.asText();
        Map<String, String> values = field == null ? null : VALUES.get(field);
        if (values != null) return values.getOrDefault(text, text);
        return ISO_INSTANT.matcher(text).matches() ? when(text) : text;
    }

    /** {@code 2026-11-11T00:02:01.041919Z} → {@code 2026-11-11 09:02:01 KST (2026-11-11 00:02:01 UTC)}. 읽지 못하면 원문 그대로. */
    static String when(String iso) {
        try {
            Instant at = OffsetDateTime.parse(iso).toInstant();
            return WHEN.format(at.atZone(MarketConstants.KST)) + " KST (" + WHEN.format(at.atOffset(ZoneOffset.UTC)) + " UTC)";
        } catch (DateTimeParseException e) {
            return iso;
        }
    }

    /** 끝의 0을 떼고 천 단위 쉼표(58800.0000 → 58,800, 12.50 → 12.5). 소수 5자리 넘는 값(실수 계산 값)은 둘째 자리로 반올림. */
    static String number(BigDecimal value) {
        BigDecimal plain = value.stripTrailingZeros();
        if (plain.scale() > 4) plain = value.setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
        int scale = Math.max(0, plain.scale());
        DecimalFormat format = new DecimalFormat(scale == 0 ? "#,##0" : "#,##0." + "0".repeat(scale), DecimalFormatSymbols.getInstance(Locale.ROOT));
        return format.format(plain);
    }
}
