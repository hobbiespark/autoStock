package com.autostock.ipo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 증권사 이름 맞추기(2026-11-11 사용자 "현재 보유 중인 증권사에서 청약 가능하면 +점수"). DART 인수인정보(actnmn)는 같은 회사를
 * "SK증권"·"에스케이증권", "KB증권"·"케이비증권"처럼 섞어 쓰고, 옛 이름(신한금융투자·하나금융투자·DB금융투자·하이투자증권)도 남아
 * 있다. 비교할 때만 열쇠({@link #key})로 바꾸고, 화면·알림에는 원래 이름을 쓴다.
 */
final class BrokerNames {

    /** 영문 약자를 한글로 읽어 적은 앞머리 → 영문 약자. 긴 것부터 본다. */
    private static final List<Map.Entry<String, String>> SPELLED_PREFIX = List.of(
            Map.entry("아이비케이", "IBK"),
            Map.entry("에스케이", "SK"),
            Map.entry("엔에이치", "NH"),
            Map.entry("비엔케이", "BNK"),
            Map.entry("케이비", "KB"),
            Map.entry("디비", "DB"),
            Map.entry("아이엠", "IM"),
            Map.entry("엘에스", "LS"),
            Map.entry("케이티비", "KTB"));

    /** 옛 이름 → 지금 이름(열쇠 기준). */
    private static final Map<String, String> RENAMED = Map.of(
            "신한금융투자", "신한투자증권",
            "하나금융투자", "하나증권",
            "DB금융투자", "DB증권",
            "하이투자증권", "IM증권",
            "이베스트투자증권", "LS증권",
            "미래에셋대우", "미래에셋증권",
            "KTB투자증권", "다올투자증권",
            "한화투자증", "한화투자증권");

    private BrokerNames() {
    }

    /** 비교용 열쇠 — 공백·괄호·"주식회사" 제거, 영문 대문자, 한글로 읽은 약자·옛 이름 통일. 비면 null. */
    static String key(String name) {
        if (name == null) {
            return null;
        }
        String s = name.replace("주식회사", "").replace("(주)", "").replace("㈜", "")
                .replaceAll("[^0-9A-Za-z가-힣]", "").toUpperCase(Locale.ROOT);
        if (s.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, String> e : SPELLED_PREFIX) {
            if (s.startsWith(e.getKey())) {
                s = e.getValue() + s.substring(e.getKey().length());
                break;
            }
        }
        return RENAMED.getOrDefault(s, s);
    }

    /** 이름 목록의 열쇠 모음(빈 값 제외). */
    static Set<String> keys(Collection<String> names) {
        Set<String> keys = new LinkedHashSet<>();
        if (names != null) {
            names.stream().map(BrokerNames::key).filter(k -> k != null).forEach(keys::add);
        }
        return keys;
    }

    /** 인수인 중 보유 계좌가 있는 곳(원래 이름, 순서 유지). */
    static List<String> held(List<String> underwriters, Set<String> heldKeys) {
        List<String> held = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String u : underwriters) {
            String k = key(u);
            if (k != null && heldKeys.contains(k) && seen.add(k)) {
                held.add(u);
            }
        }
        return held;
    }
}
