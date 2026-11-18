package com.autostock.ipo;

import com.autostock.common.util.MarketConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 공모주 할 일 셋(VALUNIVO 900) — 앱 봇·니보 경유 모두 {@code monitor.AppCommandRunner}가 이 부품을 부른다.
 * <ul>
 *   <li>{@code ipo_view} 일정 보기 — 앞으로 14일의 청약·상장과 최근 상장 기록({@link IpoAlertPlanner#schedule}).</li>
 *   <li>{@code ipo_allot} 배정 기록 — 청약 뒤 받은 배정 수량(과 청약 수량)을 그 회사의 모든 신고서 행에 쓴다.</li>
 *   <li>{@code ipo_return} 상장 후 수익 기록 — 매도가(또는 공모가 대비 수익률%)와 매도일을 쓴다.</li>
 * </ul>
 * 기록은 데브가 증권사 앱에서 한 일을 적을 뿐 주문하지 않는다. 저장은 화면 기록(POST /api/ipo/{id}/record)과 같은
 * {@link IpoDealCommandService#recordMyDeal}을 쓰고, 검증 범위도 그 요청과 같다(수량 0 이상, 매도가 정수 12자리·소수 2자리).
 * 회사는 최근 {@value #LOOKBACK_DAYS}일 안에 청약을 시작한 공모주(유상증자 제외)에서 이름으로 찾는다.
 */
@Service
public class IpoCommandService {

    private static final Logger log = LoggerFactory.getLogger(IpoCommandService.class);

    /** 명령 ID(설명서 원천과 같아야 한다). */
    public static final List<String> COMMANDS = List.of("ipo_view", "ipo_allot", "ipo_return");
    /** 기록 명령이 찾는 청약 시작일 범위 — 상장 몇 주 뒤에 수익을 적어도 찾게 넉넉히. */
    static final int LOOKBACK_DAYS = 120;
    /** 일정 보기가 읽는 청약 시작일 범위 — 최근 상장(14일)까지 들어오게. */
    static final int VIEW_LOOKBACK_DAYS = 45;

    static final String ALLOT_FORMAT = "형식: /autostock_ipo_allot 회사 배정주수 [청약주수]\n예: /autostock_ipo_allot 동원파츠 3 20";
    static final String RETURN_FORMAT = "형식: /autostock_ipo_return 회사 매도가 또는 수익률% [매도일]\n"
            + "예: /autostock_ipo_return 동원파츠 32000 · /autostock_ipo_return 동원파츠 +85% 2026-11-20";

    private static final Pattern DATE = Pattern.compile("(?<![0-9])(20[0-9]{2}-[01][0-9]-[0-3][0-9])(?![0-9])");
    /** 숫자 하나 — 앞 부호, 쉼표, 소수, 뒤 단위(주·원·%). */
    private static final Pattern NUMBER = Pattern.compile("(?<![0-9A-Za-z.])([+-]?[0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(주|원|%)?");
    private static final BigDecimal MAX_PRICE = new BigDecimal("999999999999.99");

    /** 명령 결과 — 거절은 기록하지 않은 것이다. */
    public record Reply(boolean ok, String text) {}

    private final IpoDealRepository repository;
    private final IpoDealCommandService records;
    private final IpoSubscriptionSizer sizer;
    private final Clock clock;

    @Autowired
    public IpoCommandService(IpoDealRepository repository, IpoDealCommandService records, IpoSubscriptionProperties properties,
                             Clock clock) {
        this.repository = repository;
        this.records = records;
        this.sizer = new IpoSubscriptionSizer(properties);
        this.clock = clock;
    }

    /** 명령 하나를 실행한다. 모르는 명령이면 빈 값. */
    @Transactional
    public Optional<Reply> run(String commandId, String text) {
        String input = text == null ? "" : text.strip();
        Reply reply = switch (commandId) {
            case "ipo_view" -> new Reply(true, IpoAlertPlanner.schedule(
                    repository.findBySubscriptionStartGreaterThanEqualOrSubscriptionStartIsNull(today().minusDays(VIEW_LOOKBACK_DAYS)),
                    today(), sizer));
            case "ipo_allot" -> allot(input);
            case "ipo_return" -> recordReturn(input);
            default -> null;
        };
        if (reply != null && !"ipo_view".equals(commandId)) {
            log.info("공모주 기록 명령 {}: {}", commandId, reply.ok() ? "기록" : "거절");
        }
        return Optional.ofNullable(reply);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(MarketConstants.KST));
    }

    private Reply allot(String input) {
        Found found = find(input);
        if (found.problem() != null) {
            return new Reply(false, found.problem() + "\n" + ALLOT_FORMAT);
        }
        Company company = found.company();
        List<Num> numbers = numbers(found.rest());
        List<Num> counts = numbers.stream().filter(n -> n.unit() == null || "주".equals(n.unit())).toList();
        if (counts.isEmpty()) {
            return new Reply(false, "배정 주수가 없습니다. 기록하지 않았습니다.\n" + ALLOT_FORMAT);
        }
        Integer allocated = shares(counts.get(0).value());
        Integer applied = counts.size() > 1 ? shares(counts.get(1).value()) : null;
        if (allocated == null || (counts.size() > 1 && applied == null)) {
            return new Reply(false, "주수는 0 이상 정수로 보내 주세요. 기록하지 않았습니다.\n" + ALLOT_FORMAT);
        }
        if (applied != null && allocated > applied) {
            return new Reply(false, "배정 %d주가 청약 %d주보다 많습니다. 순서(배정 → 청약)를 확인해 주세요. 기록하지 않았습니다.\n"
                    .formatted(allocated, applied) + ALLOT_FORMAT);
        }
        write(company, new IpoDealCommandService.RecordCommand(applied, null, allocated, null, null, null));
        StringBuilder out = new StringBuilder("[공모주 배정 기록] ").append(company.name()).append(" — 배정 ").append(allocated).append("주");
        if (applied != null) {
            out.append("(청약 ").append(applied).append("주)");
        }
        out.append(" 기록.");
        BigDecimal offer = company.offerPrice();
        if (offer != null && allocated > 0) {
            out.append(" 공모가 ").append(IpoAlertPlanner.won(offer)).append(" 기준 배정 금액 약 ")
                    .append(IpoAlertPlanner.won(offer.multiply(BigDecimal.valueOf(allocated)))).append('.');
        }
        if (company.listing() != null) {
            out.append("\n상장 ").append(IpoAlertPlanner.day(company.listing()));
            out.append(company.listing().isAfter(today()) ? " 예정." : ".");
        }
        out.append("\n상장 뒤 수익은 /autostock_ipo_return ").append(company.name()).append(" 매도가 로 기록합니다.");
        return new Reply(true, out.toString());
    }

    private Reply recordReturn(String input) {
        Found found = find(input);
        if (found.problem() != null) {
            return new Reply(false, found.problem() + "\n" + RETURN_FORMAT);
        }
        Company company = found.company();
        String rest = found.rest();
        LocalDate sellDate = today();
        Matcher date = DATE.matcher(rest);
        if (date.find()) {
            try {
                sellDate = LocalDate.parse(date.group(1));
            } catch (DateTimeParseException e) {
                return new Reply(false, "날짜는 2026-11-20 형식으로 보내 주세요. 기록하지 않았습니다.\n" + RETURN_FORMAT);
            }
            rest = rest.substring(0, date.start()) + " " + rest.substring(date.end());
        }
        if (sellDate.isAfter(today())) {
            return new Reply(false, "매도일이 오늘보다 뒤입니다. 기록하지 않았습니다.\n" + RETURN_FORMAT);
        }
        List<Num> numbers = numbers(rest);
        if (numbers.isEmpty()) {
            return new Reply(false, "매도가나 수익률이 없습니다. 기록하지 않았습니다.\n" + RETURN_FORMAT);
        }
        Num first = numbers.get(0);
        BigDecimal offer = company.finalOfferPrice();
        BigDecimal sellPrice;
        boolean fromRate = "%".equals(first.unit());
        if (fromRate) {
            if (offer == null) {
                return new Reply(false, company.name() + "의 확정 공모가를 몰라 수익률을 매도가로 바꿀 수 없습니다. 매도가로 보내 주세요. 기록하지 않았습니다.\n"
                        + RETURN_FORMAT);
            }
            if (first.value().compareTo(BigDecimal.valueOf(-100)) <= 0) {
                return new Reply(false, "수익률은 -100%보다 커야 합니다. 기록하지 않았습니다.\n" + RETURN_FORMAT);
            }
            sellPrice = offer.multiply(BigDecimal.ONE.add(first.value().movePointLeft(2))).setScale(0, RoundingMode.HALF_UP);
        } else {
            sellPrice = first.value();
        }
        if (sellPrice.signum() <= 0 || sellPrice.compareTo(MAX_PRICE) > 0 || sellPrice.scale() > 2) {
            return new Reply(false, "매도가는 0보다 큰 원 단위(소수 둘째 자리까지)로 보내 주세요. 기록하지 않았습니다.\n" + RETURN_FORMAT);
        }
        write(company, new IpoDealCommandService.RecordCommand(null, null, null, sellPrice, sellDate, null));
        StringBuilder out = new StringBuilder("[공모주 수익 기록] ").append(company.name()).append(" — 매도가 ")
                .append(IpoAlertPlanner.won(sellPrice));
        if (fromRate) {
            out.append("(수익률 ").append(first.value().stripTrailingZeros().toPlainString()).append("%를 확정 공모가로 환산)");
        }
        out.append(", 매도일 ").append(IpoAlertPlanner.day(sellDate)).append(" 기록.");
        if (offer == null) {
            out.append("\n확정 공모가를 몰라 수익률은 계산하지 않았습니다.");
        } else {
            out.append("\n공모가 ").append(IpoAlertPlanner.won(offer)).append(" 대비 ").append(IpoAlertPlanner.returnText(sellPrice, offer));
            Integer allocated = company.allocated();
            if (allocated == null || allocated <= 0) {
                out.append(". 배정 수량 기록이 없어 차익은 계산하지 않았습니다(/autostock_ipo_allot ").append(company.name())
                        .append(" 배정주수).");
            } else {
                out.append(", 배정 ").append(allocated).append("주 기준 차익 약 ")
                        .append(IpoAlertPlanner.won(sellPrice.subtract(offer).multiply(BigDecimal.valueOf(allocated))))
                        .append("(수수료·세금 전).");
            }
        }
        return new Reply(true, out.toString());
    }

    /** 회사의 모든 신고서 행에 같은 기록을 쓴다 — 화면·알림이 어느 행을 읽어도 같은 값이 되게. */
    private void write(Company company, IpoDealCommandService.RecordCommand command) {
        for (IpoDealEntity deal : company.deals()) {
            records.recordMyDeal(deal.getId(), command);
        }
    }

    /** 찾은 회사와 이름을 뗀 나머지 글, 또는 거절 사유. */
    private record Found(Company company, String rest, String problem) {}

    /** 회사 한 곳 — 정정 신고서마다 한 행. */
    private record Company(String code, String name, List<IpoDealEntity> deals) {

        LocalDate listing() {
            return deals.stream().map(IpoDealEntity::getListingDate).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        }

        BigDecimal finalOfferPrice() {
            return deals.stream().map(IpoDealEntity::getOfferPriceFinal).filter(Objects::nonNull).findFirst().orElse(null);
        }

        BigDecimal offerPrice() {
            return Optional.ofNullable(finalOfferPrice()).orElseGet(() -> latest().bestKnownOfferPrice());
        }

        Integer allocated() {
            return latestFirst().map(IpoDealEntity::getAllocatedQty).filter(Objects::nonNull).findFirst().orElse(null);
        }

        private IpoDealEntity latest() {
            return latestFirst().findFirst().orElseThrow();
        }

        private java.util.stream.Stream<IpoDealEntity> latestFirst() {
            return deals.stream().sorted(Comparator.comparing(IpoDealEntity::getRceptNo).reversed());
        }
    }

    /**
     * 글에서 회사를 찾는다. 최근 공모주 이름이 글 안에 그대로 있으면 그 회사(가장 긴 이름), 없으면 첫 낱말을 이름 일부로 본다.
     * 여러 곳이 맞으면 고르지 않고 후보를 알려 준다(잘못된 회사에 기록하지 않게).
     */
    private Found find(String input) {
        if (input.isBlank()) {
            return new Found(null, "", "회사 이름이 없습니다. 기록하지 않았습니다.");
        }
        List<Company> companies = companies();
        String normalizedInput = IpoRecheck.normalize(input);
        List<Company> inText = companies.stream()
                .filter(c -> !IpoRecheck.normalize(c.name()).isEmpty() && normalizedInput.contains(IpoRecheck.normalize(c.name())))
                .toList();
        if (!inText.isEmpty()) {
            int longest = inText.stream().mapToInt(c -> IpoRecheck.normalize(c.name()).length()).max().orElse(0);
            List<Company> best = inText.stream().filter(c -> IpoRecheck.normalize(c.name()).length() == longest).toList();
            if (best.size() == 1) {
                Company company = best.get(0);
                return new Found(company, withoutName(input, company.name()), null);
            }
            return ambiguous(input, best);
        }
        String word = input.split("\\s+")[0];
        String query = IpoRecheck.normalize(word);
        if (query.isEmpty() || NUMBER.matcher(word).matches()) {
            return new Found(null, "", "회사 이름을 먼저 보내 주세요. 기록하지 않았습니다.");
        }
        List<Company> partial = companies.stream().filter(c -> IpoRecheck.normalize(c.name()).contains(query)).toList();
        if (partial.isEmpty()) {
            return new Found(null, "", "'" + word + "' — 최근 " + LOOKBACK_DAYS + "일 공모주에서 찾지 못했습니다. 기록하지 않았습니다.");
        }
        if (partial.size() > 1) {
            return ambiguous(word, partial);
        }
        return new Found(partial.get(0), input.substring(word.length()), null);
    }

    private static Found ambiguous(String query, List<Company> companies) {
        return new Found(null, "", "'" + query + "' — 여러 곳이 맞습니다: "
                + companies.stream().map(Company::name).collect(Collectors.joining(", ")) + ". 이름을 더 정확히 적어 주세요. 기록하지 않았습니다.");
    }

    /** 글에서 회사 이름(띄어쓰기 무시)을 한 번 뗀다 — 이름 속 숫자를 수량으로 읽지 않게. */
    static String withoutName(String input, String name) {
        String compactName = name.replaceAll("\\s+", "");
        StringBuilder pattern = new StringBuilder();
        for (int i = 0; i < compactName.length(); i++) {
            if (i > 0) {
                pattern.append("\\s*");
            }
            pattern.append(Pattern.quote(String.valueOf(compactName.charAt(i))));
        }
        Matcher m = Pattern.compile(pattern.toString(), Pattern.CASE_INSENSITIVE).matcher(input);
        return m.find() ? input.substring(0, m.start()) + " " + input.substring(m.end()) : input;
    }

    private List<Company> companies() {
        List<IpoDealEntity> deals = repository.findBySubscriptionStartGreaterThanEqualOrSubscriptionStartIsNull(today().minusDays(LOOKBACK_DAYS));
        Map<String, List<IpoDealEntity>> byCorp = deals.stream()
                .filter(d -> !d.isRightsOffering())
                .collect(Collectors.groupingBy(IpoDealEntity::getCorpCode, LinkedHashMap::new, Collectors.toList()));
        List<Company> out = new ArrayList<>();
        byCorp.forEach((code, rows) -> {
            String name = rows.stream().max(Comparator.comparing(IpoDealEntity::getRceptNo)).orElseThrow().getCorpName();
            out.add(new Company(code, name, List.copyOf(rows)));
        });
        return out;
    }

    /** 숫자와 단위. */
    record Num(BigDecimal value, String unit) {}

    static List<Num> numbers(String text) {
        List<Num> out = new ArrayList<>();
        Matcher m = NUMBER.matcher(text == null ? "" : text);
        while (m.find()) {
            try {
                out.add(new Num(new BigDecimal(m.group(1).replace(",", "").replace("+", "")), m.group(2)));
            } catch (NumberFormatException e) {
                // 쉼표만 있는 등 숫자가 아니면 건너뛴다
            }
        }
        return out;
    }

    private static Integer shares(BigDecimal value) {
        try {
            int count = value.intValueExact();
            return count < 0 ? null : count;
        } catch (ArithmeticException e) {
            return null;
        }
    }
}
