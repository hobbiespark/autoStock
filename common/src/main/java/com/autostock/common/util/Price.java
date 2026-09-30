package com.autostock.common.util;

import java.math.BigDecimal;

/**
 * 가격(원) — 항상 양수다(ARCHITECTURE.md 3절 1순위). 0·음수·null은 만들 수 없다. 가격을 모르는 경우는
 * 값 객체가 아니라 호출부가 {@code null}로 표현한다(규칙: 단일 값의 없음은 null).
 *
 * <p>평균단가처럼 원 단위 아래 소수가 생길 수 있어 정수로 제한하지 않는다. 호가 단위 정렬은
 * {@code risk.KrxTickSize}의 일이다.
 *
 * <p><b>같음은 수치로 판단한다</b>: {@link BigDecimal#equals}는 자릿수(scale)까지 비교해
 * {@code 259000}과 {@code 259000.00}을 다르다고 본다. 가격은 수치가 같으면 같은 가격이므로 equals·hashCode를
 * 수치 기준으로 바꿨다. 값 자체는 받은 그대로 보관한다 — JSON 직렬화 결과가 기존 BigDecimal 필드와 같아야 한다.
 */
public record Price(BigDecimal value) {

    public Price {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException("가격은 양수여야 한다: " + value);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Price price && value.compareTo(price.value) == 0;
    }

    @Override
    public int hashCode() {
        return value.stripTrailingZeros().hashCode();
    }

    @Override
    public String toString() {
        return value.toPlainString();
    }
}
