package com.autostock.common.util;

import java.math.BigDecimal;

/**
 * 키움 REST/WS 응답의 숫자 필드 파싱 공용 유틸.
 *
 * <p>키움은 등락 표시를 위해 가격류 필드에 부호 접두를 붙여 보낸다
 * (예: {@code "+13500"}, {@code "-13500"}). 캔들 OHLC·체결가·체결량 같은 값은
 * 항상 0 이상으로 취급해야 하므로, 어디서 받든 이 클래스로 부호를 벗겨 정규화한다.
 *
 * <p>왜 공통화했나 — marketdata의 {@code KiwoomDailyChartService}(REST 일봉)와
 * {@code RealMessageParser}(WS 실시간)가 각자 같은 로직(부호 제거 → BigDecimal/long
 * 변환)을 중복 구현하고 있었다. 파싱 규칙이 하나라도 어긋나면 두 곳을 따로 고쳐야
 * 하는 문제가 있어 여기 하나로 모은다(PLAN ADR-5).
 */
public final class KiwoomNumbers {

    private KiwoomNumbers() {
        // 순수 정적 유틸리티 — 인스턴스화 불필요
    }

    /**
     * 값 맨 앞의 부호(+/-)만 제거한 문자열을 반환한다. null/빈 값은 빈 문자열로 정규화한다.
     *
     * <p>맨 앞 한 글자만 검사하는 이유: 값 중간에 우연히 '-'가 섞여 있어도(예상치 못한 포맷)
     * 훼손하지 않기 위해서다 — "부호 접두"라는 실제 관측 포맷에만 대응한다.
     */
    public static String stripSign(Object raw) {
        if (raw == null) {
            return "";
        }
        String s = String.valueOf(raw).trim();
        if (s.startsWith("+") || s.startsWith("-")) {
            s = s.substring(1);
        }
        return s;
    }

    /**
     * 부호 접두가 있을 수 있는 값을 BigDecimal로 변환한다. 빈 값이면 0.
     * 형식이 완전히 깨진 값(숫자가 아님)은 방어하지 않고 그대로 {@link NumberFormatException}을
     * 던진다 — 가격 필드는 조용히 0으로 눙치면 오히려 매매 판단을 그르칠 수 있기 때문이다.
     */
    public static BigDecimal toBigDecimal(Object raw) {
        String cleaned = stripSign(raw);
        return cleaned.isEmpty() ? BigDecimal.ZERO : new BigDecimal(cleaned);
    }

    /**
     * 부호 접두가 있을 수 있는 값을 long으로 변환한다(거래량 등). 빈 값이면 0.
     * {@link #toBigDecimal(Object)}와 마찬가지로 형식 오류는 그대로 예외를 던진다.
     */
    public static long toLong(Object raw) {
        String cleaned = stripSign(raw);
        return cleaned.isEmpty() ? 0L : Long.parseLong(cleaned);
    }

    /**
     * {@link #toLong(Object)}의 방어적 버전 — 형식이 깨져 있어도 예외를 던지지 않고 0을 반환한다.
     * WS 실시간 필드처럼 아직 실측으로 확정되지 않은 값(문서 기반 추정 FID)에 사용한다.
     */
    public static long toLongOrZero(Object raw) {
        String cleaned = stripSign(raw);
        if (cleaned.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(cleaned);
        } catch (NumberFormatException e) {
            return 0L; // 예상치 못한 포맷 — 실측 전이므로 방어적으로 0 처리
        }
    }
}
