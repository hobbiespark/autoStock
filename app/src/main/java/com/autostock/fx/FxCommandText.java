package com.autostock.fx;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 외화 입력 명령의 글 해석 — 순수 함수. 앱 봇({@code /autostock_fx_reserve USD 1330 40000 1360})과 니보 경유(사용자 글 그대로)
 * 모두 같은 글을 받으므로 순서대로 놓인 통화·숫자·날짜·번호를 뽑는다(설계 9장).
 *
 * <ul>
 *   <li>통화: {@code USD}·{@code JPY}·달러·엔(대소문자 무시)</li>
 *   <li>숫자: 쉼표·끝의 "원"을 지운 뒤 정수 또는 소수</li>
 *   <li>날짜: {@code 2026-12-31}</li>
 *   <li>번호: 예약 {@code R12}, 직접매수 {@code B3}</li>
 * </ul>
 */
final class FxCommandText {

    private static final Pattern NUMBER = Pattern.compile("^\\d+(\\.\\d+)?$");
    private static final Pattern DATE = Pattern.compile("^20\\d{2}-[01]\\d-[0-3]\\d$");
    private static final Pattern REFERENCE = Pattern.compile("^([RrBb])(\\d+)$");

    /** 예약(R) 또는 직접매수(B) 번호. */
    record Reference(char kind, long id) {}

    record Parsed(FxCurrency currency, List<BigDecimal> numbers, LocalDate date, Reference reference) {}

    private FxCommandText() {
    }

    static Parsed parse(String text) {
        FxCurrency currency = null;
        List<BigDecimal> numbers = new ArrayList<>();
        LocalDate date = null;
        Reference reference = null;
        for (String raw : (text == null ? "" : text).strip().split("\\s+")) {
            String token = raw.strip();
            if (token.isEmpty()) {
                continue;
            }
            FxCurrency c = currencyOf(token);
            if (c != null && currency == null) {
                currency = c;
                continue;
            }
            Matcher ref = REFERENCE.matcher(token);
            if (ref.matches() && reference == null) {
                reference = new Reference(Character.toUpperCase(ref.group(1).charAt(0)), Long.parseLong(ref.group(2)));
                continue;
            }
            if (DATE.matcher(token).matches() && date == null) {
                try {
                    date = LocalDate.parse(token);
                    continue;
                } catch (DateTimeParseException ignored) {
                    // 숫자도 날짜도 아닌 낱말로 버린다
                }
            }
            String number = token.replace(",", "");
            if (number.endsWith("원")) {
                number = number.substring(0, number.length() - 1);
            }
            if (NUMBER.matcher(number).matches()) {
                numbers.add(new BigDecimal(number));
            }
        }
        return new Parsed(currency, List.copyOf(numbers), date, reference);
    }

    private static FxCurrency currencyOf(String token) {
        String t = token.toUpperCase(Locale.ROOT);
        if (t.equals("USD") || t.equals("달러") || t.equals("달러화")) {
            return FxCurrency.USD;
        }
        if (t.equals("JPY") || t.equals("엔") || t.equals("엔화")) {
            return FxCurrency.JPY;
        }
        return null;
    }
}
