package com.autostock.common.util;

/**
 * 브로커 주문번호(키움 FID 9203, 예: "0119433"; SIM은 "SIM-1") — ClientOrderId(우리 멱등키)와 섞이지 않게
 * 타입으로 구분한다(ARCHITECTURE.md 3절 1순위). 빈 값과 공백 포함 값은 만들 수 없다.
 */
public record BrokerOrderId(String value) {

    public BrokerOrderId {
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("브로커 주문번호가 비었거나 공백을 포함함: '" + value + "'");
        }
    }

    /** 로그·메시지에 값만 나오게 한다(기존 문자열 로그 형식 유지). */
    @Override
    public String toString() {
        return value;
    }
}
