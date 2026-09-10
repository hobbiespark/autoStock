package com.autostock.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretMaskingTest {

    @Test
    void DART_쿼리스트링_crtfc_key를_마스킹한다() {
        String input = "GET https://opendart.fss.or.kr/api/list.json?crtfc_key=abcdef0123456789&pblntf_ty=C";

        String masked = SecretMasking.mask(input);

        assertFalse(masked.contains("abcdef0123456789"));
        assertTrue(masked.contains("crtfc_key=***"));
        assertTrue(masked.contains("pblntf_ty=C")); // 다른 정상 파라미터는 그대로
    }

    @Test
    void 텔레그램_봇_토큰_URL_경로를_마스킹한다() {
        String input = "404 Not Found from GET https://api.telegram.org/bot123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw/sendMessage";

        String masked = SecretMasking.mask(input);

        assertFalse(masked.contains("AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw"));
        assertTrue(masked.contains("bot***"));
        assertTrue(masked.contains("/sendMessage"));
    }

    @Test
    void Bearer_토큰_헤더값을_마스킹한다() {
        String input = "요청 헤더 값: Bearer eyJhbGciOiJIUzI1NiJ9.abcdefghijklmno";

        String masked = SecretMasking.mask(input);

        assertFalse(masked.contains("eyJhbGciOiJIUzI1NiJ9"));
        assertTrue(masked.contains("Bearer ***"));
    }

    @Test
    void authorization_헤더_전체_표기여도_토큰은_남지_않는다() {
        // "authorization: Bearer <token>" 형태는 일반 key=value 규칙과 Bearer 규칙이 둘 다
        // 대상으로 삼는 중첩 구간이다 — 어느 규칙이 최종 문구를 만들든 원본 토큰만 새지
        // 않으면 된다(정확한 마스킹 표기 형식은 규칙 순서에 따라 달라질 수 있다).
        String input = "요청 헤더: authorization: Bearer eyJhbGciOiJIUzI1NiJ9.abcdefghijklmno";

        String masked = SecretMasking.mask(input);

        assertFalse(masked.contains("eyJhbGciOiJIUzI1NiJ9"));
    }

    @Test
    void JSON_형태의_토큰_필드를_마스킹한다() {
        String input = "{\"expires_dt\":\"20260814121649\",\"token_type\":\"Bearer\",\"return_code\":0,\"token\":\"abcd1234efgh5678\"}";

        String masked = SecretMasking.mask(input);

        assertFalse(masked.contains("abcd1234efgh5678"));
        assertTrue(masked.contains("\"token\":\"***\""));
        assertTrue(masked.contains("\"return_code\":0")); // 민감하지 않은 필드는 유지
    }

    @Test
    void 키움_appkey_appsecret을_마스킹한다() {
        String input = "appkey=AKQWERTYUIOP1234567890, appsecret=ASZXCVBNM0987654321";

        String masked = SecretMasking.mask(input);

        assertFalse(masked.contains("AKQWERTYUIOP1234567890"));
        assertFalse(masked.contains("ASZXCVBNM0987654321"));
        assertTrue(masked.contains("appkey=***"));
        assertTrue(masked.contains("appsecret=***"));
    }

    @Test
    void ClientOrderId는_손대지_않는다() {
        String input = "주문 발행: clientOrderId=20260911-C3-005930-BUY-001, symbol=005930";

        String masked = SecretMasking.mask(input);

        assertEquals(input, masked);
    }

    @Test
    void 시크릿이_없는_일반_URL은_손대지_않는다() {
        String input = "GET https://opendart.fss.or.kr/api/list.json?pblntf_ty=C&bgn_de=20260901&end_de=20260911";

        String masked = SecretMasking.mask(input);

        assertEquals(input, masked);
    }

    @Test
    void null_입력은_그대로_null을_반환한다() {
        assertNull(SecretMasking.mask(null));
    }

    @Test
    void stripQuery는_쿼리스트링을_통째로_제거한다() {
        String url = "https://opendart.fss.or.kr/api/list.json?crtfc_key=abcdef0123456789&pblntf_ty=C";

        assertEquals("https://opendart.fss.or.kr/api/list.json", SecretMasking.stripQuery(url));
    }

    @Test
    void stripQuery는_쿼리가_없으면_그대로_반환한다() {
        String url = "https://opendart.fss.or.kr/api/list.json";

        assertEquals(url, SecretMasking.stripQuery(url));
    }

    @Test
    void sanitizeForLogging은_메시지를_마스킹하면서_스택트레이스는_보존한다() {
        RuntimeException original = new RuntimeException(
                "404 Not Found from GET https://api.telegram.org/bot123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw/getUpdates");

        Throwable sanitized = SecretMasking.sanitizeForLogging(original);

        assertFalse(sanitized.getMessage().contains("AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw"));
        assertTrue(sanitized.getMessage().contains("bot***"));
        assertEquals(original.getStackTrace().length, sanitized.getStackTrace().length);
    }

    @Test
    void sanitizeForLogging은_원인_체인도_재귀적으로_마스킹한다() {
        RuntimeException cause = new RuntimeException("crtfc_key=abcdef0123456789 조회 실패");
        RuntimeException wrapper = new RuntimeException("상위 실패", cause);

        Throwable sanitized = SecretMasking.sanitizeForLogging(wrapper);

        assertFalse(sanitized.getCause().getMessage().contains("abcdef0123456789"));
        assertTrue(sanitized.getCause().getMessage().contains("crtfc_key=***"));
    }

    @Test
    void sanitizeForLogging에_null을_전달하면_null을_반환한다() {
        assertNull(SecretMasking.sanitizeForLogging(null));
    }
}
