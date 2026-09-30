package com.autostock.common.util;

import java.util.regex.Pattern;

/**
 * 종목코드 — 영숫자 대문자 6자리(예: "005930", 2024년 이후 "0126Z0" 같은 영문 포함 코드). 키움 응답의
 * "A" 접두사는 어댑터가 벗긴 뒤 만든다(ARCHITECTURE.md 3절 1순위). 형식이 틀린 값은 만들 수 없다.
 */
public record StockCode(String value) {

    /** 형식 규칙의 단일 원천 — 요청 DTO의 {@code @Pattern}도 이 상수를 참조한다. */
    public static final String PATTERN = "[0-9A-Z]{6}";

    private static final Pattern FORMAT = Pattern.compile(PATTERN);

    public StockCode {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("종목코드 형식 위반(영숫자 대문자 6자리): '" + value + "'");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
