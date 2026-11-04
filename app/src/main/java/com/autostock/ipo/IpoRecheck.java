package com.autostock.ipo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 청약일 재판단(2026-11-05 사용자 "청약일에 경쟁률 등을 봇으로 알려주면 재판단 후 추천") — 순수 계산.
 *
 * <p>입력: 텔레그램 {@code /ipo <회사> <경쟁률> [청약건수] [일반물량]}(증권사 청약 화면 값). 추천 <b>등급</b>(비례까지·균등만·패스)은
 * 바꾸지 않는다 — 등급은 청약 전에 알 수 있는 값만으로 정하고(IpoSubscriptionAdvisor), 일반청약 경쟁률로 등급을 바꾸는 규칙은
 * 검증된 적이 없다. 여기서는 <b>배정 예상과 청약 금액</b>만 다시 계산한다(사전 선언, aiDoc/ipo-automation.md 7절):
 * <ul>
 *   <li>균등 예상 배정(계좌당) = (일반물량 ÷ 2) ÷ 청약건수 — 일반물량·청약건수가 있을 때만</li>
 *   <li>비례 경쟁률 ≈ 통합 경쟁률 × 2(일반물량의 절반이 균등이라는 근사), 비례 예상 배정 = 청약 수량 ÷ 비례 경쟁률</li>
 *   <li>추천 수량에서 최소 수량을 뺀 비례 추가분의 기대 배정이 {@link #MEANINGFUL_EXTRA_SHARES}주 미만이면
 *       "균등 최소 수량만 권장", 아니면 "추천 청약 유지"</li>
 * </ul>
 */
final class IpoRecheck {

    /** 비례 추가분이 이 주수 미만이면 증거금을 더 넣을 이유가 약하다. */
    static final BigDecimal MEANINGFUL_EXTRA_SHARES = BigDecimal.ONE;

    private static final Pattern NUMBER = Pattern.compile("[0-9][0-9,]*(\\.[0-9]+)?(:1)?");

    private IpoRecheck() {
    }

    /** 명령에서 읽은 값. 숫자는 뒤에서부터 최대 3개, 나머지가 회사 이름. */
    record Args(String query, BigDecimal rate, Long applicants, Long generalShares) {
    }

    /**
     * {@code /ipo 동원파츠 1,500:1 120000 600000} → Args. 형식이 아니면 빈 값(도움말을 보낸다).
     */
    static Optional<Args> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String body = text.strip();
        if (!body.equals("/ipo") && !body.startsWith("/ipo ")) {
            return Optional.empty();
        }
        List<String> tokens = new ArrayList<>(List.of(body.substring(4).strip().split("\\s+")));
        tokens.removeIf(String::isBlank);
        List<BigDecimal> numbers = new ArrayList<>();
        while (!tokens.isEmpty() && numbers.size() < 3 && NUMBER.matcher(tokens.get(tokens.size() - 1)).matches()) {
            numbers.add(0, number(tokens.remove(tokens.size() - 1)));
        }
        if (tokens.isEmpty() || numbers.isEmpty() || numbers.get(0).signum() <= 0) {
            return Optional.empty();
        }
        Long applicants = numbers.size() > 1 ? positiveLong(numbers.get(1)) : null;
        Long shares = numbers.size() > 2 ? positiveLong(numbers.get(2)) : null;
        return Optional.of(new Args(String.join(" ", tokens), numbers.get(0), applicants, shares));
    }

    /** 회사 이름 비교용 — 띄어쓰기·(주)·주식회사를 빼고 "기업인수목적"을 "스팩"으로. */
    static String normalize(String name) {
        if (name == null) {
            return "";
        }
        return name.toLowerCase(Locale.ROOT)
                .replace("주식회사", "").replace("(주)", "").replace("㈜", "")
                .replace("기업인수목적", "스팩")
                .replaceAll("[\\s·.,()]", "");
    }

    /** 계좌당 균등 예상 배정 주수. 값이 모자라면 빈 값. */
    static Optional<BigDecimal> equalPerAccount(Long generalShares, Long applicants) {
        if (generalShares == null || applicants == null || applicants <= 0) {
            return Optional.empty();
        }
        return Optional.of(BigDecimal.valueOf(generalShares).divide(BigDecimal.TWO, 6, RoundingMode.HALF_UP)
                .divide(BigDecimal.valueOf(applicants), 4, RoundingMode.HALF_UP));
    }

    /** 비례 경쟁률 근사 — 통합 경쟁률 × 2. */
    static BigDecimal proportionalRate(BigDecimal rate) {
        return rate.multiply(BigDecimal.TWO);
    }

    /** 청약 수량 qty의 비례 예상 배정 주수. */
    static BigDecimal proportionalShares(int qty, BigDecimal rate) {
        return BigDecimal.valueOf(qty).divide(proportionalRate(rate), 4, RoundingMode.HALF_UP);
    }

    /** 추천 수량에서 최소 수량을 뺀 비례 추가분이 기대 배정 1주 이상인가 — 아니면 균등 최소 수량만 권장. */
    static boolean extraWorthIt(int recommendedQty, int minQty, BigDecimal rate) {
        if (recommendedQty <= minQty) {
            return false;
        }
        return proportionalShares(recommendedQty - minQty, rate).compareTo(MEANINGFUL_EXTRA_SHARES) >= 0;
    }

    static String shares(BigDecimal v) {
        BigDecimal r = v.compareTo(BigDecimal.TEN) >= 0 ? v.setScale(1, RoundingMode.HALF_UP) : v.setScale(2, RoundingMode.HALF_UP);
        return r.stripTrailingZeros().toPlainString();
    }

    static String rate(BigDecimal v) {
        BigDecimal r = v.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        String whole = String.format(Locale.ROOT, "%,d", r.setScale(0, RoundingMode.DOWN).longValueExact());
        String frac = r.scale() > 0 ? r.toPlainString().substring(r.toPlainString().indexOf('.')) : "";
        return whole + frac + ":1";
    }

    static String count(long v) {
        return String.format(Locale.ROOT, "%,d", v);
    }

    private static BigDecimal number(String token) {
        return new BigDecimal(token.replace(",", "").replace(":1", ""));
    }

    private static Long positiveLong(BigDecimal v) {
        return v.signum() > 0 ? v.setScale(0, RoundingMode.DOWN).longValueExact() : null;
    }
}
