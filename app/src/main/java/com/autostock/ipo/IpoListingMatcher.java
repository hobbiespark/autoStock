package com.autostock.ipo;

import com.autostock.common.event.RecentListings;
import com.autostock.common.util.StockNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 최근 상장 종목({@link RecentListings})을 공모주 딜과 회사명으로 맞춰 상장일·종목코드를 남긴다(2026-10-13).
 *
 * <p>맞추는 규칙 — 틀린 종목에 붙이느니 비워 두고 사람이 넣게 한다:
 * <ul>
 *   <li>이름: 회사명에서 "주식회사"·"(주)"·"㈜"·띄어쓰기·문장부호를 빼고, "기업인수목적"을 거래소 약칭 "스팩"으로, 숫자 앞 "제"를 뺀 뒤 같아야
 *       한다(DART "한화플러스제6호기업인수목적" ↔ 거래소 "한화플러스6호스팩" 꼴).</li>
 *   <li>날짜: 상장일이 청약 마감일(모르면 첫 신고서 접수일) 이후 {@value #MAX_DAYS_AFTER}일 안.</li>
 *   <li>후보가 정확히 하나일 때만. 둘 이상이면 경고만 남긴다.</li>
 *   <li>상장사 유상증자 딜, 이미 종목코드가 있는 회사는 보지 않는다.</li>
 * </ul>
 * 맞춘 회사의 모든 딜(정정 신고서마다 한 행)에 같은 값을 넣고, 종목명 사전({@link StockNames})에 거래소 종목명을 남긴다.
 */
class IpoListingMatcher {

    private static final Logger log = LoggerFactory.getLogger(IpoListingMatcher.class);

    /** 상장일이 청약 마감(또는 첫 신고) 뒤 이 일수 안이어야 같은 공모로 본다. */
    static final int MAX_DAYS_AFTER = 90;
    /** 맞춘 딜이 없는 최근 상장 종목을 로그로 알리는 범위(오늘부터 앞으로 며칠) — 이름 규칙을 다듬을 근거. */
    private static final int UNMATCHED_LOG_DAYS = 3;

    private final IpoDealRepository repository;
    private final Clock clock;

    IpoListingMatcher(IpoDealRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** @return 이번에 상장을 확인한 회사 수 */
    int apply(List<RecentListings.Listing> listings, LocalDate today) {
        Map<String, List<RecentListings.Listing>> byName = listings.stream()
                .filter(l -> l.name() != null && l.listedOn() != null)
                .collect(Collectors.groupingBy(l -> normalize(l.name()), LinkedHashMap::new, Collectors.toList()));
        Map<String, List<IpoDealEntity>> byCorp = repository.findAll().stream()
                .filter(deal -> !deal.isRightsOffering())
                .collect(Collectors.groupingBy(IpoDealEntity::getCorpCode, LinkedHashMap::new, Collectors.toList()));
        Set<String> matchedCodes = new HashSet<>();
        int matched = 0;
        for (List<IpoDealEntity> deals : byCorp.values()) {
            if (deals.stream().allMatch(d -> d.getStockCode() != null)) {
                deals.forEach(d -> matchedCodes.add(d.getStockCode()));
                continue;   // 이미 맞췄다
            }
            IpoDealEntity latest = deals.stream().max(Comparator.comparing(IpoDealEntity::getRceptNo)).orElseThrow();
            LocalDate from = lowerBound(deals, today);
            List<RecentListings.Listing> candidates = byName.getOrDefault(normalize(latest.getCorpName()), List.of()).stream()
                    .filter(l -> !l.listedOn().isBefore(from) && !l.listedOn().isAfter(from.plusDays(MAX_DAYS_AFTER)))
                    .toList();
            if (candidates.size() > 1) {
                log.warn("공모주 상장 확인 보류 — {}와 이름이 같은 최근 상장 종목이 {}개: {} (상장일은 화면에서 직접 넣는다)",
                        latest.getCorpName(), candidates.size(), candidates);
                continue;
            }
            if (candidates.isEmpty()) {
                continue;
            }
            RecentListings.Listing listing = candidates.get(0);
            if (applyToCorp(deals, listing)) {
                matched++;
                matchedCodes.add(listing.stockCode());
                StockNames.learn(listing.stockCode(), listing.name(), StockNames.Source.KIWOOM);
            }
        }
        logUnmatched(listings, matchedCodes, today);
        return matched;
    }

    private boolean applyToCorp(List<IpoDealEntity> deals, RecentListings.Listing listing) {
        int saved = 0;
        LocalDate keptDate = null;
        for (IpoDealEntity deal : deals) {
            IpoDealEntity.ListingUpdate update = deal.applyListing(listing.stockCode(), listing.listedOn(), clock.instant());
            if (update == IpoDealEntity.ListingUpdate.UNCHANGED) {
                continue;
            }
            if (update == IpoDealEntity.ListingUpdate.KEPT_MANUAL_DATE) {
                keptDate = deal.getListingDate();
            }
            try {
                repository.save(deal);
                saved++;
            } catch (OptimisticLockingFailureException e) {
                log.info("공모주 상장 확인 저장 생략(동시 수동 입력, 다음 확인 때 다시) — {}", deal.getCorpName());
            }
        }
        String corpName = deals.get(0).getCorpName();
        if (keptDate != null) {
            log.warn("공모주 상장 확인: {} — 종목코드 {}만 남김. 이미 있는 상장일 {}가 키움 상장일 {}와 다르다(있는 값 유지 — 확인 필요)",
                    corpName, listing.stockCode(), keptDate, listing.listedOn());
        } else if (saved > 0) {
            log.info("공모주 상장 확인: {} → {}({}) {} 상장({}), 딜 {}건", corpName, listing.name(), listing.stockCode(),
                    listing.listedOn(), listing.marketName(), saved);
        }
        return saved > 0;
    }

    /** 상장일 하한 — 청약 마감일, 모르면 첫 신고서 접수일, 그것도 모르면 오늘보다 {@value #MAX_DAYS_AFTER}일 전. */
    private static LocalDate lowerBound(List<IpoDealEntity> deals, LocalDate today) {
        return deals.stream().map(IpoDealEntity::getSubscriptionEnd).filter(Objects::nonNull).max(Comparator.naturalOrder())
                .or(() -> deals.stream().map(d -> IpoOfferingClassifier.rceptDate(d.getRceptNo()).orElse(null))
                        .filter(Objects::nonNull).min(Comparator.naturalOrder()))
                .orElse(today.minusDays(MAX_DAYS_AFTER));
    }

    private static void logUnmatched(List<RecentListings.Listing> listings, Set<String> matchedCodes, LocalDate today) {
        List<String> unmatched = listings.stream()
                .filter(l -> l.listedOn() != null && !l.listedOn().isBefore(today)
                        && !l.listedOn().isAfter(today.plusDays(UNMATCHED_LOG_DAYS)))
                .filter(l -> !matchedCodes.contains(l.stockCode()))
                .map(l -> "%s(%s, %s)".formatted(l.name(), l.stockCode(), l.listedOn()))
                .toList();
        if (!unmatched.isEmpty()) {
            log.info("오늘·곧 상장하지만 공모주 딜과 맞춘 회사 없음(공모 없는 이전상장·분할 등이거나 이름이 다름): {}", unmatched);
        }
    }

    /** 회사명 비교용 — 법인 표기·띄어쓰기·문장부호를 빼고, "기업인수목적"→"스팩", 숫자 앞 "제" 제거, 영문은 대문자. */
    static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String s = name.replace("주식회사", "").replace("(주)", "").replace("㈜", "").replace("（주）", "");
        s = s.replace("기업인수목적", "스팩");
        s = s.replaceAll("[\\s·.,()\\[\\]\\-_&']", "");
        s = s.replaceAll("제(?=\\d)", "");
        return s.toUpperCase(Locale.ROOT);
    }
}
