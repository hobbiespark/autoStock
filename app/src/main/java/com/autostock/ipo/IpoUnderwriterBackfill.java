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

    /**
     * 진행 위치 이름. 2026-11-14 결함 수정(VALUNIVO 880)으로 {@code underwriters}(첫판, 공모 대부분을 놓침)에서 바꿔 처음부터 다시 훑는다.
     * 옛 행은 남겨 두고 쓰지 않는다(이미 저장한 회사는 건너뛰므로 지울 것이 없다).
     */
    static final String PROGRESS_NAME = "underwriters-v2";
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
            WindowResult result = saveWindow(filings.deals(), since, until);
            state.advance(since, clock.instant());
            windows++;
            log.info("공모 인수인 거꾸로 채우기: {}~{} 신고 {}건, 회사 {}곳 중 공모 {}곳 저장 · 이미 있음 {} · 유상증자 {} · 조회 빔 {} · "
                            + "상세 없음 {} · 종류 미판정 {} (목표 {}부터)",
                    since, until, filings.deals().size(), result.corps, result.saved, result.known, result.rights,
                    result.empty, result.noDetail, result.unknownKind, state.getTargetFrom());
        }
        progress.save(state);
        return windows;
    }

    /** 창 하나의 결과 — 회사마다 마지막으로 빠진 이유를 하나씩 센다(VALUNIVO 880 진단). */
    static final class WindowResult {
        int corps;
        int saved;
        int known;
        int rights;
        int empty;
        int noDetail;
        int unknownKind;
    }

    private enum Miss { EMPTY, NO_DETAIL, UNKNOWN_KIND }

    /**
     * 창 하나의 신고를 회사별로 묶어, 아직 없는 회사만 조회해 남긴다.
     *
     * <p>{@code estkRs.json}은 조회 기간의 <b>최신 신고서 한 건</b>만 돌려준다. 기간을 창 끝까지로 잡아야 이 창의 접수번호 행이 돌아온다
     * (첫판은 창 끝 + 120일로 잡아 창 밖의 더 나중 정정 신고서가 돌아와 행이 없었다). 결과가 비거나 상세·종류가 없으면 다음 접수번호를
     * 시도하고, 유상증자로 판정될 때만 그 회사를 건너뛴다.
     */
    private WindowResult saveWindow(List<DartClient.DealNotice> deals, LocalDate since, LocalDate until) {
        Map<String, List<DartClient.DealNotice>> byCorp = deals.stream()
                .collect(Collectors.groupingBy(DartClient.DealNotice::corpCode, LinkedHashMap::new, Collectors.toList()));
        WindowResult result = new WindowResult();
        result.corps = byCorp.size();
        for (Map.Entry<String, List<DartClient.DealNotice>> entry : byCorp.entrySet()) {
            if (history.findByCorpCode(entry.getKey()).isPresent()) {
                result.known++;   // 더 최근 창에서 이미 남김(거꾸로 읽으므로 먼저 남긴 쪽이 더 나중 공모)
                continue;
            }
            List<DartClient.DealNotice> notices = entry.getValue().stream()
                    .filter(n -> n.reportName() == null || !n.reportName().startsWith(DartClient.CONFIRMED_TERMS_PREFIX))
                    .sorted(Comparator.comparing(DartClient.DealNotice::rceptDt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
            Miss miss = Miss.EMPTY;
            boolean done = false;
            for (DartClient.DealNotice notice : notices) {
                Optional<DartClient.OfferingLookup> lookup = dart.fetchOffering(notice.corpCode(), notice.rceptNo(), since, until);
                if (lookup.isEmpty()) {
                    miss = Miss.EMPTY;
                    continue;   // 이 접수번호로는 아무것도 없음 — 다른 접수번호
                }
                if (lookup.get().kind() == OfferingKind.RIGHTS) {
                    result.rights++;
                    done = true;
                    break;   // 유상증자 — 이 회사는 건너뜀
                }
                DartClient.OfferingDetail detail = lookup.get().detail();
                if (detail == null || detail.underwriters().isEmpty()) {
                    miss = Miss.NO_DETAIL;
                    continue;   // 이 접수번호에는 행이 없음 — 다른 접수번호
                }
                if (lookup.get().kind() != OfferingKind.IPO) {
                    miss = Miss.UNKNOWN_KIND;
                    continue;   // 공모 종류를 판정하지 못함 — 다른 접수번호(모집방법이 실린 신고서가 있을 수 있음)
                }
                history.save(new IpoUnderwritingHistoryEntity(notice.corpCode(), notice.corpName(), notice.rceptNo(),
                        notice.rceptDt() != null ? notice.rceptDt() : until, detail.subscriptionStart(), detail.underwriters(),
                        clock.instant()));
                result.saved++;
                done = true;
                break;
            }
            if (!done) {
                switch (miss) {
                    case EMPTY -> result.empty++;
                    case NO_DETAIL -> result.noDetail++;
                    case UNKNOWN_KIND -> result.unknownKind++;
                }
            }
        }
        return result;
    }

    /** 채운 범위 — 알림 문구에 쓴다. 아직 시작 전이면 빈 값. */
    @Transactional(readOnly = true)
    public Optional<IpoBackfillProgressEntity> coverage() {
        return progress.findById(PROGRESS_NAME);
    }
}
