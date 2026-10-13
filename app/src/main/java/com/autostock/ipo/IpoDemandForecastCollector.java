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
 *   <li>범위: 청약 시작이 {@value #RECENT_DAYS}일 이내로 지났거나 아직 오지 않은 딜, 일정을 모르면 딜이 생긴 지
 *       {@value #RECENT_DAYS}일 이내. 오래된 딜을 매일 다시 찾지 않는다.</li>
 *   <li>한 배치에 최대 {@value #CORPS_PER_RUN}개 회사. 회사마다 발행공시 목록 1회 + 원본 1~{@value #DOCUMENTS_PER_CORP}건.</li>
 *   <li>[발행조건확정]이 여러 건이면 최신부터 읽는다 — 수요예측 표가 빠진 확정 신고서(실측: 진코스텍 9/28 첫 건)는 건너뛴다.</li>
 *   <li>같은 회사의 딜(정정 신고서마다 한 행)에 같은 값을 넣는다.</li>
 * </ul>
 *
 * <p>[발행조건확정]은 보통 수요예측 마감일 저녁에 나오고 청약은 그 2~3일 뒤다 — 다음 날 08:20 배치가 읽으면 청약 D-1 알림에
 * 권고가 실린다.
 */
class IpoDemandForecastCollector {

    private static final Logger log = LoggerFactory.getLogger(IpoDemandForecastCollector.class);

    static final int RECENT_DAYS = 45;
    static final int CORPS_PER_RUN = 10;
    static final int DOCUMENTS_PER_CORP = 3;
    /** 회사의 첫 신고서보다 이만큼 앞부터 발행공시를 찾는다(접수번호 날짜 기준). */
    private static final int FILING_SEARCH_MARGIN_DAYS = 7;

    private final DartClient dartClient;
    private final IpoDealRepository repository;
    private final Clock clock;

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
     */
    record Result(int metricsFilled, int pricesFilled) {
        static final Result NONE = new Result(0, 0);
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
        int tried = 0;
        for (Map.Entry<String, List<IpoDealEntity>> corp : byCorp.entrySet()) {
            if (tried++ >= CORPS_PER_RUN) {
                break;
            }
            Result filled = collectCorp(corp.getKey(), corp.getValue(), today);
            metrics += filled.metricsFilled();
            prices += filled.pricesFilled();
        }
        return new Result(metrics, prices);
    }

    /** 정렬 열쇠 — 다가오는 청약(가까운 순) &lt; 지난 청약(최근 순) &lt; 일정 모름. */
    private static long urgency(IpoDealEntity deal, LocalDate today) {
        LocalDate start = deal.getSubscriptionStart();
        if (start == null) {
            return Long.MAX_VALUE;
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(today, start);
        return days >= 0 ? days : 1_000_000L - days;
    }

    /** 지표나 확정 공모가가 비어 있고 청약이 최근이거나 다가오는 공모주 딜. */
    private boolean needsWork(IpoDealEntity deal, LocalDate today) {
        if (deal.isRightsOffering() || (deal.getMetricsSource() != null && deal.getOfferPriceFinal() != null)) {
            return false; // 유상증자, 또는 지표(자동·수동)와 확정가를 다 채웠다
        }
        LocalDate start = deal.getSubscriptionStart();
        if (start != null) {
            return !start.isBefore(today.minusDays(RECENT_DAYS));
        }
        return IpoOfferingClassifier.rceptDate(deal.getRceptNo())
                .map(filed -> !filed.isBefore(today.minusDays(RECENT_DAYS)))
                .orElse(false);
    }

    private Result collectCorp(String corpCode, List<IpoDealEntity> pending, LocalDate today) {
        boolean metricsWanted = pending.stream().anyMatch(d -> d.getMetricsSource() == null);
        boolean priceWanted = pending.stream().anyMatch(d -> d.getOfferPriceFinal() == null);
        LocalDate firstFiled = pending.stream()
                .map(d -> IpoOfferingClassifier.rceptDate(d.getRceptNo()).orElse(today))
                .min(Comparator.naturalOrder())
                .orElse(today);
        List<DartClient.DealNotice> confirmed = dartClient.fetchCorpEquityFilings(corpCode,
                        firstFiled.minusDays(FILING_SEARCH_MARGIN_DAYS), today).stream()
                .filter(f -> f.reportName() != null && f.reportName().contains(DartClient.CONFIRMED_TERMS_PREFIX))
                .sorted(Comparator.comparing(DartClient.DealNotice::rceptNo).reversed())
                .toList();
        if (confirmed.isEmpty()) {
            log.debug("[발행조건확정] 신고서 아직 없음(수요예측 전) — {}", pending.get(0).getCorpName());
            return Result.NONE;
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
        return new Result(metrics, prices);
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
