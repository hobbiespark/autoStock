package com.autostock.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 텔레그램 미설정 시의 기본 Notifier — 로그만 남기는 no-op 어댑터.
 *
 * <p>{@code monitor.telegram.enabled=false}(또는 미설정, 기본값)일 때 등록된다.
 * 존재 이유: 텔레그램 봇 토큰/chat_id가 전혀 없어도(로컬 개발, CI 등) 애플리케이션
 * 컨텍스트가 정상적으로 뜨도록 하기 위해서다 — Notifier를 주입받는 컴포넌트
 * (TradeNotificationListener, DailyReportScheduler 등)는 어떤 구현체가 꽂혔는지
 * 신경 쓰지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "monitor.telegram", name = "enabled", havingValue = "false", matchIfMissing = true)
public class LogOnlyNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(LogOnlyNotifier.class);

    @Override
    public void notify(NoticeLevel level, String message) {
        log.info("[알림-{}] {}", level, message);
    }
}
