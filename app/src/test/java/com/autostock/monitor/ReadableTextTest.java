package com.autostock.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.autostock.monitor.view.OrderHistoryItemView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** VALUNIVO 835: 니보·앱 봇 조회 결과의 사람용 글(시각 KST·UTC 병기, 이름표, 숫자 정리). */
class ReadableTextTest {
    private final ReadableText text = new ReadableText(JsonMapper.builder().build());

    @Test
    void orderHistoryShowsKstAndUtcSideBySideWithKoreanLabels() {
        // 데브가 11-12 니보에서 본 첫 줄(submittedAt 2026-11-11T00:02:01.041919Z).
        var order = new OrderHistoryItemView("20261111-ON-CLOSE-089860-BUY-002", "089860", "롯데렌탈", "BUY", 1, 1,
                new BigDecimal("58800.0000"), "FILLED", "ON-CLOSE", Instant.parse("2026-11-11T00:02:01.041919Z"));
        String out = text.render(List.of(order));
        assertEquals("""
                1.
                  주문 ID: 20261111-ON-CLOSE-089860-BUY-002
                  종목 코드: 089860
                  종목명: 롯데렌탈
                  구분: 매수
                  수량: 1
                  체결 수량: 1
                  지정가: 58,800
                  상태: 체결 완료
                  전략: ON-CLOSE
                  접수 시각: 2026-11-11 09:02:01 KST (2026-11-11 00:02:01 UTC)""", out);
        assertFalse(out.contains("Z\""), out);
        assertFalse(out.contains("{"), out);
    }

    @Test
    void timesAcrossMidnightKeepBothDates() {
        // UTC 15:30은 KST 다음 날 00:30이다. 두 날짜를 다 적어 헷갈리지 않게 한다.
        assertEquals("2026-11-12 00:30:00 KST (2026-11-11 15:30:00 UTC)", ReadableText.when("2026-11-11T15:30:00Z"));
        assertEquals("2026-11-11 09:00:00 KST (2026-11-11 00:00:00 UTC)", ReadableText.when("2026-11-11T09:00:00+09:00"));
        assertEquals("not-a-time", ReadableText.when("not-a-time"));
    }

    @Test
    void nestedObjectsNumbersBooleansAndDatesStayReadable() {
        Map<String, Object> value = new LinkedHashMap<>();
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("totalAsset", new BigDecimal("1234567.50"));
        account.put("asOf", Instant.parse("2026-11-11T06:30:00Z"));
        account.put("note", null);
        value.put("account", account);
        value.put("killSwitchEngaged", false);
        value.put("tradeDate", LocalDate.of(2026, 11, 11));
        value.put("avgSlippageBps", 0.30000000000000004);
        value.put("newField", "x");
        String out = text.render(value);
        assertEquals("""
                계좌:
                  총자산: 1,234,567.5
                  기준 시각: 2026-11-11 15:30:00 KST (2026-11-11 06:30:00 UTC)
                  메모: -
                킬스위치 작동: 아니오
                거래일: 2026-11-11
                평균 슬리피지(bp): 0.3
                newField: x""", out);
        assertTrue(ReadableText.number(new BigDecimal("71000")).equals("71,000"));
    }

    @Test
    void everyStatusValueHasAKoreanNameSoNewValuesAreNoticed() {
        // VALUNIVO 848: 상태 enum에 값이 늘면 이 시험이 깨져 이름표를 같이 더하게 한다.
        Map<String, String> status = ReadableText.VALUES.get("status");
        for (var s : com.autostock.trading.OrderStatus.values()) assertTrue(status.containsKey(s.name()), "주문 " + s);
        for (var s : TradingSystemStatus.values()) assertTrue(status.containsKey(s.name()), "운영 " + s);
        for (var s : com.autostock.ipo.IpoStatus.values()) assertTrue(status.containsKey(s.name()), "공모주 " + s);
    }

    @Test
    void conclusionHorizonAndEventTypeAreKoreanAndUnknownValuesStay() {
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("horizon", "MID");
        decision.put("conclusion", "SKIP");
        decision.put("type", "FILL");
        decision.put("status", "NEW_STATE");
        decision.put("strategyId", "ON-CLOSE");
        assertEquals("""
                기간: 중기
                결론: 보류
                종류: 체결
                상태: NEW_STATE
                전략: ON-CLOSE""", text.render(decision));
    }
}
