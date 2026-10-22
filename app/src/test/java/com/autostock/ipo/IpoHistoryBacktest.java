package com.autostock.ipo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 공모주 청약 추천 전략 검증(aiDoc/ipo-subscription-strategy.md 3절) — 앱과 <b>같은 코드</b>(파서·{@link IpoSubscriptionAdvisor})로
 * 과거 공모주의 등급을 다시 매긴다. 자료는 {@code scripts/fetch_ipo_history.py}가 만든 폴더(환경변수 {@code IPO_HISTORY_DIR})이고,
 * 없으면 건너뛴다(CI·평소 시험에서는 돌지 않는다).
 *
 * <p>판단 시점은 [발행조건확정] 접수일이다. 맥락(직전 20건 확약 중앙값, 직전 상장 5곳 상장일 종가 평균)은 그 날짜보다
 * <b>앞선</b> 자료만 쓴다 — 확약은 확정 신고서가 먼저 나온 딜, 분위기는 그 날짜 전에 상장한 딜.
 *
 * <p>출력: {@code backtest.csv} — 딜마다 재료·점수·등급과 상장일 시가·종가·5거래일 종가 수익률(공모가 대비).
 */
class IpoHistoryBacktest {

    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;

    private record Deal(String corpCode, String name, String stockCode, LocalDate confirmedOn, LocalDate listedOn,
                        DemandForecastParser.DemandForecast forecast, OfferingPriceParser.OfferingPrice price,
                        FloatSharesParser.FloatShares floatShares, Double open, Double close, Double d5Close, String note) {

        BigDecimal closeReturn() {
            return close == null || price == null ? null
                    : BigDecimal.valueOf(close).divide(price.finalPrice(), 6, RoundingMode.HALF_UP).subtract(BigDecimal.ONE);
        }
    }

    @Test
    void 과거_공모주를_앱과_같은_규칙으로_다시_매긴다() throws IOException {
        String dirName = System.getenv("IPO_HISTORY_DIR");
        assumeTrue(dirName != null && !dirName.isBlank(), "IPO_HISTORY_DIR 없음 — 검증 자료가 있을 때만 돈다");
        Path dir = Path.of(dirName);
        List<Map<String, String>> rows = csv(dir.resolve("ipo_candidates.csv"));
        List<Deal> deals = new ArrayList<>();
        for (Map<String, String> row : rows) {
            if (!row.get("note").isEmpty()) {
                continue;   // 이미 상장된 회사(유상증자 등)·오류
            }
            deals.add(load(dir, row));
        }
        deals.removeIf(d -> d.forecast() == null || d.forecast().competitionRate() == null);
        deals.sort(Comparator.comparing(Deal::confirmedOn).thenComparing(Deal::name));

        StringBuilder out = new StringBuilder("corp_code,name,stock_code,confirmed_on,listed_on,competition,lockup,final_price,band_low,"
                + "band_high,band,float_ratio,listed_shares,market_cap_eok,ctx_lockup_median,ctx_lockup_n,ctx_mood,ctx_mood_n,score,grade,"
                + "old_rule,open_ret,close_ret,d5_ret,reason\n");
        for (Deal deal : deals) {
            List<BigDecimal> lockups = deals.stream()
                    .filter(d -> d.confirmedOn().isBefore(deal.confirmedOn()) && d.forecast().lockupCommitRate() != null)
                    .map(d -> d.forecast().lockupCommitRate()).toList();
            List<BigDecimal> moods = deals.stream()
                    .filter(d -> d.listedOn() != null && d.listedOn().isBefore(deal.confirmedOn()) && d.closeReturn() != null)
                    .sorted(Comparator.comparing(Deal::listedOn))
                    .map(Deal::closeReturn).toList();
            IpoSubscriptionAdvisor.Context ctx = IpoSubscriptionAdvisor.Context.of(lockups, moods);
            OfferingPriceParser.OfferingPrice price = deal.price();
            IpoSubscriptionAdvisor.Advice advice = IpoSubscriptionAdvisor.advise(new IpoSubscriptionAdvisor.Input(
                    deal.forecast().competitionRate(), deal.forecast().lockupCommitRate(),
                    price == null ? null : price.finalPrice(), price == null ? null : price.bandLow(),
                    price == null ? null : price.bandHigh(),
                    deal.floatShares() == null ? null : deal.floatShares().ratio(),
                    deal.floatShares() == null ? null : deal.floatShares().listedShares(), ctx));
            boolean oldRule = deal.forecast().lockupCommitRate() != null
                    && deal.forecast().competitionRate().compareTo(BigDecimal.valueOf(500)) >= 0
                    && deal.forecast().lockupCommitRate().compareTo(new BigDecimal("0.20")) >= 0;
            Double finalPrice = price == null ? null : price.finalPrice().doubleValue();
            out.append(String.join(",", List.of(
                    deal.corpCode(), quote(deal.name()), deal.stockCode(), deal.confirmedOn().toString(),
                    deal.listedOn() == null ? "" : deal.listedOn().toString(),
                    str(deal.forecast().competitionRate()), str(deal.forecast().lockupCommitRate()),
                    price == null ? "" : str(price.finalPrice()), price == null ? "" : str(price.bandLow()),
                    price == null ? "" : str(price.bandHigh()), price == null || price.positionInBand() == null ? "" : price.positionInBand(),
                    deal.floatShares() == null ? "" : str(deal.floatShares().ratio()),
                    deal.floatShares() == null || deal.floatShares().listedShares() == null ? "" : deal.floatShares().listedShares().toString(),
                    capEok(price, deal.floatShares()),
                    str(ctx.lockupMedian()), String.valueOf(ctx.lockupSamples()), str(ctx.moodMean()), String.valueOf(ctx.moodSamples()),
                    String.valueOf(advice.score()), advice.grade().name(), oldRule ? "1" : "0",
                    ret(deal.open(), finalPrice), ret(deal.close(), finalPrice), ret(deal.d5Close(), finalPrice),
                    quote(advice.reason())))).append('\n');
        }
        Files.writeString(dir.resolve("backtest.csv"), out.toString(), StandardCharsets.UTF_8);
        System.out.println("공모주 검증 " + deals.size() + "건 → " + dir.resolve("backtest.csv"));
    }

