package com.autostock.ipo;

import com.autostock.common.util.MarketConstants;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 공모주 알림 계획 — 오늘 어떤 회사에 어떤 단계 알림을 보낼지 정한다(2026-10-13, 사용자 "공모주 기능 자동화 강화"). 보내기와 기록은
 * {@link IpoSyncScheduler}가 한다 — 여기는 계산만 해서 시험하기 쉽게 둔다.
 *
 * <p>단계(회사마다, 기준값마다 한 번 — {@code ipo_alert_log} V18). 형식: 단계 — 언제 / 대상 / 기준값.
 * <ul>
 *   <li>NEW — 회사의 첫 딜이 생긴 지 {@value #NEW_WINDOW_DAYS}일 안이고 공모주로 판정됨 / 청약이 끝나지 않은 공모주 / -</li>
 *   <li>METRICS — 수요예측 결과를 DART에서 자동으로 읽어 <b>청약 추천</b>(비례까지·균등만·패스, 2026-10-15)이 정해짐 / 청약이 끝나지 않은
 *       공모주 / [발행조건확정] 접수번호. 문구는 "[청약 추천] …" — 등급·점수·근거·청약 방법을 싣는다</li>
 *   <li>D-1·START — 청약 시작 전날·당일 / <b>청약 권고(RECOMMEND — 비례까지·균등만) 딜만</b>(2026-10-22 사용자 "공모주 청약권고인
 *       것만 청약 시작일에 메시지 전송" — 예전엔 패스·판단 보류 딜에도 나갔다) / 청약 시작일</li>
 *   <li>END — 청약 마지막 날(시작일과 다를 때) / 관심 딜 / 청약 마감일</li>
 *   <li>REFUND — 환불·납입일 / 관심 딜 / 환불일</li>
 *   <li>LISTING-D1·LISTING — 상장 전날·당일(상장일을 알 때) / 관심 딜 / 상장일</li>
 * </ul>
 * 관심 딜 = 권고가 RECOMMEND이거나, 청약·배정 수량을 기록한 딜. 상장사 유상증자는 어떤 알림도 없다. NEW는 한 번에 모아 한 통으로 보낸다
 * (공시 목록을 모든 쪽 읽게 된 첫날 놓쳤던 딜이 한꺼번에 들어와도 알림이 쏟아지지 않게).
 *
 * <p><b>"청약이 끝나지 않았다"</b>({@link #subscriptionOpen}): 청약 마감일을 알면 그것으로 본다. 모르면 예전에는 열린 것으로 봤는데,
 * 2026-10-15 기동 때 오늘 상장하는 멜콘·진코스텍(주요정보가 없어 일정이 빈 딜)에 수요예측 결과 알림이 나갔다. 이제 일정을 모르면
 * [발행조건확정] 신고서 접수일로 판단한다 — 확정 신고서는 청약 며칠 전에 나오므로 {@value #CONFIRMED_FILING_OPEN_DAYS}일이 지났으면
 * 청약은 끝났다. 상장일을 알고 그날이 지났거나 오늘이어도 끝난 것이다.
 */
final class IpoAlertPlanner {

    static final String PHASE_NEW = "NEW";
    static final String PHASE_METRICS = "METRICS";
    static final String PHASE_D_MINUS_1 = "D-1";
    static final String PHASE_START = "START";
    static final String PHASE_END = "END";
    static final String PHASE_REFUND = "REFUND";
    static final String PHASE_LISTING_D_MINUS_1 = "LISTING-D1";
    static final String PHASE_LISTING = "LISTING";

    /** 신규 공모 알림 대상 — 회사의 첫 딜이 생긴 지 이 일수 안. 기능을 처음 켠 날 옛 딜을 몰아 알리지 않는다. */
    static final int NEW_WINDOW_DAYS = 7;
    /** NEW는 회사마다 한 번 — 기준값 자리를 채우는 고정값. */
    static final String NEW_KEY = "-";
    /**
     * 청약 일정을 모를 때 — [발행조건확정] 접수 뒤 이 일수가 지났으면 청약이 끝난 것으로 본다. 확정 신고서는 보통 청약 2~4 영업일 전에
     * 나오고 청약은 이틀이다(10/15 실측: 엘리스그룹 10/6·엠에스바이오 10/7 확정 → 10/15에는 둘 다 청약 끝).
     */
    static final int CONFIRMED_FILING_OPEN_DAYS = 7;

    /**
     * 보낼 알림 한 건.
     *
     * @param key 기준값({@code ipo_alert_log.alert_key})
     */
    record PlannedAlert(String corpCode, String corpName, String phase, String key, String message) {
    }

    /**
     * @param newDeals 모아서 한 통으로 보낼 신규 공모(회사마다 한 줄)
     * @param alerts   한 통씩 보낼 알림
     */
    record Plan(List<PlannedAlert> newDeals, List<PlannedAlert> alerts) {

        boolean isEmpty() {
            return newDeals.isEmpty() && alerts.isEmpty();
        }

        /** 신규 공모 모음 한 통. */
        String newDealsMessage() {
            return "[공모주 신규] %d건 — %s. 수요예측 결과가 나오면 권고와 함께 다시 알린다.".formatted(newDeals.size(),
                    newDeals.stream().map(PlannedAlert::message).collect(Collectors.joining(" · ")));
        }
    }

    private IpoAlertPlanner() {
    }

    /**
     * @param deals 전체 딜(상태·권고를 오늘 기준으로 다시 계산한 뒤)
     * @param sent  이미 보낸 알림 열쇠({@link IpoAlertLogEntity#key})
     */
    static Plan plan(List<IpoDealEntity> deals, LocalDate today, Set<String> sent) {
        Map<String, List<IpoDealEntity>> byCorp = deals.stream()
                .filter(deal -> !deal.isRightsOffering())
                .collect(Collectors.groupingBy(IpoDealEntity::getCorpCode, LinkedHashMap::new, Collectors.toList()));
        List<PlannedAlert> newDeals = new ArrayList<>();
        List<PlannedAlert> alerts = new ArrayList<>();
        for (Map.Entry<String, List<IpoDealEntity>> entry : byCorp.entrySet()) {
            Corp corp = Corp.of(entry.getKey(), entry.getValue());
            planCorp(corp, today, sent, newDeals, alerts);
        }
        return new Plan(List.copyOf(newDeals), List.copyOf(alerts));
    }

    /** 청약이 끝나지 않았는가 — 클래스 설명 "청약이 끝나지 않았다". */
    static boolean subscriptionOpen(LocalDate end, LocalDate listing, String confirmedRceptNo, LocalDate today) {
        if (listing != null && !listing.isAfter(today)) {
            return false;   // 상장일이 오늘이거나 지났다
        }
        if (end != null) {
            return !end.isBefore(today);
        }
        return IpoOfferingClassifier.rceptDate(confirmedRceptNo)
                .map(filed -> !filed.isBefore(today.minusDays(CONFIRMED_FILING_OPEN_DAYS)))
                .orElse(true);   // 확정 신고서도 아직 없으면 수요예측 전 — 열린 것으로 본다
    }

    private static void planCorp(Corp corp, LocalDate today, Set<String> sent, List<PlannedAlert> newDeals,
                                 List<PlannedAlert> alerts) {
        boolean open = subscriptionOpen(corp.end, corp.listing, corp.rep.getMetricsRceptNo(), today);
        if (corp.kind == OfferingKind.IPO && open && !corp.firstCreated.isBefore(today.minusDays(NEW_WINDOW_DAYS))
                && !corp.firstCreated.isAfter(today)) {
            add(newDeals, sent, corp, PHASE_NEW, NEW_KEY, newDealLine(corp));
        }
        if (corp.rep.getMetricsSource() == IpoDealEntity.MetricsSource.DART && corp.rep.getMetricsRceptNo() != null
                && corp.rep.getRecommendation() != IpoRecommendation.PENDING && open) {
            add(alerts, sent, corp, PHASE_METRICS, corp.rep.getMetricsRceptNo(), metricsMessage(corp));
        }
        // 청약 D-1·시작은 청약 권고 딜만(2026-10-22) — 패스 딜은 [청약 추천] 알림에서 이미 "패스"로 알렸다
        if (corp.start != null && corp.rep.getRecommendation() == IpoRecommendation.RECOMMEND) {
            if (corp.start.minusDays(1).isEqual(today)) {
                add(alerts, sent, corp, PHASE_D_MINUS_1, corp.start.toString(), dMinus1Message(corp));
            } else if (corp.start.isEqual(today)) {
                add(alerts, sent, corp, PHASE_START, corp.start.toString(), startMessage(corp));
            }
        }
        if (!corp.interested) {
            return;
        }
        if (corp.end != null && corp.end.isEqual(today) && !corp.end.equals(corp.start)) {
            add(alerts, sent, corp, PHASE_END, corp.end.toString(),
                    "[청약 마감일] %s — 오늘이 청약 마지막 날이다(마감 시각은 증권사마다 다르다). 권고: %s".formatted(
                            corp.name, recommendationText(corp.rep)));
        }
        if (corp.refund != null && corp.refund.isEqual(today)) {
            add(alerts, sent, corp, PHASE_REFUND, corp.refund.toString(),
                    "[환불·납입일] %s — 오늘 배정 결과와 환불금을 확인하고, 배정 수량을 공모주 화면에 기록하세요.".formatted(corp.name));
        }
        if (corp.listing != null) {
            if (corp.listing.minusDays(1).isEqual(today)) {
                add(alerts, sent, corp, PHASE_LISTING_D_MINUS_1, corp.listing.toString(),
                        "[상장 D-1] %s — 내일 %s 상장. 공모가 %s%s".formatted(corp.name, day(corp.listing),
                                priceText(corp.rep), allocationText(corp)));
            } else if (corp.listing.isEqual(today)) {
                add(alerts, sent, corp, PHASE_LISTING, corp.listing.toString(),
                        "[상장일] %s — 오늘 상장. 공모가 %s%s. 매도는 영웅문S#에서 수동.".formatted(
                                corp.label(), priceText(corp.rep), allocationText(corp)));
            }
        }
    }

    private static void add(List<PlannedAlert> into, Set<String> sent, Corp corp, String phase, String key, String message) {
        if (!sent.contains(IpoAlertLogEntity.key(corp.code, phase, key))) {
            into.add(new PlannedAlert(corp.code, corp.name, phase, key, message));
        }
    }

    // ── 문구 ─────────────────────────────────────────────────────────────────────

    private static String newDealLine(Corp corp) {
        String schedule = corp.start != null && corp.end != null
                ? "청약 %s~%s".formatted(day(corp.start), day(corp.end)) : "청약 일정 미정";
        String lead = corp.rep.getLeadManager() == null ? "" : ", " + corp.rep.getLeadManager();
        BigDecimal low = corp.rep.getOfferPriceConfirmed();
        String price = corp.rep.getOfferPriceFinal() != null ? ", 확정 " + won(corp.rep.getOfferPriceFinal())
                : low != null ? ", 희망가 하단 " + won(low) : "";
        return corp.name + "(" + schedule + lead + price + ")";
    }

    private static String metricsMessage(Corp corp) {
        IpoDealEntity rep = corp.rep;
        String schedule = corp.start != null && corp.end != null
                ? " 청약 %s~%s%s.".formatted(day(corp.start), day(corp.end),
                rep.getLeadManager() == null ? "" : ", " + rep.getLeadManager()) : "";
        return "[청약 추천] %s — %s. %s%s".formatted(corp.name, finalPriceText(rep), reasonText(rep), schedule);
    }

    private static String dMinus1Message(Corp corp) {
        IpoDealEntity rep = corp.rep;
        return "[청약 D-1] %s — 내일 %s부터 청약, 공모가 %s, 주관사 %s. 추천: %s".formatted(corp.name, day(corp.start),
                priceText(rep), rep.getLeadManager() == null ? "-" : rep.getLeadManager(), reasonText(rep));
    }

    private static String startMessage(Corp corp) {
        IpoDealEntity rep = corp.rep;
        return "[청약 시작] %s — 청약기간 %s~%s, 공모가 %s. 추천: %s 청약 실행은 영웅문S#에서 수동.".formatted(corp.name,
                day(corp.start), corp.end == null ? "?" : day(corp.end), priceText(rep), reasonText(rep));
    }

    /** 권고 사유에서 출처 꼬리표(" (DART 수요예측 결과 …)"·" (수동 입력)")를 뗀 글 — 알림에는 등급·근거·방법만 싣는다. */
    static String reasonText(IpoDealEntity deal) {
        String reason = deal.getRecommendReason() == null ? recommendationText(deal) : deal.getRecommendReason();
        int source = Math.max(reason.lastIndexOf(" (DART 수요예측 결과"), reason.lastIndexOf(" (수동 입력)"));
        String text = source > 0 ? reason.substring(0, source) : reason;
        return text.endsWith(".") ? text : text + ".";
    }

    /** 확정가가 있으면 "23,500원(확정)", 없으면 "19,500원(신고서 모집가액·확정 전)", 둘 다 없으면 "미확정". */
    static String priceText(IpoDealEntity deal) {
        if (deal.getOfferPriceFinal() != null) {
            return won(deal.getOfferPriceFinal()) + "(확정)";
        }
        return deal.getOfferPriceConfirmed() != null ? won(deal.getOfferPriceConfirmed()) + "(신고서 모집가액·확정 전)" : "미확정";
    }

    private static String finalPriceText(IpoDealEntity deal) {
        if (deal.getOfferPriceFinal() == null) {
            return "확정 공모가 미확인";
        }
        String band = deal.getOfferPriceLow() == null || deal.getOfferPriceHigh() == null ? ""
                : "(희망 %s~%s%s)".formatted(number(deal.getOfferPriceLow()), won(deal.getOfferPriceHigh()),
                Optional.ofNullable(new OfferingPriceParser.OfferingPrice(deal.getOfferPriceFinal(), deal.getOfferPriceLow(),
                        deal.getOfferPriceHigh()).positionInBand()).map(p -> " " + p).orElse(""));
        return "확정 공모가 " + won(deal.getOfferPriceFinal()) + band;
    }

    private static String recommendationText(IpoDealEntity deal) {
        if (deal.getAdviceGrade() != null && !IpoSubscriptionAdvisor.Grade.PENDING.name().equals(deal.getAdviceGrade())) {
            IpoSubscriptionAdvisor.Grade grade = IpoSubscriptionAdvisor.Grade.valueOf(deal.getAdviceGrade());
            return deal.getAdviceScore() == null ? grade.label : "%s(점수 %+d)".formatted(grade.label, deal.getAdviceScore());
        }
        return switch (deal.getRecommendation()) {
            case RECOMMEND -> "RECOMMEND(청약 권고)";
            case SKIP -> "SKIP(청약 비권고)";
            case PENDING -> "PENDING(판단 보류)";
        };
    }

    private static String allocationText(Corp corp) {
        return corp.allocated == null ? "" : ", 배정 %d주(기록 기준)".formatted(corp.allocated);
    }

    static String won(BigDecimal price) {
        return number(price) + "원";
    }

    private static String number(BigDecimal value) {
        return String.format(Locale.ROOT, "%,d", value.setScale(0, RoundingMode.HALF_UP).longValueExact());
    }

    /** 10/15(수) */
    static String day(LocalDate date) {
        return "%d/%d(%s)".formatted(date.getMonthValue(), date.getDayOfMonth(),
                date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN));
    }

    /**
     * 회사 한 곳의 딜 묶음(정정 신고서마다 한 행) — 일정은 상세가 있는 가장 최근 신고서, 나머지는 회사 전체에서 모은다.
     */
    private record Corp(String code, String name, IpoDealEntity rep, LocalDate start, LocalDate end, LocalDate refund,
                        LocalDate listing, String stockCode, OfferingKind kind, LocalDate firstCreated, boolean interested,
                        Integer allocated) {

        static Corp of(String code, List<IpoDealEntity> deals) {
            IpoDealEntity latest = deals.stream().max(Comparator.comparing(IpoDealEntity::getRceptNo)).orElseThrow();
            IpoDealEntity rep = deals.stream().filter(d -> d.getSubscriptionStart() != null)
                    .max(Comparator.comparing(IpoDealEntity::getRceptNo)).orElse(latest);
            LocalDate listing = deals.stream().map(IpoDealEntity::getListingDate).filter(Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElse(null);
            String stockCode = deals.stream().map(IpoDealEntity::getStockCode).filter(Objects::nonNull).findFirst().orElse(null);
            OfferingKind kind = deals.stream().map(IpoDealEntity::getOfferingKind).filter(Objects::nonNull).findFirst().orElse(null);
            LocalDate firstCreated = deals.stream().map(d -> LocalDate.ofInstant(d.getCreatedAt(), MarketConstants.KST))
                    .min(Comparator.naturalOrder()).orElseThrow();
            boolean recorded = deals.stream().anyMatch(d -> positive(d.getAppliedQty()) || positive(d.getAllocatedQty()));
            Integer allocated = deals.stream().map(IpoDealEntity::getAllocatedQty).filter(Objects::nonNull).findFirst().orElse(null);
            return new Corp(code, latest.getCorpName(), rep, rep.getSubscriptionStart(), rep.getSubscriptionEnd(),
                    rep.getRefundDate(), listing, stockCode, kind, firstCreated,
                    rep.getRecommendation() == IpoRecommendation.RECOMMEND || recorded, allocated);
        }

        String label() {
            return stockCode == null ? name : name + "(" + stockCode + ")";
        }

        private static boolean positive(Integer qty) {
            return qty != null && qty > 0;
        }
    }
}
