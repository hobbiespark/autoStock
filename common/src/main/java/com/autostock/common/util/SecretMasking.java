package com.autostock.common.util;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 로그로 흘러나갈 수 있는 시크릿(API 키·토큰·비밀번호)을 마스킹하는 순수 유틸리티.
 *
 * <p><b>배경</b>: DART 키는 URL 쿼리({@code crtfc_key=...}), 텔레그램 봇 토큰은 URL 경로
 * ({@code api.telegram.org/bot{token}/...}), 키움 앱키·시크릿·접근토큰은 헤더/바디에
 * 실린다. WebClient 예외 메시지(예: {@code WebClientResponseException.getMessage()}는
 * 호출한 전체 URL을 그대로 포함한다)나 디버그 로그, 스택트레이스를 통해 이 값들이 그대로
 * 로그에 남을 수 있고, 운영 로그를 외부(채팅·이슈)에 공유할 때 유출 위험이 된다.
 *
 * <p><b>심층 방어 2겹</b> 중 소스 지점 방어에 해당한다 — 클라이언트(DartClient,
 * TelegramNotifier 등)의 catch 블록에서 예외를 로그로 남기기 전에 이 유틸을 거친다.
 * 최후 방어선은 로그백 레벨의 {@code SecretMaskingConverter}(app 모듈)이며, 두 계층 모두
 * 여기 정의된 규칙({@link #RULES})을 그대로 재사용해 규칙이 한 곳에서만 관리되게 한다.
 *
 * <p><b>원칙</b>: 마스킹은 최후 방어선일 뿐이다 — 애초에 키를 로그 문자열에 넣지 않는 것이
 * 우선이다. 이 클래스는 그 원칙이 지켜지지 못했을 때의 안전망이다.
 */
public final class SecretMasking {

    private SecretMasking() {
    }

    /** 하나의 마스킹 규칙 — 패턴과, 매칭된 부분을 대체할 치환 문자열. */
    private record Rule(Pattern pattern, String replacement) {
    }

    /**
     * 마스킹 규칙 목록. 순서대로 적용된다. 대소문자를 구분하지 않는다(키 이름이
     * {@code appKey}, {@code APPKEY}, {@code app_key} 등으로 섞여 나타날 수 있어서).
     *
     * <ol>
     *   <li>텔레그램 봇 토큰이 그대로 노출되는 {@code bot<숫자>:<문자열>} URL 경로 세그먼트.</li>
     *   <li>{@code Authorization: Bearer <token>} 헤더 값.</li>
     *   <li>JSON {@code "key":"value"} 형태 — 키움 토큰 발급 응답/요청 바디 등이 그대로
     *       문자열로 로그에 찍히는 경우.</li>
     *   <li>{@code key=value} / {@code key: value} 형태의 쿼리스트링·헤더성 표현 —
     *       DART {@code crtfc_key}, 키움 {@code appkey}/{@code appsecret}/{@code secretkey},
     *       공공데이터포털 {@code serviceKey} 등.</li>
     * </ol>
     */
    // 주의: 순서가 중요하다. "authorization: Bearer xyz..." 같은 문자열에서 값 패턴이
    // 공백을 포함하지 못하는 일반 key=value 규칙(아래)이 먼저 적용되면 "Bearer"만 잡아먹고
    // 뒤에 남은 실제 토큰은 마스킹되지 않은 채 남는다. 그래서 공백을 포함해 전체 토큰을
    // 한 번에 소비하는 Bearer/텔레그램 규칙을 먼저 적용하고, 남은 key=value·JSON 형태를
    // 나중에 처리한다.
    static final List<Rule> RULES = List.of(
            new Rule(
                    Pattern.compile("(?i)bot[0-9]{6,}:[A-Za-z0-9_-]{20,}"),
                    "bot***"),
            new Rule(
                    Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/-]{10,}=*"),
                    "Bearer ***"),
            new Rule(
                    Pattern.compile(
                            "(?i)\"(token|appkey|secretkey|app_secret|access_token|api_key|crtfc_key)\""
                                    + "\\s*:\\s*\"[^\"]+\""),
                    "\"$1\":\"***\""),
            new Rule(
                    Pattern.compile(
                            "(?i)(crtfc_key|appkey|app_key|appsecret|app_secret|secretkey|api_key|apikey"
                                    + "|token|access_token|authorization|password|serviceKey)"
                                    + "\\s*[=:]\\s*[^&\\s\"',}]{6,}"),
                    "$1=***")
    );

    /**
     * 입력 문자열에 담긴 시크릿을 전부 마스킹한다. {@code null}이면 빈 문자열을 반환하지
     * 않고 그대로 {@code null}을 돌려준다(호출부가 null 처리를 하던 관례를 깨지 않기 위해).
     */
    public static String mask(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String result = input;
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(result);
            result = matcher.replaceAll(rule.replacement());
        }
        return result;
    }

    /**
     * 쿼리스트링을 통째로 제거한다({@code ?} 이후 전부 버림) — DART {@code crtfc_key}처럼
     * 키가 쿼리 파라미터에 실리는 URL을 로그에 남길 때, {@link #mask(String)}보다 더 확실히
     * 지운다(파라미터 이름이 규칙에 없는 새 키라도 안전하다).
     */
    public static String stripQuery(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        int idx = url.indexOf('?');
        return idx < 0 ? url : url.substring(0, idx);
    }

    /**
     * 예외를 로그에 남기기 전에 메시지(및 원인 체인의 메시지)를 마스킹한 새 예외로 감싼다.
     * 원본 스택트레이스는 그대로 보존한다(디버깅에 필요) — 마스킹 대상은 메시지 문자열뿐이다
     * (WebClientResponseException처럼 예외 메시지 자체에 호출 URL 전체가 들어가는 경우 대응).
     *
     * <p>원본 예외 타입은 보존하지 않는다(모든 예외 타입을 안전하게 재구성할 수는 없어서) —
     * 로그 목적으로는 마스킹된 메시지 + 스택트레이스면 충분하다.
     */
    public static Throwable sanitizeForLogging(Throwable t) {
        if (t == null) {
            return null;
        }
        Throwable maskedCause = sanitizeForLogging(t.getCause());
        String maskedMessage = mask(String.valueOf(t.getMessage()));
        RuntimeException sanitized = new RuntimeException(
                t.getClass().getSimpleName() + ": " + maskedMessage, maskedCause);
        sanitized.setStackTrace(t.getStackTrace());
        return sanitized;
    }
}
