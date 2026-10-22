package com.autostock.ipo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 기관경쟁률·의무보유확약비율 자동 입력 — 회사의 [발행조건확정]증권신고서(지분증권) 원본을 받아 「수요예측 결과」를 읽는다
 * ({@link DemandForecastParser}, 2026-10-02 사용자 요구, aiDoc/ipo-demand-forecast.md).
 *
 * <p>2026-10-13: 같은 원본에서 확정 공모가와 희망 밴드도 읽는다({@link OfferingPriceParser}) — 주요정보의 모집가액은 희망가 하단이라
 * 알림의 "공모가"가 확정가와 달랐다.
 *
 * <ul>
 *   <li>대상: 공모주(유상증자 제외)이면서 지표나 확정 공모가가 아직 없는 딜. 사람이 넣은 지표(MANUAL)는 덮지 않는다.</li>
 *   <li>범위: 청약 시작이 {@value #RECENT_DAYS}일 이내로 지났거나 {@value #CONFIRM_LOOKAHEAD_DAYS}일 안에 오는 딜, 일정을 모르면
 *       딜이 생긴 지 {@value #RECENT_DAYS}일 이내. 오래된 딜을 매일 다시 찾지 않는다. 청약이 그보다 멀면 [발행조건확정]이 아직
 *       나올 때가 아니라 보지 않는다(2026-10-15 — 아래 순서 참고).</li>
 *   <li>한 배치에 최대 {@value #CORPS_PER_RUN}개 회사. 회사마다 발행공시 목록 1회 + 원본 1~{@value #DOCUMENTS_PER_CORP}건.</li>
 *   <li>순서: [발행조건확정]을 이미 읽어 지표는 있는데 확정가만 빈 회사 → 다가오는 청약 → 최근 지난 청약 → 일정 모름.
 *       2026-10-15 실측: 확정가를 한 곳도 채우지 못했다 — 청약이 3~4주 뒤인 신규 공모 10여 곳이 매번 상한을 다 써서, 일정을
 *       모르는 멜콘·진코스텍·엘리스그룹·엠에스바이오(지표는 10/2~10/8에 읽음)까지 차례가 오지 않았다.</li>
 *   <li>[발행조건확정]이 여러 건이면 최신부터 읽는다 — 수요예측 표가 빠진 확정 신고서(실측: 진코스텍 9/28 첫 건)는 건너뛴다.</li>
 *   <li>같은 회사의 딜(정정 신고서마다 한 행)에 같은 값을 넣는다.</li>
 * </ul>
 *
 * <p>[발행조건확정]은 보통 수요예측 마감일 저녁에 나오고 청약은 그 2~3일 뒤다 — 다음 날 08:20 배치가 읽으면 청약 D-1 알림에
 * 권고가 실린다.
 *
 * <p>2026-10-15(청약 추천 전략, aiDoc/ipo-subscription-strategy.md): 공모주 딜의 증권신고서 본문에서 <b>상장 직후 유통가능물량</b>과
 * 상장예정주식수도 읽는다({@link FloatSharesParser}). 회사의 최신 신고서부터 [발행조건확정]·첨부정정(원본 없음)을 건너뛰며
 * {@value #FLOAT_DOCUMENTS}건까지 본다. 신고서 원본은 2~5MB라 회사마다 한 번만 읽는다(읽은 신고서 번호를 남긴다).
 */
class IpoDemandForecastCollector {

    private static final Logger log = LoggerFactory.getLogger(IpoDemandForecastCollector.class);

    static final int RECENT_DAYS = 45;
    /** 청약이 이 일수보다 멀면 [발행조건확정]을 아직 찾지 않는다 — 확정 신고서는 보통 청약 2~4 영업일 전에 나온다(연휴 여유 포함). */
    static final int CONFIRM_LOOKAHEAD_DAYS = 10;
    static final int CORPS_PER_RUN = 10;
    static final int DOCUMENTS_PER_CORP = 3;
    /** 유통가능물량을 찾는 신고서 수(회사마다, 최신부터). */
    static final int FLOAT_DOCUMENTS = 3;
    /** 회사의 첫 신고서보다 이만큼 앞부터 발행공시를 찾는다(접수번호 날짜 기준). */
    private static final int FILING_SEARCH_MARGIN_DAYS = 7;

    private final DartClient dartClient;
    private final IpoDealRepository repository;
    private final Clock clock;
    /** 유통가능물량을 찾지 못한 회사 → 시도한 날 — 신고서 원본이 커서(2~5MB) 같은 날 다시 받지 않는다. */
    private final Map<String, LocalDate> floatTried = new java.util.concurrent.ConcurrentHashMap<>();

    IpoDemandForecastCollector(DartClient dartClient, IpoDealRepository repository, Clock clock) {
        this.dartClient = dartClient;
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 이번 배치에서 자동 입력한 회사 수.
     *
     * @param metricsFilled 수요예측 지표를 넣은 회사 수
     * @param pricesFilled  확정 공모가를 넣은 회사 수(2026-10-13)
     * @param floatsFilled  상장 직후 유통가능물량을 넣은 회사 수(2026-10-15)
     */
    record Result(int metricsFilled, int pricesFilled, int floatsFilled) {
        static final Result NONE = new Result(0, 0, 0);

        Result(int metricsFilled, int pricesFilled) {
            this(metricsFilled, pricesFilled, 0);
        }
    }

    /** 자동 입력한 회사 수를 돌려준다. 청약이 가까운 회사부터 본다(다가오는 청약 → 최근 지난 청약 → 일정 모름). */
    Result collect(LocalDate today) {
        Map<String, List<IpoDealEntity>> byCorp = new LinkedHashMap<>();
        repository.findAll().stream()
                .filter(deal -> needsWork(deal, today))
                .sorted(Comparator.comparing((IpoDealEntity deal) -> urgency(deal, today)))
                .forEach(deal -> byCorp.computeIfAbsent(deal.getCorpCode(), k -> new java.util.ArrayList<>()).add(deal));
        int metrics = 0;
        int prices = 0;
        int floats = 0;
        int tried = 0;
        for (Map.Entry<String, List<IpoDealEntity>> corp : byCorp.entrySet()) {
            if (tried++ >= CORPS_PER_RUN) {
                break;
            }
            Result filled = collectCorp(corp.getKey(), corp.getValue(), today);
            metrics += filled.metricsFilled();
            prices += filled.pricesFilled();
            floats += filled.floatsFilled();
        }
        return new Result(metrics, prices, floats);
    }

    /** 정렬 열쇠 — 확정 신고서를 이미 읽었는데 확정가만 빔 &lt; 다가오는 청약(가까운 순) &lt; 지난 청약(최근 순) &lt; 일정 모름. */
    static long urgency(IpoDealEntity deal, LocalDate today) {
        if (deal.getMetricsRceptNo() != null && deal.getOfferPriceFinal() == null) {
            return -1;   // 어느 신고서를 읽을지 안다 — 원본 한 건이면 끝난다
        }
        LocalDate start = deal.getSubscriptionStart();
        if (start == null) {
            return Long.MAX_VALUE;
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(today, start);
        return days >= 0 ? days : 1_000_000L - days;
    }

    /** 지표나 확정 공모가가 비어 있고 청약이 최근이거나 다가오는 공모주 딜. */
    static boolean needsWork(IpoDealEntity deal, LocalDate today) {
        if (deal.isRightsOffering() || (deal.getMetricsSource() != null && deal.getOfferPriceFinal() != null
                && !wantsFloat(deal))) {
            return false; // 유상증자, 또는 지표(자동·수동)·확정가·유통가능물량(스팩 아닌 공모주만)을 다 채웠다
        }
        LocalDate start = deal.getSubscriptionStart();
        if (start != null) {
            // 청약이 멀면 확정 신고서가 아직 없다 — 매 배치 상한을 헛되이 쓰지 않는다
            return !start.isBefore(today.minusDays(RECENT_DAYS)) && !start.isAfter(today.plusDays(CONFIRM_LOOKAHEAD_DAYS));
        }
        return IpoOfferingClassifier.rceptDate(deal.getRceptNo())
                .map(filed -> !filed.isBefore(today.minusDays(RECENT_DAYS)))
                .orElse(false);
    }

    /**
     * 유통가능물량을 더 찾아야 하는 딜 — 아직 비어 있는 공모주. 스팩은 뺀다(2026-10-16): 스팩 신고서에는 그 문장이 없어
     * 매일 "찾지 못함"만 남았고, 스팩은 청약 추천 대상도 아니다({@link IpoSyncScheduler#isSpac}).
     */
    static boolean wantsFloat(IpoDealEntity deal) {
        return deal.getFloatRatio() == null && deal.getOfferingKind() == OfferingKind.IPO
                && !IpoSyncScheduler.isSpac(deal.getCorpName());
    }

    private Result collectCorp(String corpCode, List<IpoDealEntity> pending, LocalDate today) {
        boolean metricsWanted = pending.stream().anyMatch(d -> d.getMetricsSource() == null);
        boolean priceWanted = pending.stream().anyMatch(d -> d.getOfferPriceFinal() == null);
        boolean floatWanted = pending.stream().anyMatch(IpoDemandForecastCollector::wantsFloat);
        LocalDate firstFiled = pending.stream()
                .map(d -> IpoOfferingClassifier.rceptDate(d.getRceptNo()).orElse(today))
                .min(Comparator.naturalOrder())
                .orElse(today);
        List<DartClient.DealNotice> confirmed = dartClient.fetchCorpEquityFilings(corpCode,
                        firstFiled.minusDays(FILING_SEARCH_MARGIN_DAYS), today).stream()
                .filter(f -> f.reportName() != null && f.reportName().contains(DartClient.CONFIRMED_TERMS_PREFIX))
                .sorted(Comparator.comparing(DartClient.DealNotice::rceptNo).reversed())
                .toList();
        int floats = floatWanted && !today.equals(floatTried.get(corpCode)) ? collectFloat(corpCode, confirmed, today) : 0;
        if (confirmed.isEmpty()) {
            log.debug("[발행조건확정] 신고서 아직 없음(수요예측 전) — {}", pending.get(0).getCorpName());
            return new Result(0, 0, floats);
        }
        int metrics = 0;
        int prices = 0;
        int read = Math.min(DOCUMENTS_PER_CORP, confirmed.size());
        for (DartClient.DealNotice filing : confirmed.subList(0, read)) {
            Optional<String> xml = dartClient.fetchDocument(filing.rceptNo());
            if (xml.isEmpty()) {
                continue;
            }
            if (metricsWanted) {
                Optional<DemandForecastParser.DemandForecast> forecast = DemandForecastParser.parse(xml.get());
                if (forecast.isPresent()) {
                    apply(corpCode, forecast.get(), filing);
                    metrics = 1;
                    metricsWanted = false;
                } else {
                    log.info("[발행조건확정] 신고서에 수요예측 결과 표 없음 — {} rceptNo={} (다음 건 확인)",
                            filing.corpName(), filing.rceptNo());
                }
            }
            if (priceWanted) {
                Optional<OfferingPriceParser.OfferingPrice> price = OfferingPriceParser.parse(DartDocument.plainText(xml.get()));
                if (price.isPresent()) {
                    applyPrice(corpCode, price.get(), filing);
                    prices = 1;
                    priceWanted = false;
                }
            }
            if (!metricsWanted && !priceWanted) {
                break;
            }
        }
        if (metricsWanted) {
            log.warn("[발행조건확정] 신고서 {}건에서 수요예측 결과를 읽지 못함 — {} (수동 입력 필요)", read, pending.get(0).getCorpName());
        }
        if (priceWanted) {
            log.info("[발행조건확정] 신고서 {}건에서 확정 공모가 문장을 찾지 못함 — {} (다음 배치에서 다시)", read, pending.get(0).getCorpName());
        }
        return new Result(metrics, prices, floats);
    }

    /**
     * 상장 직후 유통가능물량 — 회사의 증권신고서(정정 포함, [발행조건확정] 제외)를 최신부터 {@value #FLOAT_DOCUMENTS}건까지 읽는다.
     *
     * @return 넣었으면 1
     */
    private int collectFloat(String corpCode, List<DartClient.DealNotice> confirmed, LocalDate today) {
        java.util.Set<String> confirmedNos = new java.util.HashSet<>();
        confirmed.forEach(c -> confirmedNos.add(c.rceptNo()));
        List<IpoDealEntity> deals = repository.findByCorpCode(corpCode).stream().filter(d -> !d.isRightsOffering()).toList();
        List<String> sources = deals.stream().map(IpoDealEntity::getRceptNo)
                .filter(no -> !confirmedNos.contains(no)).distinct()
                .sorted(Comparator.reverseOrder()).limit(FLOAT_DOCUMENTS).toList();
        for (String rceptNo : sources) {
            Optional<String> xml = dartClient.fetchDocument(rceptNo);
            if (xml.isEmpty()) {
                continue;   // 첨부정정 등 원본 없음
            }
            Optional<FloatSharesParser.FloatShares> parsed = FloatSharesParser.parse(DartDocument.plainText(xml.get()));
            if (parsed.isEmpty()) {
                continue;
            }
            FloatSharesParser.FloatShares fs = parsed.get();
            int updated = 0;
            for (IpoDealEntity deal : deals) {
                if (deal.applyFloat(fs.ratio(), fs.listedShares(), rceptNo, clock.instant())) {
                    try {
                        repository.save(deal);
                        updated++;
                    } catch (OptimisticLockingFailureException e) {
                        log.info("유통가능물량 저장 생략(동시 수동 입력, 다음 배치 재시도) — {}", deal.getCorpName());
                    }
                }
            }
            log.info("상장 직후 유통가능물량 자동 입력: {} — {}(상장예정 {}주), 딜 {}건 (rceptNo={})", deals.get(0).getCorpName(),
                    percentText(fs.ratio()), fs.listedShares() == null ? "미확인" : String.format(java.util.Locale.ROOT, "%,d", fs.listedShares()),
                    updated, rceptNo);
            return 1;
        }
        floatTried.put(corpCode, today);
        log.info("증권신고서 {}건에서 상장 직후 유통가능물량 문장을 찾지 못함 — {} (내일 다시, 추천은 유통 항목 0점)", sources.size(),
                deals.isEmpty() ? corpCode : deals.get(0).getCorpName());
        return 0;
    }

    private void applyPrice(String corpCode, OfferingPriceParser.OfferingPrice price, DartClient.DealNotice filing) {
        int updated = 0;
        for (IpoDealEntity deal : repository.findByCorpCode(corpCode)) {
            if (deal.isRightsOffering()) {
                continue;
            }
            if (deal.applyFinalPrice(price.finalPrice(), price.bandLow(), price.bandHigh(), clock.instant())) {
                try {
                    repository.save(deal);
                    updated++;
                } catch (OptimisticLockingFailureException e) {
                    log.info("확정 공모가 저장 생략(동시 수동 입력, 다음 배치 재시도) — {}", deal.getCorpName());
                }
            }
        }
        String band = price.bandLow() == null ? "희망 밴드 문장 없음"
                : "희망 %s~%s, %s".formatted(IpoAlertPlanner.won(price.bandLow()), IpoAlertPlanner.won(price.bandHigh()),
                price.positionInBand());
        log.info("확정 공모가 자동 입력: {} — {}({}), 딜 {}건 (rceptNo={})", filing.corpName(),
                IpoAlertPlanner.won(price.finalPrice()), band, updated, filing.rceptNo());
    }

    private void apply(String corpCode, DemandForecastParser.DemandForecast forecast, DartClient.DealNotice filing) {
        int updated = 0;
        for (IpoDealEntity deal : repository.findByCorpCode(corpCode)) {
            if (deal.isRightsOffering()) {
                continue;
            }
            if (deal.applyDemandForecast(forecast.competitionRate(), forecast.lockupCommitRate(),
                    filing.rceptNo(), clock.instant())) {
                try {
                    repository.save(deal);
                    updated++;
                } catch (OptimisticLockingFailureException e) {
                    log.info("수요예측 지표 저장 생략(동시 수동 입력, 다음 배치 재시도) — {}", deal.getCorpName());
                }
            }
        }
        log.info("수요예측 결과 자동 입력: {} — 기관경쟁률 {}, 의무보유확약비율 {}(수량 기준, 확약 {}주 / 신청 {}주), 딜 {}건 (rceptNo={})",
                filing.corpName(), rateText(forecast.competitionRate()), percentText(forecast.lockupCommitRate()),
                forecast.committedQuantity(), forecast.totalQuantity(), updated, filing.rceptNo());
    }

    static String rateText(BigDecimal rate) {
        return rate == null ? "미확인" : String.format(java.util.Locale.ROOT, "%,.2f:1", rate);
    }

    static String percentText(BigDecimal ratio) {
        return ratio == null ? "미확인" : ratio.movePointRight(2).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }
}
