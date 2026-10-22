package com.autostock.ipo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 증권신고서·투자설명서 본문에서 <b>상장 직후 유통가능물량 비율</b>과 상장예정주식수를 읽는다(2026-10-15, 공모주 청약 추천 전략 —
 * aiDoc/ipo-subscription-strategy.md). 유통가능물량이 많을수록 상장일 매도 압력이 크다(전략 점수 F).
 *
 * <p>실측 표현(2025-04~2026-10 공모주 85곳의 투자설명서·증권신고서, data/ipo/history/float) — 회사·주관사마다 다르다.
 * <ol>
 *   <li>표: "상장일 유통가능 7,944,478 32.05%" (「상장 후 시점별 유통가능 주식수 현황」 첫 행)</li>
 *   <li>"…제외한 주식수 2,146,145주( 37.15% )는 상장 직후 시장에서 유통가능한 물량" — 괄호 안에 "공모 후"·"상장 후 지분율"·
 *       "공모 후 기준 약"이 붙기도 한다</li>
 *   <li>"…주식수 15,306,040주는 상장 직후 시장에서 유통가능한 물량이며, 상장예정주식수 기준으로 37.14% 에 해당"</li>
 *   <li>"상장예정주식수 14,859,445주 중 약 15.15%에 해당하는 2,250,787주는 상장 직후 유통가능물량"</li>
 *   <li>"상장 직후 유통가능주식 비율이 20.77%" (공모가 산정 근거 문단)</li>
 * </ol>
 * 기재정정 문서에는 "정정 전" 문장이 함께 남는다(니어스랩: 정정 전 37.19%, 본문 37.15%). 그래서 모든 문장을 모아 <b>가장 많이 나온
 * 비율</b>을 쓴다(같으면 뒤에 나온 것 — 정정 전 문장은 문서 앞쪽 정정 표에 있다).
 */
final class FloatSharesParser {

    private static final String NUM = "([0-9][0-9,]*)";
    private static final String PCT = "([0-9]{1,2}(?:\\.[0-9]+)?)\\s*%";
    private static final List<Pattern> SHARES_THEN_PCT = List.of(
            // 1. 표 첫 행
            Pattern.compile("상장\\s*일\\s*유통\\s*가능\\s*(?:주식\\s*수?)?\\s*" + NUM + "\\s*주?\\s*" + PCT),
            // 2. M주(…P%)는 상장 직후
            Pattern.compile(NUM + "\\s*주\\s*\\(\\s*[^()0-9%]{0,16}" + PCT + "\\s*\\)\\s*[는은]?\\s*상장\\s*직후"),
            // 3. M주는 상장 직후 … 상장예정주식수 기준으로 P%
            Pattern.compile(NUM + "\\s*주[는은]\\s*상장\\s*직후.{0,40}?유통\\s*가능.{0,40}?상장\\s*예정\\s*주식\\s*수\\s*(?:기준으로|대비|의)\\s*(?:약\\s*)?" + PCT));
    // 4. P%에 해당하는 M주는 상장 직후 유통가능
    private static final Pattern PCT_THEN_SHARES = Pattern.compile(
            PCT + "\\s*에?\\s*해당하는\\s*(?:보통주\\s*)?" + NUM + "\\s*주[는은이가]?\\s*(?:상장\\s*)?(?:직후|후|당일)?.{0,20}?유통\\s*가능");
    // 5. 상장 직후 유통가능주식 비율이 P%
    private static final Pattern RATIO_ONLY = Pattern.compile(
            "상장\\s*직후\\s*유통\\s*가능\\s*(?:주식|물량)?\\s*(?:수\\s*)?(?:비율|비중)\\s*[은는이가]?\\s*(?:약\\s*)?" + PCT);
    private static final Pattern LISTED = Pattern.compile(
            "상장\\s*예정\\s*주식\\s*수\\s*(?:\\([^)]{0,40}\\))?\\s*(?:은|는|:)?\\s*(?:총\\s*)?(?:보통주\\s*)?([0-9]{1,3}(?:,[0-9]{3})+)\\s*주");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final BigDecimal MIN_PCT = new BigDecimal("1");
    private static final BigDecimal MAX_PCT = new BigDecimal("95");

