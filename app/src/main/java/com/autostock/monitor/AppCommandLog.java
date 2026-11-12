package com.autostock.monitor;

import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * VALUNIVO 855(공통 표준 03 관측 행, 22 C-01·C-02, 데브 지적 2026-11-13 "플랫폼 및 각 앱 일관성"): 앱이 받은 명령 한 건마다 결과를 한 줄 남긴다.
 * 플랫폼의 {@code Nivo reply} 줄(VALUNIVO 600)과 같은 원칙이다: AI가 데브에게 묻지 않고 로그로 확인할 수 있게 하되,
 * 본문·인자·토큰·답 글은 남기지 않는다. 남기는 것은 경로, 명령 이름(허용 문자만), 결과 코드, 답 글자 수뿐이다.
 * <ul>
 *   <li>플랫폼 전달 입구: {@code 앱 명령 — route=platform command=<ID> request=<요청 ID> outcome=<결과> http=<상태> chars=<답 글자 수>}</li>
 *   <li>앱 봇 직접 명령: {@code 앱 명령 — route=app-bot command=<ID> outcome=<결과> chars=<답 글자 수> notice=<handed|skipped…>}</li>
 * </ul>
 * {@code notice=handed}는 답을 알림기에 넘겼다는 뜻이고 텔레그램 발송 성공을 뜻하지 않는다(발송 실패는 알림기가 따로 남긴다).
 * 글쓰기 앱의 명령 입구도 같은 키를 쓴다. 명령 처리·거래 로직·응답은 이 클래스가 바꾸지 않는다.
 */
final class AppCommandLog {
    private static final Logger log = LoggerFactory.getLogger(AppCommandLog.class);
    private static final Pattern NAME = Pattern.compile("[a-z0-9_]{1,60}");
    private static final Pattern REQUEST = Pattern.compile("[0-9A-Za-z_-]{1,64}");

    private AppCommandLog() {}

    /** 명령 이름은 허용 문자(영소문자·숫자·밑줄)만 그대로 쓴다. 그 밖은 내용 없이 other로 남긴다(입력 글이 로그에 섞이지 않게). */
    static String name(String value) {
        return value != null && NAME.matcher(value).matches() ? value : "other";
    }

    /** 플랫폼이 준 요청 ID. 없으면 -, 허용 문자가 아니면 other. */
    static String request(String value) {
        if (value == null || value.isBlank()) return "-";
        return REQUEST.matcher(value).matches() ? value : "other";
    }

    static void platform(String commandId, String requestId, String outcome, int http, String replyText) {
        log.info("앱 명령 — route=platform command={} request={} outcome={} http={} chars={}",
                name(commandId), request(requestId), outcome, http, replyText == null ? 0 : replyText.length());
    }

    static void direct(String command, String outcome, String replyText, String notice) {
        log.info("앱 명령 — route=app-bot command={} outcome={} chars={} notice={}",
                name(command), outcome, replyText == null ? 0 : replyText.length(), notice);
    }
}
