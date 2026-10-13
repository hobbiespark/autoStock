package com.autostock.ipo;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [발행조건확정]증권신고서(지분증권) 본문에서 확정 공모가와 희망 공모가 밴드를 읽는다(2026-10-13, 사용자 "공모주 기능 자동화 강화").
 *
 * <p>왜 필요한가: 주요정보(estkRs.json)의 모집가액(slprc)은 수요예측 전 희망가 하단이고 [발행조건확정] 뒤에도 바뀌지 않는다 — 청약
 * 알림이 확정가가 아닌 하단 가격을 "공모가"로 보냈다(진코스텍 19,500원 → 실제 확정 23,500원).
 *
 * <p>실측 문장(2026-09~10 [발행조건확정] 9건 — 공모주·스팩 모두):
 * <ul>
 *   <li>확정가: "…협의하여 <b>1주당 확정공모가액을 23,500원으로 (최종) 결정하였습니다</b>" — 신고서 앞쪽 주석과 「확정 공모가액의 결정」에
 *       같은 문장이 나온다. 첫 문장을 쓴다.</li>
 *   <li>밴드: "공모희망가액인 13,800원 ~ 15,800원 중 최저가액인 13,800원 기준" / "희망공모가액 16,500원~19,500원" — 정정 뒤 본문에도
 *       원래 밴드 문장이 남아 있다. 확정 신고서 일부(진코스텍 9/28 둘째 건 등)는 밴드 문장이 없다.</li>
 * </ul>
 * 확정가 문장이 없으면(유상증자·기재정정만 한 신고서) 빈 값이다. 밴드가 이상하면(하단 &gt; 상단 등) 밴드만 버린다.
 */
final class OfferingPriceParser {

    private static final List<Pattern> FINAL_PRICE = List.of(
            Pattern.compile("(?:1\\s*)?주당\\s*확정\\s*공모\\s*가\\s*액?\\s*(?:을|를)\\s*(?:금\\s*)?([0-9][0-9,]*)\\s*원\\s*으로\\s*(?:최종\\s*)?(?:결정|확정)"),
            Pattern.compile("확정\\s*공모\\s*가\\s*액?\\s*(?:을|를)\\s*(?:금\\s*)?([0-9][0-9,]*)\\s*원\\s*으로\\s*(?:최종\\s*)?(?:결정|확정)"));
    private static final Pattern BAND = Pattern.compile(
            "(?:공모\\s*희망|희망\\s*공모)\\s*가\\s*액?\\s*(?:범위|밴드)?\\s*(?:인|은|는|:|\\()?\\s*([0-9][0-9,]*)\\s*원?\\s*[~∼～]\\s*([0-9][0-9,]*)\\s*원");
    /** 밴드 상단이 하단의 이 배수를 넘으면 잘못 읽은 것으로 본다. */
    private static final BigDecimal MAX_BAND_RATIO = new BigDecimal("3");

    /**
     * @param finalPrice 확정 공모가(원)
     * @param bandLow    희망 공모가 하단(모르면 null)
     * @param bandHigh   희망 공모가 상단(모르면 null)
     */
    record OfferingPrice(BigDecimal finalPrice, BigDecimal bandLow, BigDecimal bandHigh) {

        /** 확정가가 밴드의 어디인가 — "상단 초과"·"상단"·"밴드 안"·"하단"·"하단 미만", 밴드를 모르면 null. */
        String positionInBand() {
            if (bandLow == null || bandHigh == null) {
                return null;
            }
            if (finalPrice.compareTo(bandHigh) > 0) {
                return "상단 초과";
            }
            if (finalPrice.compareTo(bandHigh) == 0) {
                return "상단";
            }
            if (finalPrice.compareTo(bandLow) == 0) {
                return "하단";
            }
            return finalPrice.compareTo(bandLow) < 0 ? "하단 미만" : "밴드 안";
        }
    }

    private OfferingPriceParser() {
    }

    /** @param plainText 태그를 벗긴 본문({@link DartDocument#plainText}) */
    static Optional<OfferingPrice> parse(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return Optional.empty();
        }
        BigDecimal finalPrice = null;
        for (Pattern pattern : FINAL_PRICE) {
            Matcher m = pattern.matcher(plainText);
            if (m.find()) {
                finalPrice = number(m.group(1));
                break;
            }
        }
        if (finalPrice == null || finalPrice.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal low = null;
        BigDecimal high = null;
        Matcher band = BAND.matcher(plainText);
        if (band.find()) {
            BigDecimal a = number(band.group(1));
            BigDecimal b = number(band.group(2));
            if (a != null && b != null && a.signum() > 0 && a.compareTo(b) <= 0
                    && b.compareTo(a.multiply(MAX_BAND_RATIO)) <= 0) {
                low = a;
                high = b;
            }
        }
        return Optional.of(new OfferingPrice(finalPrice, low, high));
    }

    private static BigDecimal number(String raw) {
        try {
            return raw == null ? null : new BigDecimal(raw.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
