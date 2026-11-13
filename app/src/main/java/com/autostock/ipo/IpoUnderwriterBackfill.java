package com.autostock.ipo;

import com.autostock.common.util.MarketConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 지난 공모의 인수인을 DART에서 거꾸로 채운다(2026-11-13 사용자 "지난 1,3,5년 ipo를 많이 했다던지 하는 근거로 추천", 결정 D-29).
 *
 * <p>첫 실행 날부터 {@code years}년 전까지, 2주 창씩 과거로 거슬러 {@code list.json}(지분증권 신고서)을 읽고 회사마다
 * {@code estkRs.json}으로 공모 종류와 인수인을 받아 {@code ipo_underwriting_history}에 남긴다. 5년이면 호출이 수천 번이라 한 번에 하지 않고
 * 밤(00~06시) 매시 {@code windowsPerPass}창씩 나눠 진행 위치({@code ipo_backfill_progress})를 저장한다 — 재기동해도 이어 간다.
 *
 * <ul>
 *   <li>목록을 끝까지 읽지 못한 창은 진행 위치를 옮기지 않는다(다음 번에 같은 창을 다시 읽음).</li>
 *   <li>공모 종류가 {@link OfferingKind#IPO}로 판정된 회사만 남긴다(유상증자·판정 불가 제외). 이미 남긴 회사는 다시 조회하지 않는다.</li>
 *   <li>{@code estkRs.json}은 회사의 최신 신고서 한 건만 돌려주고 [발행조건확정] 접수번호로는 행이 없어, 그 회사의 다른 접수번호를 최신부터 시도한다.</li>
 * </ul>
 * 주문·거래와 관계없다. 실패는 로그로만 남기고 밖으로 던지지 않는다.
 */
@Component
public class IpoUnderwriterBackfill {

    private static final Logger log = LoggerFactory.getLogger(IpoUnderwriterBackfill.class);

    static final String PROGRESS_NAME = "underwriters";
    static final int WINDOW_DAYS = 14;

    private final DartClient dart;
    private final DartProperties dartProperties;
    private final IpoBackfillProperties properties;
    private final IpoUnderwritingHistoryRepository history;
    private final IpoBackfillProgressRepository progress;
    private final Clock clock;

    public IpoUnderwriterBackfill(DartClient dart, DartProperties dartProperties, IpoBackfillProperties properties,
                                  IpoUnderwritingHistoryRepository history, IpoBackfillProgressRepository progress, Clock clock) {
        this.dart = dart;
        this.dartProperties = dartProperties;
        this.properties = properties;
        this.history = history;
        this.progress = progress;
        this.clock = clock;
    }

    /** 밤 00~06시 매시 40분(낮 배치·매시 점검과 겹치지 않게). */
    @Scheduled(cron = "0 40 0-6 * * *", zone = "Asia/Seoul")
    public void scheduledPass() {
        if (!properties.enabled() || !dartProperties.enabled()) {
            return;
        }
        try {
            pass();
        } catch (RuntimeException e) {
            log.error("공모 인수인 거꾸로 채우기 실패", e);
        }
    }

    /** 창 {@code windowsPerPass}개를 읽는다. 끝났거나 목록을 다 읽지 못하면 멈춘다. 읽은 창 수를 돌려준다. */
    @Transactional
    public int pass() {
        LocalDate today = LocalDate.now(clock.withZone(MarketConstants.KST));
        IpoBackfillProgressEntity state = progress.findById(PROGRESS_NAME)
                .orElseGet(() -> new IpoBackfillProgressEntity(PROGRESS_NAME, today.minusYears(properties.years()),
                        today.plusDays(1), clock.instant()));
        int windows = 0;
        while (windows < properties.windowsPerPass() && !state.done()) {
            LocalDate until = state.getCoveredFrom().minusDays(1);
            LocalDate since = until.minusDays(WINDOW_DAYS - 1);
            if (since.isBefore(state.getTargetFrom())) {
                since = state.getTargetFrom();
            }
            DartClient.RecentFilings filings = dart.fetchRecentEquityFilings(since, until);
            if (!filings.complete()) {
                log.warn("공모 인수인 거꾸로 채우기: {}~{} 목록을 끝까지 읽지 못함({}/{}쪽) — 다음 번에 같은 창을 다시 읽음",
                        since, until, filings.pagesRead(), filings.totalPages());
                break;
            }
            int saved = saveWindow(filings.deals(), since, until);
            state.advance(since, clock.instant());
            windows++;
            log.info("공모 인수인 거꾸로 채우기: {}~{} 신고 {}건, 공모 {}곳 저장 (목표 {}부터)",
                    since, until, filings.deals().size(), saved, state.getTargetFrom());
        }
        progress.save(state);
        return windows;
    }

    /** 창 하나의 신고를 회사별로 묶어, 아직 없는 회사만 조회해 남긴다. 저장한 회사 수. */
    private int saveWindow(List<DartClient.DealNotice> deals, LocalDate since, LocalDate until) {
        Map<String, List<DartClient.DealNotice>> byCorp = deals.stream()
                .collect(Collectors.groupingBy(DartClient.DealNotice::corpCode, LinkedHashMap::new, Collectors.toList()));
        int saved = 0;
        for (Map.Entry<String, List<DartClient.DealNotice>> entry : byCorp.entrySet()) {
            if (history.findByCorpCode(entry.getKey()).isPresent()) {
                continue;   // 더 최근 창에서 이미 남김(거꾸로 읽으므로 먼저 남긴 쪽이 더 나중 공모)
            }
            List<DartClient.DealNotice> notices = entry.getValue().stream()
                    .filter(n -> n.reportName() == null || !n.reportName().startsWith(DartClient.CONFIRMED_TERMS_PREFIX))
                    .sorted(Comparator.comparing(DartClient.DealNotice::rceptDt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
            for (DartClient.DealNotice notice : notices) {
                Optional<DartClient.OfferingLookup> lookup = dart.fetchOffering(notice.corpCode(), notice.rceptNo(), since,
                        until.plusDays(IpoOfferingClassifier.LOOKUP_DAYS));
                if (lookup.isEmpty() || lookup.get().kind() == OfferingKind.RIGHTS) {
                    break;   // 조회 실패이거나 유상증자 — 이 회사는 건너뜀
                }
                DartClient.OfferingDetail detail = lookup.get().detail();
                if (detail == null || detail.underwriters().isEmpty()) {
                    continue;   // 이 접수번호에는 행이 없음 — 다른 접수번호
                }
                if (lookup.get().kind() != OfferingKind.IPO) {
                    break;   // 공모 종류를 판정하지 못함 — 세지 않음
                }
                history.save(new IpoUnderwritingHistoryEntity(notice.corpCode(), notice.corpName(), notice.rceptNo(),
                        notice.rceptDt() != null ? notice.rceptDt() : until, detail.subscriptionStart(), detail.underwriters(),
                        clock.instant()));
                saved++;
                break;
            }
        }
        return saved;
    }

    /** 채운 범위 — 알림 문구에 쓴다. 아직 시작 전이면 빈 값. */
    @Transactional(readOnly = true)
    public Optional<IpoBackfillProgressEntity> coverage() {
        return progress.findById(PROGRESS_NAME);
    }
}
