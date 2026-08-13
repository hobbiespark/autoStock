package com.autostock.common.util;

import com.autostock.common.event.Side;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 논리 주문 멱등키 — 가독성 있는 포맷으로 UUID를 대체한다 (PLAN.md ADR-6 7절).
 *
 * <p>포맷: {@code 날짜-전략ID-종목코드-매매방향-일련번호}
 * <pre>
 *   20260813-BREAKOUT-005930-BUY-001
 * </pre>
 * 사람이 로그·DB만 보고도 "언제, 어느 전략이, 어느 종목을, 어느 방향으로, 몇 번째로
 * 낸 주문인지"를 바로 읽을 수 있다는 게 UUID 대비 장점이다. 유일성은 이 포맷 자체가
 * 아니라 {@code orders.client_order_id} 컬럼의 DB UNIQUE 제약이 최종 방어선이다
 * (이 클래스는 "읽기 좋은 키"를 만들 뿐, 동시성 충돌 방지는 DB가 한다).
 *
 * <p>전략ID는 항상 대문자로 정규화한다 — 소문자로 낸 전략ID("breakout")와 대문자("BREAKOUT")가
 * 서로 다른 키로 취급되는 걸 막기 위해서다. 일련번호는 3자리 0-padding(001~999) —
 * 하루 999건을 넘는 전략은 없다고 가정한다(RiskProperties.dailyMaxOrders 기본 30).
 */
public record ClientOrderId(String value) {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE; // yyyyMMdd

    /**
     * 파싱용 정규식. 전략ID 구간에 하이픈이 섞여 있어도(예: "TEST-STRATEGY") 뒤쪽
     * 고정 포맷(6자리 종목코드-BUY|SELL-3자리 일련번호)을 기준으로 역산해 잘라낸다.
     * {@code .+}가 탐욕적(greedy)으로 매칭한 뒤 뒤 구간에 맞춰 백트래킹하기 때문에
     * 전략ID에 하이픈이 있어도 올바르게 분리된다.
     */
    private static final Pattern FORMAT = Pattern.compile(
            "^(\\d{8})-(.+)-([0-9A-Za-z]{6})-(BUY|SELL)-(\\d{3})$");

    /** 전략ID로 허용하는 문자 집합 — 대문자·숫자·하이픈·언더스코어. */
    private static final Pattern STRATEGY_ID_CHARS = Pattern.compile("^[A-Z0-9_-]+$");

    public ClientOrderId {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("ClientOrderId 포맷 위반: " + value);
        }
    }

    /**
     * 새 ClientOrderId를 생성한다.
     *
     * @param date       주문 발생일(보통 KST 기준 오늘, {@code MarketConstants.KST} 사용 권장)
     * @param strategyId 전략 식별자(대문자로 정규화됨)
     * @param symbol     종목코드(6자리)
     * @param side       매매 방향
     * @param sequence   당일 일련번호(1~999)
     */
    public static ClientOrderId generate(LocalDate date, String strategyId, String symbol, Side side, int sequence) {
        if (date == null) {
            throw new IllegalArgumentException("date는 null일 수 없다");
        }
        if (symbol == null || symbol.length() != 6) {
            throw new IllegalArgumentException("symbol은 6자리여야 한다: " + symbol);
        }
        if (side == null) {
            throw new IllegalArgumentException("side는 null일 수 없다");
        }
        if (sequence < 1 || sequence > 999) {
            throw new IllegalArgumentException("sequence는 1~999 범위여야 한다: " + sequence);
        }
        String normalizedStrategyId = normalizeStrategyId(strategyId);

        String formatted = "%s-%s-%s-%s-%03d".formatted(
                date.format(DATE_FORMAT), normalizedStrategyId, symbol, side.name(), sequence);
        return new ClientOrderId(formatted);
    }

    private static String normalizeStrategyId(String strategyId) {
        if (strategyId == null || strategyId.isBlank()) {
            throw new IllegalArgumentException("strategyId는 비어있을 수 없다");
        }
        String upper = strategyId.toUpperCase(Locale.ROOT);
        if (!STRATEGY_ID_CHARS.matcher(upper).matches()) {
            throw new IllegalArgumentException(
                    "strategyId는 영문/숫자/하이픈/언더스코어만 허용: " + strategyId);
        }
        return upper;
    }

    /** 문자열을 파싱해 ClientOrderId로 되돌린다. 포맷 위반이면 IllegalArgumentException. */
    public static ClientOrderId parse(String raw) {
        return new ClientOrderId(raw); // 컴팩트 생성자가 포맷 검증을 수행한다
    }

    private Matcher matcher() {
        Matcher m = FORMAT.matcher(value);
        if (!m.matches()) {
            // 컴팩트 생성자를 통과했다면 항상 매칭되어야 한다 — 방어적 코드.
            throw new IllegalStateException("잘못된 ClientOrderId 상태: " + value);
        }
        return m;
    }

    /** 주문 발생일. */
    public LocalDate date() {
        return LocalDate.parse(matcher().group(1), DATE_FORMAT);
    }

    /** 전략 식별자(대문자). */
    public String strategyId() {
        return matcher().group(2);
    }

    /** 종목코드. */
    public String symbol() {
        return matcher().group(3);
    }

    /** 매매 방향. */
    public Side side() {
        return Side.valueOf(matcher().group(4));
    }

    /** 당일 일련번호. */
    public int sequence() {
        return Integer.parseInt(matcher().group(5));
    }

    @Override
    public String toString() {
        return value;
    }
}
