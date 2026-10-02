package com.autostock.common.util;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 종목명 사전 — 종목코드를 사람에게 보여줄 때 종목명과 함께 쓰기 위한 <b>표시 전용</b> 저장소.
 *
 * <p>사용자 요구(2026-10-02): "종목코드와 종목명은 항상 같이 표시". 텔레그램 알림, 일일 리포트, 대시보드 API,
 * 로그가 모두 {@link #label(String)}로 같은 형식 {@code 삼성전자(005930)}을 쓴다(공시 블랙리스트 알림이 먼저 쓰던
 * "이름(코드)" 형식과 같다). 이름을 모르면 {@code 종목명 미확인(005930)}으로 표시하고, 조회 담당
 * ({@code market.StockNameDirectory} — DB 사전과 키움 ka10001)에 알려 비동기로 채운다.
 *
 * <p><b>매매 판단에는 쓰지 않는다.</b> 리스크·전략은 이름으로 분기하지 않고, 이름이 없어도 매매는 그대로 돈다.
 *
 * <p>정적 저장소로 둔 이유(aiDoc/stock-names.md):
 * <ul>
 *   <li>로그를 남기는 거의 모든 모듈(risk·strategy·execution·portfolio·market·trading·monitor)이 쓴다. 생성자 주입으로
 *       바꾸면 표시 하나 때문에 매매 핵심 클래스의 의존과 테스트 구성이 모두 늘어난다.</li>
 *   <li>common은 순수 자바 모듈이라 스프링 빈을 둘 수 없다.</li>
 *   <li>상태는 "코드 → 이름" 사전 하나뿐이고 스레드 안전하다. 테스트는 {@link #resetForTest()}로 비운다.</li>
 * </ul>
 */
public final class StockNames {

    /** 이름 출처 — 순서가 우선순위다. 높은 출처의 이름은 낮은 출처가 덮지 못한다. */
    public enum Source {
        /** DART 기업명(corp_name) — 공시 블랙리스트. 거래소 약칭과 다를 수 있어 가장 낮다. */
        DART,
        /** 키움 ka10001·kt00018의 {@code stk_nm} — 거래소 종목명. ETF도 있다. */
        KIWOOM
    }

    /** 이름이 새로 생기거나 바뀌면 알린다 — DB 저장 담당({@code market.StockNameDirectory})이 등록한다. */
    @FunctionalInterface
    public interface LearnListener {
        void learned(String code, String name, Source source);
    }

    /** 이름을 모를 때 이름 자리에 쓰는 문구. */
    public static final String UNKNOWN_NAME = "종목명 미확인";

    /** 받아들이는 종목명의 최대 길이 — DB 사전({@code stock_names.name VARCHAR(100)})과 같다. */
    public static final int MAX_NAME_LENGTH = 100;

    private static final Pattern CODE = Pattern.compile(StockCode.PATTERN);
    private static final Map<String, Entry> NAMES = new ConcurrentHashMap<>();
    private static final Consumer<String> NO_MISS = code -> { };
    private static final LearnListener NO_LEARN = (code, name, source) -> { };

    private static volatile Consumer<String> missListener = NO_MISS;
    private static volatile LearnListener learnListener = NO_LEARN;

    private record Entry(String name, Source source) {
    }

    private StockNames() {
    }

    /**
     * 사람에게 보여줄 종목 표기 — {@code 삼성전자(005930)}. 이름을 모르면 {@code 종목명 미확인(005930)}을 돌려주고
     * 조회를 요청한다. 코드가 비어 있으면 빈 문자열, 종목코드 형식이 아니면 받은 값을 그대로 돌려준다
     * (예: 키움의 형식 오류 행 — 종목코드가 아니므로 이름을 붙일 수 없다).
     */
    public static String label(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        String trimmed = code.trim();
        if (!CODE.matcher(trimmed).matches()) {
            return trimmed;
        }
        return format(nameOf(trimmed).orElse(UNKNOWN_NAME), trimmed);
    }

    /** {@link #label(String)}과 같다. */
    public static String label(StockCode code) {
        return code == null ? "" : label(code.value());
    }

    /**
     * 사전의 이름을 우선 쓰고, 없으면 호출자가 가진 이름(예: 공시의 회사명)으로 같은 형식을 만든다.
     * 받은 이름은 DART 출처로 사전에 남긴다(키움 이름이 있으면 덮지 않는다).
     */
    public static String label(String code, String fallbackName) {
        if (code == null || code.isBlank() || !CODE.matcher(code.trim()).matches()) {
            return label(code);
        }
        String trimmed = code.trim();
        Entry known = NAMES.get(trimmed);
        if (known != null) {
            return format(known.name(), trimmed);
        }
        if (isUsableName(fallbackName)) {
            learn(trimmed, fallbackName, Source.DART);
            return format(fallbackName.trim(), trimmed);
        }
        return label(trimmed);
    }

    /** 이름이 있으면 그 이름. 없으면 비어 있고, 종목코드 형식이면 조회를 요청한다. */
    public static Optional<String> nameOf(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String trimmed = code.trim();
        Entry entry = NAMES.get(trimmed);
        if (entry != null) {
            return Optional.of(entry.name());
        }
        if (CODE.matcher(trimmed).matches()) {
            notifyMiss(trimmed);
        }
        return Optional.empty();
    }

    /** {@link #nameOf(String)}과 같다. */
    public static Optional<String> nameOf(StockCode code) {
        return code == null ? Optional.empty() : nameOf(code.value());
    }

    /** 이름이 사전에 있는지 — 조회를 요청하지 않는다. */
    public static boolean isKnown(String code) {
        return code != null && NAMES.containsKey(code.trim());
    }

    /**
     * 이름을 배운다. 출처 우선순위가 같거나 높고 이름이 다를 때만 바꾼다.
     *
     * @return 사전이 바뀌었으면 true — 이때만 저장 청취자에 알린다
     */
    public static boolean learn(String code, String name, Source source) {
        if (!store(code, name, source)) {
            return false;
        }
        try {
            learnListener.learned(code.trim(), name.trim(), source);
        } catch (RuntimeException ignored) {
            // 저장 실패가 표시·매매 흐름을 막지 않는다 — 저장 담당이 자체 로그를 남긴다
        }
        return true;
    }

    /** DB에서 읽은 이름을 올린다 — 이미 저장된 값이므로 저장 청취자에 알리지 않는다. */
    public static void preload(String code, String name, Source source) {
        store(code, name, source);
    }

    /** 이름을 모르는 종목코드를 만났을 때 불린다(조회 요청). null이면 해제. */
    public static void onMiss(Consumer<String> listener) {
        missListener = listener == null ? NO_MISS : listener;
    }

    /** 이름이 새로 생기거나 바뀌면 불린다(DB 저장). null이면 해제. */
    public static void onLearn(LearnListener listener) {
        learnListener = listener == null ? NO_LEARN : listener;
    }

    /** 테스트 전용 — 사전과 청취자를 비운다. */
    public static void resetForTest() {
        NAMES.clear();
        missListener = NO_MISS;
        learnListener = NO_LEARN;
    }

    private static boolean store(String code, String name, Source source) {
        if (code == null || source == null || !isUsableName(name)) {
            return false;
        }
        String trimmedCode = code.trim();
        if (!CODE.matcher(trimmedCode).matches()) {
            return false;
        }
        String trimmedName = name.trim();
        boolean[] changed = {false};
        NAMES.compute(trimmedCode, (key, old) -> {
            if (old != null && (old.source().compareTo(source) > 0
                    || (old.source() == source && old.name().equals(trimmedName)))) {
                return old; // 더 높은 출처의 이름이 있거나, 같은 출처의 같은 이름이다
            }
            changed[0] = true;
            return new Entry(trimmedName, source);
        });
        return changed[0];
    }

    private static boolean isUsableName(String name) {
        return name != null && !name.isBlank() && name.trim().length() <= MAX_NAME_LENGTH;
    }

    private static String format(String name, String code) {
        return name + "(" + code + ")";
    }

    private static void notifyMiss(String code) {
        try {
            missListener.accept(code);
        } catch (RuntimeException ignored) {
            // 조회 요청 실패가 표시를 막지 않는다
        }
    }
}
