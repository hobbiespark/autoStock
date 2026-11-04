package com.autostock.ipo;

import com.autostock.common.util.MarketConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 청약일 재판단 텔레그램 명령 {@code /ipo}(2026-11-05) — 딜을 찾아 {@link IpoRecheck}로 배정 예상·청약 금액을 다시 계산한 답을 만든다.
 * 읽기만 한다(DB에 쓰지 않는다). 명령 수신·발신자 확인은 monitor의 텔레그램 폴러가 한다.
 */
@Service
public class IpoRecheckService {

    private static final Logger log = LoggerFactory.getLogger(IpoRecheckService.class);
    /** 이 날 수만큼 지난 청약 시작일까지 찾는다. */
    static final int LOOKBACK_DAYS = 30;
    /** /ipo 도움말에 보일 곧 청약할 딜 — 오늘부터 이 날 수 안에 시작. */
    static final int UPCOMING_DAYS = 7;

    static final String USAGE = "사용법: /ipo <회사> <경쟁률> [청약건수] [일반청약 물량]\n"
            + "예: /ipo 동원파츠 1500 120000 600000 — 증권사 청약 화면의 통합 경쟁률·청약 건수·일반청약자 배정 주식수.\n"
            + "청약건수·물량이 없으면 비례 배정만 계산한다.";

    private final IpoDealRepository repository;
    private final IpoSubscriptionSizer sizer;
    private final Clock clock;

    @Autowired
    public IpoRecheckService(IpoDealRepository repository, IpoSubscriptionProperties properties, Clock clock) {
        this.repository = repository;
        this.sizer = new IpoSubscriptionSizer(properties);
        this.clock = clock;
    }

    /** 명령 본문 → 답(항상 사람이 읽을 문구). */
    @Transactional(readOnly = true)
    public String reply(String text) {
        LocalDate today = LocalDate.now(clock.withZone(MarketConstants.KST));
        List<IpoAlertPlanner.CorpView> corps = IpoAlertPlanner.corpViews(
                repository.findBySubscriptionStartGreaterThanEqualOrSubscriptionStartIsNull(today.minusDays(LOOKBACK_DAYS)),
                today, sizer);
        Optional<IpoRecheck.Args> args = IpoRecheck.parse(text);
        if (args.isEmpty()) {
            return help(corps, today);
        }
        String query = IpoRecheck.normalize(args.get().query());
        List<IpoAlertPlanner.CorpView> matched = corps.stream()
                .filter(c -> IpoRecheck.normalize(c.name()).contains(query)).toList();
        List<IpoAlertPlanner.CorpView> exact = matched.stream()
                .filter(c -> IpoRecheck.normalize(c.name()).equals(query)).toList();
        if (exact.size() == 1) {
            matched = exact;
        } else if (matched.size() > 1) {
            List<IpoAlertPlanner.CorpView> open = matched.stream().filter(IpoAlertPlanner.CorpView::open).toList();
            if (open.size() == 1) {
                matched = open;
            }
        }
        if (matched.isEmpty()) {
            return "[청약 재판단] '" + args.get().query() + "' — 최근 " + LOOKBACK_DAYS + "일 공모주에서 찾지 못함.\n" + help(corps, today);
        }
        if (matched.size() > 1) {
            return "[청약 재판단] '" + args.get().query() + "' — 여러 곳이 맞음: "
                    + matched.stream().map(IpoAlertPlanner.CorpView::label).collect(Collectors.joining(", "))
                    + ". 이름을 더 정확히 적어 주세요.";
        }
        String answer = recheck(matched.get(0), args.get(), today);
        log.info("공모주 청약 재판단(/ipo): {}", answer.replace('\n', ' '));
        return answer;
    }

