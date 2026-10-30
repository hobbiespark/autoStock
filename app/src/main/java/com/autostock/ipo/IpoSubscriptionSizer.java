package com.autostock.ipo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 공모주 청약 금액 추천 — 예산 안에서 몇 주를 청약할지 계산만 한다(2026-10-30 사용자 "청약추천이라면 200만원 예산 내에서 얼마나
 * 청약할지도 추천", aiDoc/ipo-automation.md "2026-10-30 바뀜"). 알림 문구에 붙이는 것은 {@link IpoAlertPlanner}다.
 *
 * <p>규칙(사용자 확정):
 * <ul>
 *   <li>균등만(EQUAL_ONLY) — 최소 청약 수량({@code min-qty})과 그 증거금.</li>
 *   <li>비례까지(PROPORTIONAL) — 청약 기간이 겹치는 다른 추천·스팩 딜의 최소 증거금을 먼저 빼고, 남은 예산 안에서 최대 수량(청약 단위로
 *       내림). 겹치는 비례(비례까지·스팩) 딜이 여럿이면 남은 예산을 똑같이 나눈다.</li>
 *   <li>스팩(SPAC) — 비례까지와 같은 방식(사용자가 스팩 비례 청약으로 10주를 배정받은 사례가 있다). 근거를 문구에 남긴다.</li>
 *   <li>공모가 — 확정가, 없으면 희망 밴드 상단, 그것도 없으면 신고서 모집가액. 확정가가 아니면 "확정가 전"으로 표시한다.</li>
 *   <li>증거금 = 수량 × 공모가 × 증거금률(원 단위 반올림). 증거금률·최소 수량·단위는 주관사마다 달라 "주관사 공고 확인"을 붙인다.</li>
 * </ul>
 */
final class IpoSubscriptionSizer {

    /** 청약 방식 — 추천 등급에서 온다. */
    enum Style {
        EQUAL_ONLY, PROPORTIONAL, SPAC;

        boolean proportional() {
            return this != EQUAL_ONLY;
        }

        /** 청약 추천 등급 이름(advice_grade) → 방식. 금액을 추천하지 않는 등급(패스·판단 보류)이면 비어 있다. */
        static Optional<Style> ofGrade(String grade) {
            if (grade == null) {
                return Optional.empty();
            }
            return switch (grade) {
                case "EQUAL_ONLY" -> Optional.of(EQUAL_ONLY);
                case "PROPORTIONAL" -> Optional.of(PROPORTIONAL);
                case "SPAC" -> Optional.of(SPAC);
                default -> Optional.empty();
            };
        }
    }

    /** 계산에 쓴 공모가의 출처. */
    enum PriceBasis { FINAL, BAND_HIGH, FILING }

    /**
     * 계산 대상 딜(회사 하나).
     *
     * @param start 청약 시작일(모르면 null — 겹침 계산에서 빠진다)
     * @param end   청약 마감일(모르면 시작일과 같다고 본다)
     */
    record Deal(String code, Style style, BigDecimal price, PriceBasis basis, LocalDate start, LocalDate end) {

        /** 등급·가격으로 만든다 — 금액을 추천하지 않는 등급이거나 공모가를 모르면 비어 있다. */
        static Optional<Deal> of(String code, String grade, BigDecimal finalPrice, BigDecimal bandHigh, BigDecimal filingPrice,
                                 LocalDate start, LocalDate end) {
            Optional<Style> style = Style.ofGrade(grade);
            if (style.isEmpty()) {
                return Optional.empty();
            }
            if (positive(finalPrice)) {
                return Optional.of(new Deal(code, style.get(), finalPrice, PriceBasis.FINAL, start, end));
            }
            if (positive(bandHigh)) {
                return Optional.of(new Deal(code, style.get(), bandHigh, PriceBasis.BAND_HIGH, start, end));
            }
            if (positive(filingPrice)) {
                return Optional.of(new Deal(code, style.get(), filingPrice, PriceBasis.FILING, start, end));
            }
            return Optional.empty();
        }

        boolean overlaps(Deal other) {
            if (start == null || other.start == null) {
                return false;
            }
            LocalDate myEnd = end == null ? start : end;
            LocalDate otherEnd = other.end == null ? other.start : other.end;
            return !start.isAfter(otherEnd) && !other.start.isAfter(myEnd);
        }

        private static boolean positive(BigDecimal value) {
            return value != null && value.signum() > 0;
        }
    }

    /**
     * 계산 결과.
     *
     * @param qty          추천 청약 수량(주)
     * @param margin       증거금(원)
     * @param reservedOthers 겹치는 다른 딜의 최소 증거금 합(원)
     * @param overlapping  청약 기간이 겹치는 다른 추천·스팩 딜 수
     * @param sharedWith   남은 예산을 나눈 비례 딜 수(자기 포함, 균등만이면 0)
     * @param overBudget   이 딜과 겹치는 딜의 증거금 합이 예산을 넘는가
     */
    record Sizing(Deal deal, int qty, BigDecimal margin, BigDecimal reservedOthers, int overlapping, int sharedWith,
                  boolean overBudget) {
    }

    private final IpoSubscriptionProperties properties;

    IpoSubscriptionSizer(IpoSubscriptionProperties properties) {
        this.properties = properties;
    }

    /**
     * @param target 계산할 딜
     * @param others 다른 추천·스팩 딜 전부(겹치는지는 여기서 본다 — target 자신이 섞여 있어도 코드로 뺀다)
     */
    Sizing size(Deal target, List<Deal> others) {
        BigDecimal unitMargin = target.price().multiply(properties.marginRate());
        List<Deal> overlapping = others.stream()
                .filter(d -> !d.code().equals(target.code()) && target.overlaps(d)).toList();
        BigDecimal reserved = overlapping.stream().map(this::minMargin).reduce(BigDecimal.ZERO, BigDecimal::add);
        int qty = properties.minQty();
        int sharedWith = 0;
        boolean overBudget;
        if (target.style().proportional()) {
            sharedWith = 1 + (int) overlapping.stream().filter(d -> d.style().proportional()).count();
            BigDecimal leftover = properties.budgetKrw().subtract(reserved).subtract(minMargin(target));
            overBudget = leftover.signum() < 0;
            if (!overBudget) {
                BigDecimal share = leftover.divide(BigDecimal.valueOf(sharedWith), 0, RoundingMode.DOWN);
                long extraUnits = share.divide(unitMargin.multiply(BigDecimal.valueOf(properties.unitQty())), 0,
                        RoundingMode.DOWN).longValueExact();
                qty = Math.toIntExact(properties.minQty() + extraUnits * properties.unitQty());
            }
        } else {
            overBudget = reserved.add(minMargin(target)).compareTo(properties.budgetKrw()) > 0;
        }
        BigDecimal margin = round(unitMargin.multiply(BigDecimal.valueOf(qty)));
        return new Sizing(target, qty, margin, reserved, overlapping.size(), sharedWith, overBudget);
    }

    private BigDecimal minMargin(Deal deal) {
        return round(deal.price().multiply(properties.marginRate()).multiply(BigDecimal.valueOf(properties.minQty())));
    }

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP);
    }

    /**
     * 알림 문구 — "추천 청약: N주 · 증거금 약 M원(예산 200만 원 중) — 근거. 가격 기준 · 가정, 주관사 공고 확인."
     */
    String text(Sizing sizing) {
        Deal deal = sizing.deal();
        String head = "추천 청약: %s주 · 증거금 약 %s(예산 %s 중)".formatted(number(BigDecimal.valueOf(sizing.qty())),
                IpoAlertPlanner.won(sizing.margin()), budgetText());
        String basis = switch (deal.style()) {
            case EQUAL_ONLY -> "균등 최소 수량";
            case PROPORTIONAL -> "비례: " + proportionalBasis(sizing);
            case SPAC -> "스팩 비례: " + proportionalBasis(sizing) + "(스팩 비례 청약으로 10주를 배정받은 사례가 있어 기본을 비례로 둔다)";
        };
        if (sizing.overBudget()) {
            basis += " — 예산 초과: 겹치는 딜 최소 증거금 %s와 이 딜 최소 증거금을 더하면 예산을 넘는다".formatted(
                    IpoAlertPlanner.won(sizing.reservedOthers()));
        }
        String price = switch (deal.basis()) {
            case FINAL -> "확정가 %s 기준".formatted(IpoAlertPlanner.won(deal.price()));
            case BAND_HIGH -> "확정가 전 — 희망 상단 %s 기준".formatted(IpoAlertPlanner.won(deal.price()));
            case FILING -> deal.style() == Style.SPAC
                    ? "신고서 공모가 %s 기준".formatted(IpoAlertPlanner.won(deal.price()))
                    : "확정가 전 — 신고서 모집가액 %s 기준".formatted(IpoAlertPlanner.won(deal.price()));
        };
        return "%s — %s. %s · 증거금률 %s%%·최소 %d주·%d주 단위 가정, 주관사 공고 확인.".formatted(head, basis, price,
                properties.marginRate().multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString(),
                properties.minQty(), properties.unitQty());
    }

    private String proportionalBasis(Sizing sizing) {
        if (sizing.overlapping() == 0) {
            return "청약이 겹치는 추천 딜 없음, 예산 안 최대 수량";
        }
        String shared = sizing.sharedWith() > 1 ? ", 비례 딜 %d곳이 나눔".formatted(sizing.sharedWith()) : "";
        return "청약이 겹치는 딜 %d곳 최소 증거금 %s을 뺀 나머지%s".formatted(sizing.overlapping(),
                IpoAlertPlanner.won(sizing.reservedOthers()), shared);
    }

    /** 예산 — 만 원 단위면 "200만 원", 아니면 "1,234,500원". */
    String budgetText() {
        BigDecimal budget = properties.budgetKrw();
        BigDecimal[] manWon = budget.divideAndRemainder(BigDecimal.valueOf(10_000));
        return manWon[1].signum() == 0 ? number(manWon[0]) + "만 원" : IpoAlertPlanner.won(budget);
    }

    private static String number(BigDecimal value) {
        return String.format(Locale.ROOT, "%,d", value.setScale(0, RoundingMode.HALF_UP).longValueExact());
    }
}
