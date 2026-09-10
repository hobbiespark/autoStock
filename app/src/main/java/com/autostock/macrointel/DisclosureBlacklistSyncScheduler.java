package com.autostock.macrointel;

import com.autostock.common.event.DisclosureRisk;
import com.autostock.common.util.MarketConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * DART 주요사항(유상증자·CB·BW·EB 발행 결정) 감시 배치 (PLAN.md ADR-14, 트랙 G1).
 *
 * <p>평일 08:35 KST — {@code ipo.IpoSyncScheduler}(08:20)·{@code macrointel.MacroSyncScheduler}
 * (08:30)보다 뒤에 둔 이유는 같은 macrointel 모듈 안에서 두 배치가 같은 분에 몰리지 않게
 * 여유를 둔 것뿐이다(특별한 순서 의존성은 없다 — 서로 다른 데이터 소스).
 *
 * <p>절차: 최근 {@code macrointel.blacklist.lookback-days}일 list.json(주요사항보고서) 조회 →
 * stock_code 있는 건만 → {@link DisclosureRisk} 이벤트 발행(만료일 = 공시일 +
 * {@code retentionDays}). <b>이 모듈은 블랙리스트에 실제로 무엇을 넣을지 판단하지 않는다</b> —
 * risk.DisclosureBlacklist가 이 이벤트를 구독해 등록 여부(멱등)·영속화·RiskGate 연동을 전부
 * 책임진다(risk/package-info.java "한도·차단 판단은 risk 소유" 원칙, macrointel/package-info.java
 * "수집·발행까지만" 원칙 — macrointel → risk 타입 의존은 전혀 없다).
 *
 * <p><b>딜 단위 실패 격리</b>({@code ipo.IpoSyncScheduler}와 동일 정책): 한 공시 파싱/이벤트
 * 발행 실패가 다른 공시 처리를 막지 않는다.
 */
@Component
public class DisclosureBlacklistSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(DisclosureBlacklistSyncScheduler.class);

    private final DisclosureBlacklistProperties properties;
    private final MajorDisclosureDartClient dartClient;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    public DisclosureBlacklistSyncScheduler(DisclosureBlacklistProperties properties,
                                             MajorDisclosureDartClient dartClient,
                                             ApplicationEventPublisher publisher,
                                             Clock clock) {
        this.properties = properties;
        this.dartClient = dartClient;
        this.publisher = publisher;
        this.clock = clock;
    }

    /** 매 평일 08:35 KST 1회 실행. */
    @Scheduled(cron = "0 35 8 * * MON-FRI", zone = "Asia/Seoul")
    public void syncScheduled() {
        syncNow();
    }

    /** 수동 트리거 — {@code IpoSyncScheduler.syncNow}와 같은 이유로 public. */
    public void syncNow() {
        if (!properties.enabled()) {
            log.info("공시 블랙리스트 수집 비활성(macrointel.blacklist.enabled=false) — 이번 배치 스킵");
            return;
        }
        LocalDate today = LocalDate.now(clock.withZone(MarketConstants.KST));
        LocalDate since = today.minusDays(properties.lookbackDays());

        List<MajorDisclosureDartClient.MajorDisclosureNotice> notices =
                dartClient.fetchRecentIssuanceDecisions(since, today);
        for (MajorDisclosureDartClient.MajorDisclosureNotice notice : notices) {
            try {
                publishIfListed(notice);
            } catch (RuntimeException e) {
                log.error("공시 블랙리스트 이벤트 발행 실패(스킵) — corpName={}, rceptNo={}",
                        notice.corpName(), notice.rceptNo(), e);
            }
        }
    }

    private void publishIfListed(MajorDisclosureDartClient.MajorDisclosureNotice notice) {
        String stockCode = notice.stockCode();
        if (stockCode == null || stockCode.isBlank()) {
            log.debug("종목코드 없음(비상장) — 블랙리스트 대상 아님, 스킵: corpName={}, rceptNo={}",
                    notice.corpName(), notice.rceptNo());
            return;
        }
        LocalDate expiresOn = notice.rceptDt().plusDays(properties.retentionDays());
        publisher.publishEvent(new DisclosureRisk(
                stockCode, notice.corpName(), notice.type().name(), notice.rceptNo(),
                notice.rceptDt(), expiresOn, Instant.now()));
        log.info("공시 리스크 이벤트 발행: {}({}) — {}, 만료 {}",
                notice.corpName(), stockCode, notice.type().label(), expiresOn);
    }
}
