package com.autostock.kiwoom;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 키움 REST 오류코드 분류 — 공식 스펙(오류코드 37종) 중 재시도·알림 판단에 쓰는 것만 모은다
 * (Phase 0.6, aiDoc/kiwoom-error-codes.md, 조사 upgrade-2026-10/07 §1-2·§1-8).
 *
 * <p>코드는 메시지 안에 {@code [8005:Token이 유효하지 않습니다]}처럼 대괄호로 온다(HTTP 오류 본문의 return_msg,
 * HTTP 200 논리 오류의 return_msg 모두). api-id 표기 {@code [ka10081]}와 섞이지 않게 "숫자 4자리 + 콜론/닫는 괄호"만 본다.
 */
final class KiwoomErrorCodes {

    /** 유량 초과 — 서버가 처리하지 않고 거절했으므로 재전송해도 중복이 없다: 1700(TR), 1701(전체 총유량), 1702(그룹). */
    static final Set<String> RATE_LIMIT = Set.of("1700", "1701", "1702");

    /**
     * 토큰 거절 — 인증 단계 거절이라 요청이 처리되지 않았다. 캐시 토큰을 버리고 새 토큰으로 1회 재시도한다:
     * 8005(토큰 무효), 8010(토큰 발급 IP ≠ 요청 IP — 공인 IP가 바뀐 경우 새 IP에서 재발급하면 풀린다).
     */
    static final Set<String> TOKEN_REJECTED = Set.of("8005", "8010");

    /**
     * 재시도로 풀리지 않는 인증 실패 — 사람이 조치해야 한다(P1 알림): 8001/8002(App Key·Secret 검증 실패),
     * 8010(재발급 뒤에도 남으면 — 새 IP 미등록), 8030/8031(실전·모의 구분 불일치), 8040/8050/8103(단말기·허용 IP 인증 실패).
     */
    static final Set<String> AUTH_FAILURE = Set.of("8001", "8002", "8010", "8030", "8031", "8040", "8050", "8103");

    private static final Pattern CODE = Pattern.compile("\\[(\\d{4})[:\\]]");

    private KiwoomErrorCodes() {
    }

    /** 텍스트에서 {@code within}에 속한 첫 오류코드. */
    static Optional<String> find(String text, Set<String> within) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher matcher = CODE.matcher(text);
        while (matcher.find()) {
            if (within.contains(matcher.group(1))) {
                return Optional.of(matcher.group(1));
            }
        }
        return Optional.empty();
    }

    /** 키움 응답 오류({@link KiwoomApiException})이고 메시지에 {@code codes} 중 하나가 있는가. */
    static boolean has(Throwable e, Set<String> codes) {
        return e instanceof KiwoomApiException && find(e.getMessage(), codes).isPresent();
    }
}
