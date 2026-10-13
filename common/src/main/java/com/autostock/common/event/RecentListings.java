package com.autostock.common.event;

import java.time.LocalDate;
import java.util.List;

/**
 * 최근 상장한 종목 목록 — 공모주 상장일·종목코드를 사람 손 없이 채우기 위한 이벤트(2026-10-13).
 *
 * <p>배경: 공모주 딜의 상장일은 DART 어디에도 없어(증권신고서는 "상장일 미확정"으로만 쓴다) 사람이 화면에 넣어야 했고,
 * 넣는 화면도 없어 LISTED 상태와 상장일 알림이 한 번도 나간 적이 없다. market 모듈이 거래일 아침 키움 종목정보 리스트
 * (ka10099)에서 상장일이 최근인 종목만 추려 이 이벤트를 발행하고, ipo 모듈이 회사명으로 딜과 맞춰 상장일·종목코드를 남긴다.
 * market → ipo 타입 의존이 생기지 않도록 common 이벤트로만 통신한다.
 *
 * @param day      확인한 날(KST)
 * @param listings 상장일이 확인 범위 안인 종목(코스피·코스닥)
 */
public record RecentListings(LocalDate day, List<Listing> listings) {

    public RecentListings {
        listings = listings == null ? List.of() : List.copyOf(listings);
    }

    /**
     * @param stockCode  종목코드 6자리
     * @param name       종목명(거래소 약칭 — 예: "OO스팩N호")
     * @param listedOn   상장일
     * @param marketName 시장명(예: "거래소", "코스닥")
     */
    public record Listing(String stockCode, String name, LocalDate listedOn, String marketName) {
    }
}
