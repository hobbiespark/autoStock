package com.autostock.monitor;

import com.autostock.common.event.BrokerAuthFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 키움 인증 실패 긴급 알림(Phase 0.6, aiDoc/kiwoom-error-codes.md) — {@link BrokerAuthFailure}를 받아 원인 안내와 함께
 * 텔레그램으로 보낸다. 인증 실패는 재시도로 풀리지 않아 사람이 키움 포털에서 조치해야 한다.
 *
 * <p>같은 코드는 {@link #SUPPRESS_FOR}(10분) 안에 한 번만 보낸다 — 인증이 막히면 모든 호출(분봉·잔고·대사…)이
 * 같은 코드로 실패해 분당 수십 건이 쏟아진다.
 */
@Component
public class BrokerAuthAlertListener {

    private static final Logger log = LoggerFactory.getLogger(BrokerAuthAlertListener.class);

    static final Duration SUPPRESS_FOR = Duration.ofMinutes(10);

    /** 코드별 원인 안내 — 조사 upgrade-2026-10/07 §1-8(키움 공식 스펙·서비스 안내). */
    private static final Map<String, String> HINTS = Map.of(
            "8001", "App Key·Secret 검증 실패 — 키 오타·재발급 여부, 서비스 해지(3개월 실서버 미접속 시 자동 해지) 확인",
            "8002", "App Key·Secret 검증 실패 — 키 오타·재발급 여부, 서비스 해지(3개월 실서버 미접속 시 자동 해지) 확인",
            "8010", "토큰 발급 IP와 요청 IP가 다름 — 공인 IP가 바뀌었으면 현재 IP를 허용 IP 목록에 등록",
            "8030", "실전·모의 구분 불일치 — 실행 프로필(paper/live)과 App Key 종류 확인",
            "8031", "실전·모의 구분 불일치 — 실행 프로필(paper/live)과 App Key 종류 확인",
            "8040", "단말기 인증 실패 — 허용 IP 목록에 현재 공인 IP가 있는지 확인",
            "8050", "지정단말기 인증 실패 — 허용 IP 목록에 현재 공인 IP가 있는지 확인",
            "8103", "토큰 또는 단말기 인증 실패 — 허용 IP 목록·App Key 상태 확인");

    private final Notifier notifier;
    private final Clock clock;
    private final Map<String, Instant> lastSentByCode = new ConcurrentHashMap<>();

    public BrokerAuthAlertListener(Notifier notifier, Clock clock) {
        this.notifier = notifier;
        this.clock = clock;
    }

    @EventListener
    public void onBrokerAuthFailure(BrokerAuthFailure event) {
        Instant now = clock.instant();
        boolean[] send = {false};
        lastSentByCode.compute(event.code(), (code, last) -> {
            if (last != null && now.isBefore(last.plus(SUPPRESS_FOR))) {
                return last;        // 억제 창 안 — 최초 발송 시각을 유지해 10분마다 최대 1건
            }
            send[0] = true;
            return now;
        });
        if (!send[0]) {
            log.debug("키움 인증 실패 [{}] 알림 억제({}분 안 중복)", event.code(), SUPPRESS_FOR.toMinutes());
            return;
        }
        notifier.notify(NoticeLevel.CRITICAL, message(event));
    }

    static String message(BrokerAuthFailure event) {
        return """
                키움 인증 실패 [%s] (%s)
                %s
                원인 안내: %s
                확인 순서: ① openapi.kiwoom.com → App Key 관리에서 서비스 상태(해지 여부) ② 허용 IP 목록에 현재 공인 IP 포함 여부(최대 10개) ③ 조치 후 앱 재기동. 같은 코드는 10분에 한 번만 알린다."""
                .formatted(event.code(), event.host(), event.message(),
                        HINTS.getOrDefault(event.code(), "키움 오류코드 표 확인"));
    }
}