    String recheck(IpoAlertPlanner.CorpView corp, IpoRecheck.Args args, LocalDate today) {
        IpoSubscriptionProperties props = sizer.properties();
        List<String> lines = new ArrayList<>();
        String when;
        if (!corp.open()) {
            when = "청약 기간 아님";
        } else if (corp.start() != null && today.equals(corp.start()) && corp.end() != null && corp.end().isAfter(today)) {
            when = "청약 1일차(" + IpoAlertPlanner.day(today) + ", 마감 " + IpoAlertPlanner.day(corp.end()) + ") 기준";
        } else if (corp.end() != null && today.equals(corp.end())) {
            when = "청약 마감일(" + IpoAlertPlanner.day(today) + ") 기준";
        } else {
            when = IpoAlertPlanner.day(today) + " 기준";
        }
        String inputs = "경쟁률 " + IpoRecheck.rate(args.rate())
                + (args.applicants() == null ? "" : " · 청약 " + IpoRecheck.count(args.applicants()) + "건")
                + (args.generalShares() == null ? "" : " · 일반물량 " + IpoRecheck.count(args.generalShares()) + "주");
        lines.add("[청약 재판단] " + corp.label() + " — " + when + ": " + inputs);
        lines.add("· 등급: " + gradeLabel(corp.adviceGrade()) + "(그대로 — 등급은 청약 전 값으로만 정한다)");

        IpoRecheck.equalPerAccount(args.generalShares(), args.applicants()).ifPresentOrElse(
                eq -> lines.add("· 균등 예상 배정: 계좌당 약 " + IpoRecheck.shares(eq) + "주(균등 "
                        + IpoRecheck.count(args.generalShares() / 2) + "주 ÷ " + IpoRecheck.count(args.applicants()) + "건)"
                        + (eq.compareTo(BigDecimal.ONE) < 0 ? " — 1주 미만이라 추첨" : "")),
                () -> lines.add("· 균등 예상 배정: 청약건수·일반물량을 붙이면 계산"));

        int minQty = props.minQty();
        BigDecimal price = corp.sizing().map(s -> s.deal().price()).orElse(corp.offerPrice());
        String minMargin = price == null ? "공모가 미상"
                : "증거금 약 " + IpoAlertPlanner.won(price.multiply(props.marginRate()).multiply(BigDecimal.valueOf(minQty)));
        String rateText = "비례 경쟁률 약 " + IpoRecheck.rate(IpoRecheck.proportionalRate(args.rate())) + " 가정";
        if (corp.sizing().isEmpty()) {
            lines.add("· 비례 예상 배정: 최소 " + minQty + "주 청약 시 약 "
                    + IpoRecheck.shares(IpoRecheck.proportionalShares(minQty, args.rate())) + "주(" + rateText + ")");
            lines.add("· 재판단: 청약 금액 추천 대상 아님(" + gradeLabel(corp.adviceGrade())
                    + (corp.open() ? "" : "·청약 기간 아님") + ") — 청약한다면 최소 " + minQty + "주(" + minMargin + ")");
        } else {
            IpoSubscriptionSizer.Sizing sizing = corp.sizing().get();
            int qty = sizing.qty();
            lines.add("· 비례 예상 배정: 추천 " + IpoRecheck.count(qty) + "주 청약 시 약 "
                    + IpoRecheck.shares(IpoRecheck.proportionalShares(qty, args.rate())) + "주(" + rateText + ")");
            String original = "원래 추천 " + IpoRecheck.count(qty) + "주 · 증거금 약 " + IpoAlertPlanner.won(sizing.margin());
            if (qty <= minQty) {
                lines.add("· 재판단: 균등 최소 수량 유지 — " + original);
            } else if (IpoRecheck.extraWorthIt(qty, minQty, args.rate())) {
                lines.add("· 재판단: 추천 청약 유지 — " + original + "(비례 추가분 " + IpoRecheck.count(qty - minQty) + "주 → 약 "
                        + IpoRecheck.shares(IpoRecheck.proportionalShares(qty - minQty, args.rate())) + "주 기대)");
            } else {
                lines.add("· 재판단: 균등 최소 " + minQty + "주(" + minMargin + ")만 권장 — 비례 추가분 "
                        + IpoRecheck.count(qty - minQty) + "주의 기대 배정이 약 "
                        + IpoRecheck.shares(IpoRecheck.proportionalShares(qty - minQty, args.rate())) + "주(1주 미만). " + original);
            }
        }
        if (when.startsWith("청약 1일차")) {
            lines.add("※ 1일차 경쟁률은 마감일에 보통 더 오른다 — 마감일에 다시 보내면 다시 계산한다.");
        }
        lines.add("※ 비례 경쟁률은 통합 경쟁률×2 근사(일반물량의 절반이 균등). 주관사·증권사별 배정 방식이 다를 수 있다. 청약은 직접 판단.");
        return String.join("\n", lines);
    }

    private String help(List<IpoAlertPlanner.CorpView> corps, LocalDate today) {
        List<String> open = corps.stream()
                .filter(c -> c.sizing().isPresent() && c.start() != null && !c.start().isAfter(today.plusDays(UPCOMING_DAYS)))
                .map(c -> c.label() + " " + IpoAlertPlanner.day(c.start())
                        + (c.end() == null ? "" : "~" + IpoAlertPlanner.day(c.end())) + " " + gradeLabel(c.adviceGrade()))
                .toList();
        return USAGE + (open.isEmpty() ? "\n지금 청약 중이거나 곧 청약할 추천 딜 없음."
                : "\n추천 딜: " + String.join(" · ", open));
    }

    private static String gradeLabel(String grade) {
        if (grade == null) {
            return "판단 전";
        }
        try {
            return IpoSubscriptionAdvisor.Grade.valueOf(grade).label;
        } catch (IllegalArgumentException e) {
            return grade;
        }
    }
}
