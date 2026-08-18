package com.autostock.risk;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DART 공시 기반 매수 배제 목록 골격 (PLAN 5절 1단계 — 규칙 기반 macro-intel).
 *
 * <p><b>향후 계획(TODO — DART OpenAPI 연동, 실측 전 미구현)</b>: DART 공시검색 API를 배치로
 * 조회해 유상증자·소송 등 리스크 공시가 발생한 종목을 감지하면, 공시 다음 거래일(D+1)부터
 * 자동으로 이 목록에 추가한다(PLAN 5절 표 — "DART OpenAPI: 공시·지분변동 → 유상증자·소송 등
 * 발생 종목 진입 배제"). 리스크가 해소됐다고 판단되면(수동 확인 또는 일정 기간 경과) 목록에서
 * 제거한다. 지금은 DART 연동 없이 <b>수동 add/remove만 제공하는 골격</b>이다 — 운영자가
 * 직접(또는 향후 배치가) 종목을 넣고 뺄 수 있는 자리만 먼저 만들어 둔다.
 *
 * <p>이 클래스가 risk 모듈에 있는 이유는 {@link MacroGuard}와 동일하다 — "한도·차단 판단은
 * risk 소유" 원칙(risk/package-info.java), macrointel은 수집·발행만 담당한다.
 *
 * <p>스레드 안전: {@link ConcurrentHashMap#newKeySet()}으로 만든 Set을 쓴다 — 조회(RiskGate의
 * 매수 판단 경로)와 갱신(운영자의 add/remove, 향후 DART 배치)이 서로 다른 스레드에서 동시에
 * 일어날 수 있다.
 */
@Component
public class DisclosureBlacklist {

    private final Set<String> blacklisted = ConcurrentHashMap.newKeySet();

    /** 이 종목이 지금 매수 배제 대상인지. */
    public boolean isBlacklisted(String symbol) {
        return blacklisted.contains(symbol);
    }

    /** 배제 목록에 종목을 추가한다(운영자 수동 등록, 또는 향후 DART 배치가 D+1에 자동 호출). */
    public void add(String symbol) {
        blacklisted.add(symbol);
    }

    /** 배제 목록에서 종목을 제거한다(리스크 해소 확인 후 운영자 수동 해제). */
    public void remove(String symbol) {
        blacklisted.remove(symbol);
    }
}
