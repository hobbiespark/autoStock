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
 * <ul>
 *   <li>대상: 공모주(유상증자 제외)이면서 지표가 아직 없는 딜. 사람이 넣은 지표(MANUAL)는 덮지 않는다.</li>
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

    /** 자동 입력한 회사 수를 돌려준다. */
    int collect(LocalDate today) {
        Map<String, List<IpoDealEntity>> byCorp = new LinkedHashMap<>();
        repository.findAll().stream()
                .filter(deal -> needsMetrics(deal, today))
                .sorted(Comparator.comparing(IpoDealEntity::getSubscriptionStart,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(deal -> byCorp.computeIfAbsent(deal.getCorpCode(), k -> new java.util.ArrayList<>()).add(deal));
        int filled = 0;
        int tried = 0;
        for (Map.Entry<String, List<IpoDealEntity>> corp : byCorp.entrySet()) {
            if (tried++ >= CORPS_PER_RUN) {
                break;
            }
            if (collectCorp(corp.getKey(), corp.getValue(), today)) {
                filled++;
            }
        }
        return filled;
    }

    private boolean needsMetrics(IpoDealEntity deal, LocalDate today) {
        if (deal.isRightsOffering() || deal.getMetricsSource() != null) {
            return false; // 유상증자, 또는 이미 자동·수동으로 채웠다
        }
        LocalDate start = deal.getSubscriptionStart();
        if (start != null) {
            return !start.isBefore(today.minusDays(RECENT_DAYS));
        }
        return IpoOfferingClassifier.rceptDate(deal.getRceptNo())
                .map(filed -> !filed.isBefore(today.minusDays(RECENT_DAYS)))
                .orElse(false);
    }

    private boolean collectCorp(String corpCode, List<IpoDealEntity> pending, LocalDate today) {
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
            return false;
        }
        for (DartClient.DealNotice filing : confirmed.subList(0, Math.min(DOCUMENTS_PER_CORP, confirmed.size()))) {
            Optional<DemandForecastParser.DemandForecast> forecast = dartClient.fetchDocument(filing.rceptNo())
                    .flatMap(DemandForecastParser::parse);
            if (forecast.isEmpty()) {
                log.info("[발행조건확정] 신고서에 수요예측 결과 표 없음 — {} rceptNo={} (다음 건 확인)",
                        filing.corpName(), filing.rceptNo());
                continue;
            }
            apply(corpCode, forecast.get(), filing);
            return true;
        }
        log.warn("[발행조건확정] 신고서 {}건에서 수요예측 결과를 읽지 못함 — {} (수동 입력 필요)",
                Math.min(DOCUMENTS_PER_CORP, confirmed.size()), pending.get(0).getCorpName());
        return false;
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