    private static Deal load(Path dir, Map<String, String> row) throws IOException {
        DemandForecastParser.DemandForecast forecast = null;
        OfferingPriceParser.OfferingPrice price = null;
        List<String> confirmed = new ArrayList<>(Arrays.asList(row.get("confirmed_rcept_nos").split(" ")));
        confirmed.sort(Comparator.reverseOrder());   // 최신 확정 신고서부터(앱과 같은 순서)
        for (String rceptNo : confirmed) {
            Path doc = dir.resolve("docs").resolve(rceptNo + ".xml");
            if (!Files.exists(doc)) {
                continue;
            }
            String xml = read(doc);
            if (forecast == null) {
                forecast = DemandForecastParser.parse(xml).orElse(null);
            }
            if (price == null) {
                price = OfferingPriceParser.parse(DartDocument.plainText(xml)).orElse(null);
            }
        }
        Path snippet = dir.resolve("float").resolve(row.get("corp_code") + ".txt");
        FloatSharesParser.FloatShares floatShares = Files.exists(snippet)
                ? FloatSharesParser.parse(Files.readString(snippet, StandardCharsets.UTF_8)).orElse(null) : null;
        return new Deal(row.get("corp_code"), row.get("corp_name"), row.get("stock_code"),
                LocalDate.parse(row.get("confirmed_dt"), BASIC),
                row.get("listing_date").isEmpty() ? null : LocalDate.parse(row.get("listing_date")),
                forecast, price, floatShares, num(row.get("open")), num(row.get("close")), num(row.get("d5_close")), row.get("note"));
    }

    private static String read(Path doc) throws IOException {
        byte[] raw = Files.readAllBytes(doc);
        String head = new String(raw, 0, Math.min(300, raw.length), StandardCharsets.US_ASCII).toLowerCase();
        Charset cs = head.contains("euc-kr") ? Charset.forName("EUC-KR") : StandardCharsets.UTF_8;
        return new String(raw, cs);
    }

    private static List<Map<String, String>> csv(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String[] header = split(lines.get(0));
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            String[] cells = split(line);
            Map<String, String> row = new HashMap<>();
            for (int i = 0; i < header.length; i++) {
                row.put(header[i], i < cells.length ? cells[i] : "");
            }
            rows.add(row);
        }
        return rows;
    }

    /** 단순 CSV — 이 자료에는 따옴표 안 쉼표가 없다(회사명·숫자만). */
    private static String[] split(String line) {
        return line.split(",", -1);
    }

    private static Double num(String s) {
        return s == null || s.isBlank() ? null : Double.valueOf(s);
    }

    private static String str(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    private static String ret(Double price, Double base) {
        return price == null || base == null || base == 0 ? "" : String.format("%.4f", price / base - 1);
    }

    private static String capEok(OfferingPriceParser.OfferingPrice price, FloatSharesParser.FloatShares fs) {
        if (price == null || fs == null || fs.listedShares() == null) {
            return "";
        }
        return price.finalPrice().multiply(BigDecimal.valueOf(fs.listedShares()))
                .divide(new BigDecimal("100000000"), 0, RoundingMode.HALF_UP).toPlainString();
    }

    private static String quote(String s) {
        return "\"" + s.replace("\"", "'") + "\"";
    }
}
