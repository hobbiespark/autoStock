package com.autostock.ipo;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 증권사 이름 맞추기(2026-11-11) — DART 인수인정보의 표기와 사용자가 적은 보유 증권사를 같은 열쇠로. */
class BrokerNamesTest {

    @Test
    void 한글로_읽은_약자와_옛_이름을_같은_증권사로_본다() {
        assertEquals("SK증권", BrokerNames.key("에스케이증권"));
        assertEquals("SK증권", BrokerNames.key("SK증권"));
        assertEquals("KB증권", BrokerNames.key("케이비증권"));
        assertEquals("KB증권", BrokerNames.key("kb 증권"));
        assertEquals("NH투자증권", BrokerNames.key("엔에이치투자증권"));
        assertEquals("신한투자증권", BrokerNames.key("신한금융투자(주)"));
        assertEquals("하나증권", BrokerNames.key("하나금융투자"));
        assertEquals("IM증권", BrokerNames.key("하이투자증권"));
        assertEquals("한화투자증권", BrokerNames.key("한화투자증"), "줄여 적은 이름도");
        assertNull(BrokerNames.key(" "));
    }

    @Test
    void 인수인_중_보유_계좌를_원래_이름으로_고른다() {
        Set<String> held = BrokerNames.keys(List.of("SK증권", "키움증권", "KB증권"));
        assertEquals(List.of("에스케이증권", "케이비증권"),
                BrokerNames.held(List.of("엔에이치투자증권", "에스케이증권", "케이비증권", "SK증권"), held));
        assertEquals(List.of(), BrokerNames.held(List.of("엔에이치투자증권"), held));
    }
}
