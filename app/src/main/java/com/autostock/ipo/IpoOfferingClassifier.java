package com.autostock.ipo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 공모 종류 판정 — 공모주(IPO)와 상장사 유상증자(RIGHTS)를 가른다({@link OfferingKind}, aiDoc/ipo-demand-forecast.md).
 *
 * <p>판정 순서: ① 증권신고서 주요정보(estkRs.json, 배치가 상세를 받으며 함께 얻음) → ② 이미 판정된 딜 → ③ 같은 회사의 다른
 * 딜(정정 신고서마다 한 행이라 같은 공모다) → ④ 공시 본문 핵심어(처음 보는 딜만 — 원본을 받아야 해서). 판정되면 같은
 * 회사의 판정 전 딜에도 남긴다.
 *
 * <p>V11 이전에 들어온 딜(판정 전)은 배치마다 최대 {@value #BACKFILL_CORPS_PER_RUN}개 회사씩 판정한다.
 */
class IpoOfferingClassifier {

    private static final Logger log = LoggerFactory.getLogger(IpoOfferingClassifier.class);

    /** 주요정보 조회 기간 — estkRs는 회사의 최신 신고서 접수일이 기간에 걸려야 행을 준다(DartClient Javadoc). */
    static final int LOOKUP_DAYS = 120;
    /** 한 배치에 판정하는 옛 딜의 회사 수 상한 — 외부 호출을 하루 몇십 건 안으로 둔다. */
    static final int BACKFILL_CORPS_PER_RUN = 10;

    private static final DateTimeFormatter RCEPT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final DartClient dartClient;
    private final IpoDealRepository repository;
    private final Clock clock;

    IpoOfferingClassifier(DartClient dartClient, IpoDealRepository repository, Clock clock) {
        this.dartClient = dartClient;
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 배치가 받은 공시 한 건의 공모 종류. 판정하지 못하면 null(공모주로 취급 — 예전 동작).
     *
     * @param lookupKind 이번 배치의 주요정보 조회로 얻은 종류(없으면 null)
     * @param existing   이 접수번호로 이미 있는 딜
     */
    OfferingKind kindOf(DartClient.DealNotice deal, OfferingKind lookupKind, Optional<IpoDealEntity> existing) {
        if (lookupKind != null) {
            return lookupKind;
        }
        if (existing.isPresent() && existing.get().getOfferingKind() != null) {
            return existing.get().getOfferingKind();
        }
        OfferingKind sibling = siblingKind(deal.corpCode());
        if (sibling != null) {
            return sibling;
        }
        if (existing.isEmpty()) {
            return fromDocument(deal.rceptNo());
        }
        return null;
    }

    /** 같은 회사의 판정 전 딜에 판정을 남긴다. */
    void propagate(String corpCode, OfferingKind kind) {
        if (kind == null) {
            return;
        }
        for (IpoDealEntity sibling : repository.findByCorpCode(corpCode)) {
            if (sibling.getOfferingKind() == null) {
                sibling.classifyOffering(kind, clock.instant());
                saveQuietly(sibling);
            }
        }
    }

    /**
     * 판정 전 딜(V11 이전에 들어온 딜 등)을 회사 단위로 판정한다 — 최신 딜부터 최대 {@value #BACKFILL_CORPS_PER_RUN}개 회사.
     *
     * @param alreadyTried 이번 배치에서 이미 조회한 회사(같은 회사를 두 번 조회하지 않는다)
     * @return 이번에 판정한 딜 수
     */
    int backfill(LocalDate today, Set<String> alreadyTried) {
        Map<String, IpoDealEntity> latestByCorp = new LinkedHashMap<>();
        repository.findByOfferingKindIsNull().stream()
                .sorted(Comparator.comparing(IpoDealEntity::getRceptNo).reversed())
                .forEach(deal -> latestByCorp.putIfAbsent(deal.getCorpCode(), deal));
        int classified = 0;
        int tried = 0;
        for (IpoDealEntity latest : latestByCorp.values()) {
            if (tried >= BACKFILL_CORPS_PER_RUN) {
                break;
            }
            if (!alreadyTried.add(latest.getCorpCode())) {
                continue;
            }
            tried++;
            LocalDate filed = rceptDate(latest.getRceptNo()).orElse(today);
            OfferingKind kind = dartClient.fetchOffering(latest.getCorpCode(), latest.getRceptNo(),
                            filed.minusDays(LOOKUP_DAYS), today)
                    .map(DartClient.OfferingLookup::kind)
                    .orElse(null);
            if (kind == null) {
                kind = fromDocument(latest.getRceptNo());
            }
            if (kind == null) {
                log.info("공모 종류 판정 보류(주요정보·공시 본문 모두 근거 없음) — {} rceptNo={}", latest.getCorpName(), latest.getRceptNo());
                continue;
            }
            for (IpoDealEntity deal : repository.findByCorpCode(latest.getCorpCode())) {
                if (deal.getOfferingKind() == null) {
                    deal.classifyOffering(kind, clock.instant());
                    if (saveQuietly(deal)) {
                        classified++;
                    }
                }
            }
            if (kind == OfferingKind.RIGHTS) {
                log.info("상장사 유상증자로 판정 — 공모주 화면·권고·알림에서 뺀다: {}", latest.getCorpName());
            }
        }
        return classified;
    }

    /** 접수번호 앞 8자리(접수일). */
    static Optional<LocalDate> rceptDate(String rceptNo) {
        if (rceptNo == null || rceptNo.length() < 8) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(rceptNo.substring(0, 8), RCEPT_DATE));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private OfferingKind siblingKind(String corpCode) {
        return repository.findByCorpCode(corpCode).stream()
                .filter(d -> d.getOfferingKind() != null)
                .max(Comparator.comparing(IpoDealEntity::getRceptNo))
                .map(IpoDealEntity::getOfferingKind)
                .orElse(null);
    }

    private OfferingKind fromDocument(String rceptNo) {
        return dartClient.fetchDocument(rceptNo)
                .map(DartDocument::plainText)
                .map(OfferingKind::fromDocumentText)
                .orElse(null);
    }

    private boolean saveQuietly(IpoDealEntity deal) {
        try {
            repository.save(deal);
            return true;
        } catch (OptimisticLockingFailureException e) {
            log.info("공모 종류 저장 생략(동시 수동 입력, 다음 배치 재시도) — {}", deal.getCorpName());
            return false;
        }
    }
}
