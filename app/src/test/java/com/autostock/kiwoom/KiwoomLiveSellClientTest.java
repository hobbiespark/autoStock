package com.autostock.kiwoom;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 공모주 상장일 실전 매도 전용 클라이언트(2026-11-05) — 매수 경로 없음, 실전 도메인 고정, 키 없음 거절, 요청·응답 해석. */
class KiwoomLiveSellClientTest {

    @Test
    void 공개_메서드는_매도_잔고_미체결뿐이고_매수는_없다() {
        Set<String> methods = Arrays.stream(KiwoomLiveSellClient.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .map(Method::getName).collect(Collectors.toSet());
        assertEquals(Set.of("create", "hasKeys", "sellLimit", "holding", "outstandingOrders"), methods);
        assertTrue(methods.stream().noneMatch(n -> n.toLowerCase().contains("buy")));
    }

    @Test
    void 실전_도메인을_쓴다() {
        assertEquals("https://api.kiwoom.com", KiwoomLiveSellClient.LIVE_REST_BASE_URL);
    }

    @Test
    void 키가_비면_만들지_않는다_모의_키_폴백_없음() {
        assertThrows(IllegalStateException.class, () -> KiwoomLiveSellClient.create(WebClient.builder(), "", "x",
                new SimpleMeterRegistry(), event -> { }, Clock.systemUTC()));
        assertThrows(IllegalStateException.class, () -> KiwoomLiveSellClient.create(WebClient.builder(), "x", null,
                new SimpleMeterRegistry(), event -> { }, Clock.systemUTC()));
        assertNotNull(KiwoomLiveSellClient.create(WebClient.builder(), "test-live-key", "test-live-secret",
                new SimpleMeterRegistry(), event -> { }, Clock.systemUTC()), "만들 때는 네트워크를 쓰지 않는다");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 지정가_매도는_kt10001_보통_주문이다() {
        KiwoomRestClient rest = mock(KiwoomRestClient.class);
        when(rest.call(eq(TrId.ORDER_SELL), anyString(), anyMap())).thenReturn(Map.of("ord_no", "0123", "return_code", 0));
        KiwoomLiveSellClient client = new KiwoomLiveSellClient(rest);
        assertEquals("0123", client.sellLimit("123450", 5, new BigDecimal("9900")));
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(rest).call(eq(TrId.ORDER_SELL), eq("/api/dostk/ordr"), body.capture());
        assertEquals("0", body.getValue().get("trde_tp"), "지정가(보통) — 시장가 3 아님");
        assertEquals("9900", body.getValue().get("ord_uv"));
        assertEquals("5", body.getValue().get("ord_qty"));
        verify(rest, never()).call(eq(TrId.ORDER_BUY), anyString(), anyMap());
    }

    @Test
    void 잘못된_주문값은_보내지_않는다() {
        KiwoomRestClient rest = mock(KiwoomRestClient.class);
        KiwoomLiveSellClient client = new KiwoomLiveSellClient(rest);
        assertThrows(IllegalArgumentException.class, () -> client.sellLimit("123450", 0, new BigDecimal("9900")));
        assertThrows(IllegalArgumentException.class, () -> client.sellLimit("12345", 1, new BigDecimal("9900")));
        assertThrows(IllegalArgumentException.class, () -> client.sellLimit("123450", 1, new BigDecimal("9900.5")));
        verify(rest, never()).call(any(), anyString(), anyMap());
    }

    @Test
    void 잔고와_미체결을_종목별로_읽는다() {
        KiwoomRestClient rest = mock(KiwoomRestClient.class);
        when(rest.call(eq(TrId.ACCOUNT_BALANCE), anyString(), anyMap())).thenReturn(Map.of("acnt_evlt_remn_indv_tot", List.of(
                Map.of("stk_cd", "A005930", "rmnd_qty", "000000000000003", "trde_able_qty", "3"),
                Map.of("stk_cd", "A123450", "rmnd_qty", "000000000000010", "trde_able_qty", "000000000000006"))));
        when(rest.call(eq(TrId.OUTSTANDING_ORDERS), anyString(), anyMap())).thenReturn(Map.of("oso", List.of(
                Map.of("stk_cd", "123450", "ord_no", "0000101", "oso_qty", "4"),
                Map.of("stk_cd", "005930", "ord_no", "0000102", "oso_qty", "1"))));
        KiwoomLiveSellClient client = new KiwoomLiveSellClient(rest);
        assertEquals(new KiwoomLiveSellClient.Holding(10, 6), client.holding("123450"));
        assertEquals(new KiwoomLiveSellClient.Holding(0, 0), client.holding("999990"));
        assertEquals(List.of(new KiwoomLiveSellClient.OpenOrder("0000101", 4)), client.outstandingOrders("123450"));
    }
}