    /**
     * @param ratio        상장 직후 유통가능물량 ÷ 상장예정주식수(0~1, 소수 넷째 자리)
     * @param floatShares  유통가능 주식수 — 비율만 적힌 문장이면 null
     * @param listedShares 상장예정주식수 — 못 읽으면 null(유통 주식수와 비율로 거꾸로 셈한 값일 수 있다)
     */
    record FloatShares(BigDecimal ratio, Long floatShares, Long listedShares) {
    }

    private record Hit(BigDecimal pct, Long shares, int position) {
    }

    private FloatSharesParser() {
    }

    /** @param plainText 태그를 벗긴 본문({@link DartDocument#plainText}) — 공백은 여기서 하나로 줄인다 */
    static Optional<FloatShares> parse(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return Optional.empty();
        }
        String text = SPACES.matcher(plainText).replaceAll(" ");
        List<Hit> hits = new ArrayList<>();
        for (Pattern pattern : SHARES_THEN_PCT) {
            Matcher m = pattern.matcher(text);
            while (m.find()) {
                add(hits, pct(m.group(2)), shares(m.group(1)), m.start());
            }
        }
        Matcher m = PCT_THEN_SHARES.matcher(text);
        while (m.find()) {
            add(hits, pct(m.group(1)), shares(m.group(2)), m.start());
        }
        m = RATIO_ONLY.matcher(text);
        while (m.find()) {
            add(hits, pct(m.group(1)), null, m.start());
        }
        if (hits.isEmpty()) {
            return Optional.empty();
        }
        Map<BigDecimal, Integer> counts = new HashMap<>();
        Map<BigDecimal, Hit> last = new HashMap<>();
        for (Hit hit : hits) {
            counts.merge(hit.pct(), 1, Integer::sum);
            Hit before = last.get(hit.pct());
            if (before == null || hit.position() > before.position() || (before.shares() == null && hit.shares() != null)) {
                last.put(hit.pct(), hit.shares() == null && before != null && before.shares() != null
                        ? new Hit(hit.pct(), before.shares(), hit.position()) : hit);
            }
        }
        Hit chosen = null;
        for (Hit hit : last.values()) {
            int c = counts.get(hit.pct());
            if (chosen == null || c > counts.get(chosen.pct())
                    || (c == counts.get(chosen.pct()) && hit.position() > chosen.position())) {
                chosen = hit;
            }
        }
        Long listed = listedShares(text, chosen);
        BigDecimal ratio = chosen.pct().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        return Optional.of(new FloatShares(ratio, chosen.shares(), listed));
    }

    /** 상장예정주식수 — 유통 주식수와 비율에 맞는 값(±1%p)을 고르고, 없으면 유통 주식수 ÷ 비율. */
    private static Long listedShares(String text, Hit chosen) {
        Map<Long, Integer> counts = new HashMap<>();
        Matcher m = LISTED.matcher(text);
        while (m.find()) {
            Long value = shares(m.group(1));
            if (value != null && value > 0) {
                counts.merge(value, 1, Integer::sum);
            }
        }
        Long best = null;
        for (Map.Entry<Long, Integer> e : counts.entrySet()) {
            if (chosen.shares() != null) {
                double implied = 100.0 * chosen.shares() / e.getKey();
                if (Math.abs(implied - chosen.pct().doubleValue()) > 1.0) {
                    continue;
                }
            }
            if (best == null || e.getValue() > counts.get(best)) {
                best = e.getKey();
            }
        }
        if (best == null && chosen.shares() != null && chosen.pct().signum() > 0) {
            best = BigDecimal.valueOf(chosen.shares()).multiply(BigDecimal.valueOf(100))
                    .divide(chosen.pct(), 0, RoundingMode.HALF_UP).longValue();
        }
        return best;
    }

    private static void add(List<Hit> hits, BigDecimal pct, Long shares, int position) {
        if (pct != null && pct.compareTo(MIN_PCT) >= 0 && pct.compareTo(MAX_PCT) <= 0) {
            hits.add(new Hit(pct, shares, position));
        }
    }

    private static BigDecimal pct(String raw) {
        try {
            return new BigDecimal(raw).setScale(2, RoundingMode.HALF_UP);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Long shares(String raw) {
        try {
            return raw == null ? null : Long.parseLong(raw.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
