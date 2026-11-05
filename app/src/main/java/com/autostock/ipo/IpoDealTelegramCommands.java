package com.autostock.ipo;

import com.autostock.common.util.MarketConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 공모주 딜 텔레그램 명령(2026-11-05, aiDoc/ipo-automation.md 8절) — 기존 {@code /ipo <회사> <경쟁률>} 재판단 옆에 붙는 하위 명령.
 *
 * <ul>
 *   <li>{@code /ipo 청약 <회사> <수량> <증거금>} — 청약 수량·증거금 저장</li>
 *   <li>{@code /ipo 배정 <회사> <수량>} — 배정 수량 저장, 상장일·매도 계획 안내</li>
 *   <li>{@code /ipo 매도 [코드]} — 상장일 실거래 매도 확인(코드 없으면 새 코드)</li>
 * </ul>
 * 저장은 {@link IpoDealCommandService#recordMyDeal}(POST /api/ipo/{id}/record와 같은 트랜잭션)로 하고, 검증도 그 요청과 같다
 * (수량 0 이상, 증거금 0 이상·정수 14자리·소수 2자리 이내).
 */
@Service
public class IpoDealTelegramCommands {

    private static final Logger log = LoggerFactory.getLogger(IpoDealTelegramCommands.class);

    /** {@code /ipo 청약|배정|매도 …}인가. */
    public static final Pattern SUBCOMMAND = Pattern.compile("^/ipo\\s+(청약|배정|매도)(?:\\s+(.*))?$", Pattern.DOTALL);
    private static final Pattern NUMBER = Pattern.compile("[0-9][0-9,]*(\\.[0-9]+)?(원|주)?");
    private static final Pattern CODE = Pattern.compile("\\d{4}");

    static final String USAGE = "사용법: /ipo 청약 <회사> <수량> <증거금> · /ipo 배정 <회사> <수량> · /ipo 매도 <확인코드>\n"
            + "예: /ipo 청약 동원파츠 20 165000 · /ipo 배정 동원파츠 3 · /ipo 매도 1234";

    private final IpoDealRepository repository;
    private final IpoDealCommandService commandService;
    private final IpoLiveSellService liveSell;
    private final Clock clock;

    public IpoDealTelegramCommands(IpoDealRepository repository, IpoDealCommandService commandService,
                                   IpoLiveSellService liveSell, Clock clock) {
        this.repository = repository;
        this.commandService = commandService;
        this.liveSell = liveSell;
        this.clock = clock;
    }

    /** 하위 명령인가(폴러의 명령 판정용). */
    public static boolean matches(String text) {
        return text != null && SUBCOMMAND.matcher(text.strip()).matches();
    }

    /** 명령 본문 → 답(항상 사람이 읽을 문구). */
    public String reply(String text) {
        Matcher m = text == null ? null : SUBCOMMAND.matcher(text.strip());
        if (m == null || !m.matches()) {
            return USAGE;
        }
        String args = m.group(2) == null ? "" : m.group(2).strip();
        return switch (m.group(1)) {
            case "청약" -> subscribe(args);
            case "배정" -> allocate(args);
            default -> sell(args);
        };
    }

    // ===== 해석 =====

    /** 회사 이름과 끝의 숫자들. */
    record Parsed(String query, List<BigDecimal> numbers) {
    }

    /** 끝에서부터 숫자 count개, 나머지가 회사 이름. 모자라면 빈 값. */
    static Optional<Parsed> parse(String args, int count) {
        List<String> tokens = new ArrayList<>(List.of(args.strip().split("\\s+")));
        tokens.removeIf(String::isBlank);
        List<BigDecimal> numbers = new ArrayList<>();
        while (!tokens.isEmpty() && numbers.size() < count && NUMBER.matcher(tokens.get(tokens.size() - 1)).matches()) {
            numbers.add(0, number(tokens.remove(tokens.size() - 1)));
        }
        if (tokens.isEmpty() || numbers.size() != count) {
            return Optional.empty();
        }
        return Optional.of(new Parsed(String.join(" ", tokens), numbers));
    }

    private static BigDecimal number(String token) {
        return new BigDecimal(token.replace(",", "").replace("원", "").replace("주", ""));
    }

    /** 수량 — 0 이상 정수(IpoRecordRequest @PositiveOrZero Integer). 아니면 null. */
    static Integer quantity(BigDecimal value) {
        try {
            int qty = value.intValueExact();
            return qty >= 0 ? qty : null;
        } catch (ArithmeticException e) {
            return null;
        }
    }

    /** 증거금 — 0 이상, 정수 14자리·소수 2자리 이내(IpoRecordRequest @Digits(14,2)). 아니면 null. */
    static BigDecimal deposit(BigDecimal value) {
        BigDecimal v = value.stripTrailingZeros();
        int scale = Math.max(v.scale(), 0);
        int integerDigits = v.precision() - v.scale();
        if (v.signum() < 0 || scale > 2 || integerDigits > 14) {
            return null;
        }
        return value;
    }

    // ===== 명령 =====

    private String subscribe(String args) {
        Optional<Parsed> parsed = parse(args, 2);
        if (parsed.isEmpty()) {
            return "[청약 기록] 형식이 맞지 않습니다.\n" + USAGE;
        }
        Integer qty = quantity(parsed.get().numbers().get(0));
        BigDecimal deposit = deposit(parsed.get().numbers().get(1));
        if (qty == null || deposit == null) {
            return "[청약 기록] 수량은 0 이상 정수, 증거금은 0 이상(소수 2자리 이내)이어야 합니다. 저장하지 않았습니다.";
        }
        return find(parsed.get().query(), "청약 기록").map(corp -> {
            Optional<IpoDealEntity> saved = commandService.recordMyDeal(corp.repId(),
                    new IpoDealCommandService.RecordCommand(qty, deposit, null, null, null, null));
            if (saved.isEmpty()) {
                return "[청약 기록] " + corp.name() + " — 딜을 찾지 못해 저장하지 않았습니다.";
            }
            log.info("공모주 청약 기록(텔레그램): {} 청약 {}주", corp.name(), qty);
            return "[청약 기록] " + corp.name() + " — 청약 " + qty + "주 · 증거금 " + IpoLiveSellService.won(deposit)
                    + " 저장. 배정이 나오면 /ipo 배정 " + corp.name() + " <수량> 을 보내세요.";
        }).orElseGet(() -> notFound(parsed.get().query(), "청약 기록"));
    }

    private String allocate(String args) {
        Optional<Parsed> parsed = parse(args, 1);
        if (parsed.isEmpty()) {
            return "[배정 기록] 형식이 맞지 않습니다.\n" + USAGE;
        }
        Integer qty = quantity(parsed.get().numbers().get(0));
        if (qty == null) {
            return "[배정 기록] 수량은 0 이상 정수여야 합니다. 저장하지 않았습니다.";
        }
        return find(parsed.get().query(), "배정 기록").map(corp -> {
            Optional<IpoDealEntity> saved = commandService.recordMyDeal(corp.repId(),
                    new IpoDealCommandService.RecordCommand(null, null, qty, null, null, null));
            if (saved.isEmpty()) {
                return "[배정 기록] " + corp.name() + " — 딜을 찾지 못해 저장하지 않았습니다.";
            }
            log.info("공모주 배정 기록(텔레그램): {} 배정 {}주", corp.name(), qty);
            return allocationReply(corp, qty);
        }).orElseGet(() -> notFound(parsed.get().query(), "배정 기록"));
    }

    String allocationReply(Corp corp, int qty) {
        StringBuilder sb = new StringBuilder("[배정 기록] ").append(corp.name()).append(" — 배정 ").append(qty).append("주 저장.");
        if (qty == 0) {
            return sb.append(" 배정이 없어 매도 계획이 없습니다.").toString();
        }
        sb.append("\n· 상장일: ").append(corp.listing() == null ? "미정(상장일이 정해지면 자동으로 채운다)"
                : IpoAlertPlanner.day(corp.listing())).append(corp.stockCode() == null ? "" : " · 종목코드 " + corp.stockCode());
        int[] split = IpoSellMath.split(qty);
        sb.append("\n· 매도 계획(실전 계좌): 상장일 08:30 확인 코드 → 08:55까지 /ipo 매도 <코드> → 1차 ").append(split[0])
                .append("주 장전 지정가");
        if (corp.offerPrice() != null) {
            sb.append(' ').append(IpoLiveSellService.won(IpoSellMath.floorPrice(corp.offerPrice())))
                    .append("(공모가 ").append(IpoLiveSellService.won(corp.offerPrice())).append("의 60%, 시초가에 체결)");
        } else {
            sb.append("(공모가 60%, 확정 공모가 미상)");
        }
        sb.append(" · 2차 ").append(split[1]).append("주 장중 고점 −5% · 3차 ").append(split[2])
                .append("주 고점 −10% · 14:30 남은 수량 전부.");
        sb.append("\n· 실거래 매도: ").append(liveSell.stateLabel());
        if (!liveSell.ordersEnabled()) {
            sb.append(" — 지금은 자동 매도하지 않습니다(상장일에 직접 매도).");
        }
        return sb.toString();
    }

    private String sell(String args) {
        if (args.isEmpty()) {
            return liveSell.requestCode();
        }
        if (!CODE.matcher(args).matches()) {
            return "[공모주 실거래 매도] 확인 코드는 4자리 숫자입니다 — 예: /ipo 매도 1234";
        }
        return liveSell.confirm(args);
    }

    // ===== 딜 찾기 =====

    /** 회사 하나 — 기록을 남길 대표 행 id와 안내에 쓸 값. */
    record Corp(Long repId, String name, LocalDate listing, String stockCode, BigDecimal offerPrice) {
    }

    private List<Corp> corps() {
        LocalDate today = LocalDate.now(clock.withZone(MarketConstants.KST));
        List<IpoDealEntity> deals = repository.findBySubscriptionStartGreaterThanEqualOrSubscriptionStartIsNull(
                today.minusDays(IpoLiveSellService.LOOKBACK_DAYS));
        Map<String, List<IpoDealEntity>> byCorp = deals.stream().filter(d -> !d.isRightsOffering())
                .collect(Collectors.groupingBy(IpoDealEntity::getCorpCode, LinkedHashMap::new, Collectors.toList()));
        return byCorp.values().stream().map(rows -> {
            IpoDealEntity rep = IpoLiveSellService.representative(rows);
            LocalDate listing = rows.stream().map(IpoDealEntity::getListingDate).filter(java.util.Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElse(null);
            String code = rows.stream().map(IpoDealEntity::getStockCode).filter(java.util.Objects::nonNull).findFirst().orElse(null);
            BigDecimal offer = rows.stream().map(IpoDealEntity::getOfferPriceFinal).filter(java.util.Objects::nonNull)
                    .findFirst().orElse(null);
            return new Corp(rep.getId(), rep.getCorpName(), listing, code, offer);
        }).toList();
    }

    /** 이름으로 한 곳을 찾는다 — 정확히 같은 이름이 하나면 그것, 아니면 포함하는 곳이 하나일 때만. */
    private Optional<Corp> find(String query, String title) {
        String q = IpoRecheck.normalize(query);
        List<Corp> all = corps();
        List<Corp> exact = all.stream().filter(c -> IpoRecheck.normalize(c.name()).equals(q)).toList();
        if (exact.size() == 1) {
            return Optional.of(exact.get(0));
        }
        List<Corp> matched = all.stream().filter(c -> IpoRecheck.normalize(c.name()).contains(q)).toList();
        return matched.size() == 1 ? Optional.of(matched.get(0)) : Optional.empty();
    }

    private String notFound(String query, String title) {
        String q = IpoRecheck.normalize(query);
        List<String> names = corps().stream().map(Corp::name).filter(n -> IpoRecheck.normalize(n).contains(q)).toList();
        if (names.size() > 1) {
            return "[" + title + "] '" + query + "' — 여러 곳이 맞음: " + String.join(", ", names) + ". 이름을 더 정확히 적어 주세요.";
        }
        return "[" + title + "] '" + query + "' — 최근 " + IpoLiveSellService.LOOKBACK_DAYS + "일 공모주에서 찾지 못함. 저장하지 않았습니다.";
    }
}
