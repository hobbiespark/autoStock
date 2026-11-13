package com.autostock.ipo;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 증권 계좌 개설 요청(2026-11-11 사용자 "보유하지 않은 계좌 증권사는 1개월에 1개씩 개설 요청"). 신규 계좌는 한 번 열면 한동안
 * 다른 계좌를 열 수 없어(단기 다수 계좌 개설 제한) 한 달에 한 곳만 고른다. 계산만 한다 — 보내고 남기는 것은 {@link IpoSyncScheduler}.
 *
 * <p>고르는 순서: 보유 계좌로 청약할 수 없는 회사를 그 회사의 인수 증권사(미보유)마다 센다.
 * <ol>
 *   <li>다가오는 추천 딜(비례까지·균등만·스팩, 청약 마감 전) 수가 많은 증권사</li>
 *   <li>같으면 최근 {@value #RECENT_DAYS}일(청약 시작일 기준) 공모 수가 많은 증권사</li>
 *   <li>같으면 최근 3년, 그다음 5년 공모 수가 많은 증권사(2026-11-13 사용자 "지난 1,3,5년 ipo를 많이 했다던지 하는 근거로 추천", D-29)</li>
 *   <li>같으면 이름순</li>
 * </ol>
 * 지난 공모는 앱이 모은 딜에 DART에서 거꾸로 채운 이력({@link IpoUnderwritingHistoryEntity}, 회사가 겹치면 딜 쪽)을 더해 센다.
 * 1·3·5년 모두 보유 계좌로는 청약할 수 없었던 공모만 센다 — 보유 증권사와 함께 인수한 공모는 새 계좌 없이도 청약할 수 있어 개설 근거가 아니다.
 * 보유 계좌로 못 하는 딜이 하나도 없으면 요청하지 않는다. 마지막 요청일에서 한 달이 지나야 다음 요청을 한다.
 */
final class IpoAccountOpenPlanner {

    /** 알림 기록({@code ipo_alert_log})의 회사 자리 — 회사가 아닌 알림. */
    static final String ACCOUNT_CORP = "ACCOUNT";
    static final String PHASE_ACCOUNT_OPEN = "ACCOUNT-OPEN";
    static final int RECENT_DAYS = 365;
    private static final int LISTED_DEALS = 3;
    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("M/d");

    private IpoAccountOpenPlanner() {
    }

    /** 다음 요청을 보낼 수 있는 첫날 — 마지막 요청이 없으면 null(지금 보낼 수 있음). */
    static LocalDate nextAllowed(LocalDate lastRequest) {
        return lastRequest == null ? null : lastRequest.plusMonths(1);
    }

    /**
     * @param deals       전체 딜
     * @param heldKeys    보유 증권사 열쇠({@link BrokerNames#keys}) — 비면 요청하지 않는다
     * @param lastRequest 마지막 개설 요청일(KST) — 없으면 null
     */
    static Optional<IpoAlertPlanner.PlannedAlert> plan(List<IpoDealEntity> deals, Set<String> heldKeys, LocalDate today,
                                                       LocalDate lastRequest) {
        return plan(deals, List.of(), null, heldKeys, today, lastRequest);
    }

    /**
     * @param history     DART에서 거꾸로 채운 지난 공모(V35)
     * @param coveredFrom 이력을 채운 가장 이른 날 — 아직 시작 전이면 null(문구에 "채우는 중")
     */
    static Optional<IpoAlertPlanner.PlannedAlert> plan(List<IpoDealEntity> deals, List<IpoUnderwritingHistoryEntity> history,
                                                       LocalDate coveredFrom, Set<String> heldKeys, LocalDate today,
                                                       LocalDate lastRequest) {
        if (heldKeys == null || heldKeys.isEmpty()) {
            return Optional.empty();
        }
        LocalDate allowed = nextAllowed(lastRequest);
        if (allowed != null && today.isBefore(allowed)) {
            return Optional.empty();
        }
        LocalDate from1 = today.minusDays(RECENT_DAYS);
        LocalDate from3 = today.minusYears(3);
        LocalDate from5 = today.minusYears(5);
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        for (Corp corp : corps(deals, history)) {
            List<String> keys = corp.underwriters.stream().map(BrokerNames::key).filter(k -> k != null).distinct().toList();
            if (keys.isEmpty() || keys.stream().anyMatch(heldKeys::contains)) {
                continue;   // 인수인을 모르거나 보유 계좌로 청약할 수 있다
            }
            boolean upcoming = corp.recommendedGrade != null && corp.end != null && !corp.end.isBefore(today);
            boolean past = corp.start != null && !corp.start.isAfter(today);
            boolean in1 = past && !corp.start.isBefore(from1);
            boolean in3 = past && !corp.start.isBefore(from3);
            boolean in5 = past && !corp.start.isBefore(from5);
            if (!upcoming && !in5) {
                continue;
            }
            for (String key : keys) {
                Candidate c = candidates.computeIfAbsent(key, Candidate::new);
                if (upcoming) {
                    c.upcoming.add(corp);
                }
                if (in1) {
                    c.recent++;
                }
                if (in3) {
                    c.within3++;
                }
                if (in5) {
                    c.within5++;
                }
            }
        }
        return candidates.values().stream()
                .min(Comparator.comparingInt((Candidate c) -> -c.upcoming.size())
                        .thenComparingInt(c -> -c.recent)
                        .thenComparingInt(c -> -c.within3)
                        .thenComparingInt(c -> -c.within5)
                        .thenComparing(c -> c.key))
                .map(c -> new IpoAlertPlanner.PlannedAlert(ACCOUNT_CORP, displayName(c.key), PHASE_ACCOUNT_OPEN,
                        today.toString(), message(c, today, coveredFrom)));
    }

    static String message(Candidate c, LocalDate today) {
        return message(c, today, null);
    }

    static String message(Candidate c, LocalDate today, LocalDate coveredFrom) {
        StringBuilder text = new StringBuilder("[계좌 개설 요청] %s — 이번 달 개설할 증권사 1곳(신규 계좌는 한 달에 하나씩). "
                .formatted(displayName(c.key)));
        if (!c.upcoming.isEmpty()) {
            List<Corp> sorted = c.upcoming.stream()
                    .sorted(Comparator.comparing((Corp corp) -> corp.start == null ? LocalDate.MAX : corp.start)).toList();
            String listed = sorted.stream().limit(LISTED_DEALS).map(IpoAccountOpenPlanner::dealText)
                    .collect(Collectors.joining(", "));
            text.append("보유 계좌로 청약할 수 없는 다가오는 추천 딜 %d곳: %s%s. ".formatted(sorted.size(), listed,
                    sorted.size() > LISTED_DEALS ? " 외" : ""));
        }
        if (c.within5 > 0) {
            text.append("보유 계좌로 못 한 공모 인수: 최근 1년 %d건 · 3년 %d건 · 5년 %d건%s. "
                    .formatted(c.recent, c.within3, c.within5, coverageNote(today, coveredFrom)));
        }
        text.append("개설하면 application.yml ipo.subscription.held-brokers에 더하고 재기동 — 이 증권사 딜은 청약 점수 +1. ")
                .append("다음 요청은 %s 이후.".formatted(nextAllowed(today).format(MD)));
        return text.toString();
    }

    /** 이력을 다 채우지 못했으면 건수가 덜 셀 수 있다고 밝힌다. */
    static String coverageNote(LocalDate today, LocalDate coveredFrom) {
        if (coveredFrom == null) {
            return "(지난 공모 이력은 아직 채우는 중)";
        }
        return coveredFrom.isAfter(today.minusYears(5)) ? "(지난 공모 이력은 %s부터 채움, 5년 채우는 중)".formatted(coveredFrom) : "";
    }

    private static String dealText(Corp corp) {
        String when = corp.start == null ? "일정 미정"
                : corp.end == null || corp.end.equals(corp.start) ? corp.start.format(MD)
                : corp.start.format(MD) + "~" + corp.end.format(MD);
        return "%s(%s, %s)".formatted(corp.name, when, corp.recommendedGrade.label);
    }

    /** 열쇠를 알림에 쓸 이름으로 — 열쇠가 곧 지금 이름이다(iM증권만 소문자). */
    static String displayName(String key) {
        return "IM증권".equals(key) ? "iM증권" : key;
    }

    static final class Candidate {
        final String key;
        final List<Corp> upcoming = new ArrayList<>();
        int recent;
        int within3;
        int within5;

        Candidate(String key) {
            this.key = key;
        }
    }

    /** 회사 하나(신고서 여러 개를 묶음). */
    record Corp(String name, List<String> underwriters, LocalDate start, LocalDate end,
                IpoSubscriptionAdvisor.Grade recommendedGrade) {
    }

    /** 앱이 모은 딜에 거꾸로 채운 이력을 더한다. 같은 회사는 딜 쪽(일정·추천 등급이 있음)을 쓴다. */
    private static List<Corp> corps(List<IpoDealEntity> deals, List<IpoUnderwritingHistoryEntity> history) {
        List<Corp> corps = corps(deals);
        java.util.Set<String> known = deals.stream().map(IpoDealEntity::getCorpCode).collect(Collectors.toSet());
        for (IpoUnderwritingHistoryEntity h : history) {
            if (known.add(h.getCorpCode())) {
                corps.add(new Corp(h.getCorpName(), h.getUnderwriterList(), h.countedOn(), null, null));
            }
        }
        return corps;
    }

    private static List<Corp> corps(List<IpoDealEntity> deals) {
        Map<String, List<IpoDealEntity>> byCorp = deals.stream()
                .filter(d -> d.getOfferingKind() != OfferingKind.RIGHTS)
                .collect(Collectors.groupingBy(IpoDealEntity::getCorpCode, LinkedHashMap::new, Collectors.toList()));
        List<Corp> corps = new ArrayList<>();
        byCorp.forEach((code, list) -> {
            List<String> underwriters = list.stream().flatMap(d -> d.getUnderwriters().stream()).distinct().toList();
            LocalDate start = list.stream().map(IpoDealEntity::getSubscriptionStart).filter(d -> d != null)
                    .min(Comparator.naturalOrder()).orElse(null);
            LocalDate end = list.stream().map(IpoDealEntity::getSubscriptionEnd).filter(d -> d != null)
                    .max(Comparator.naturalOrder()).orElse(null);
            IpoSubscriptionAdvisor.Grade grade = list.stream()
                    .filter(d -> d.getRecommendation() == IpoRecommendation.RECOMMEND)
                    .map(IpoDealEntity::getAdviceGrade).filter(g -> g != null)
                    .map(IpoAccountOpenPlanner::gradeOrNull).filter(g -> g != null)
                    .min(Comparator.naturalOrder()).orElse(null);   // 비례까지 > 균등만 > … 순(enum 순서)
            corps.add(new Corp(list.get(0).getCorpName(), underwriters, start, end, grade));
        });
        return corps;
    }

    private static IpoSubscriptionAdvisor.Grade gradeOrNull(String name) {
        try {
            IpoSubscriptionAdvisor.Grade g = IpoSubscriptionAdvisor.Grade.valueOf(name);
            return g == IpoSubscriptionAdvisor.Grade.PASS || g == IpoSubscriptionAdvisor.Grade.PENDING ? null : g;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
