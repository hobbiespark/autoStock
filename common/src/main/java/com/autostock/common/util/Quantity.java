package com.autostock.common.util;

/**
 * 주문·체결 수량(주) — 항상 양수다(ARCHITECTURE.md 3절 1순위). 0과 음수는 만들 수 없다.
 * 누적 체결량처럼 0일 수 있는 값에는 쓰지 않는다.
 */
public record Quantity(long value) {

    public Quantity {
        if (value <= 0) {
            throw new IllegalArgumentException("수량은 양수여야 한다: " + value);
        }
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
