package com.autostock.risk;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 공시 블랙리스트 만료 자동 해제 배치 (PLAN.md ADR-14, 트랙 G1).
 *
 * <p>매일 08:00 KST — macrointel의 신규 등록 배치(08:35)보다 앞서 돌려, 오늘 자로 만료된
 * 종목을 먼저 정리한 뒤 그날의 신규 등록이 쌓이게 한다(순서가 바뀌어도 정합성에 영향은
 * 없다 — {@link DisclosureBlacklist#isBlacklisted}가 만료일을 그때그때 비교하므로 이 배치가
 * 늦게 돌아도 매수 차단 판단 자체는 항상 정확하다. 이 배치는 DB 행 정리·메모리 캐시 재구성
 * 용도다).
 *
 * <p>이 스케줄러를 risk 모듈 안에 둔 이유: macrointel이 risk를 호출해 트리거하는 방식은 모듈
 * 경계 원칙(risk/package-info.java "macrointel → risk 방향 참조 없음")에 어긋난다 — risk가
 * 스스로 매일 자기 상태를 정리하는 편이 더 단순하고 결합도가 낮다({@code DailyLimitTracker}의
 * 자정 리셋과 같은 성격의 "risk 내부 유지보수 배치").
 */
@Component
public class DisclosureBlacklistExpiryScheduler {

    private final DisclosureBlacklist disclosureBlacklist;

    public DisclosureBlacklistExpiryScheduler(DisclosureBlacklist disclosureBlacklist) {
        this.disclosureBlacklist = disclosureBlacklist;
    }

    /** 매일(주말 포함 — 만료 해제는 장 운영일과 무관하게 정리해도 무해하다) 08:00 KST 1회 실행. */
    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Seoul")
    public void releaseExpiredScheduled() {
        releaseExpiredNow();
    }

    /** 수동 트리거 — 운영자가 즉시 정리하고 싶을 때({@code IpoSyncScheduler.syncNow}와 같은 이유). */
    public void releaseExpiredNow() {
        disclosureBlacklist.releaseExpired();
    }
}
