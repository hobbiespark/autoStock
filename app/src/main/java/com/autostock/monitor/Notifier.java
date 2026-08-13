package com.autostock.monitor;

/**
 * 알림 Port (Hexagonal — ARCHITECTURE 4절 패턴).
 *
 * <p>monitor 내부 로직(TradeNotificationListener, DailyReportScheduler 등)은
 * "누구에게 어떻게 보내는지"를 전혀 모른 채 이 인터페이스만 호출한다. 실제 발송 수단은
 * 어댑터가 결정한다 — 지금은 텔레그램({@link TelegramNotifier})뿐이지만, 나중에
 * 슬랙·이메일 어댑터를 추가해도 이 인터페이스와 호출부는 손댈 필요가 없다.
 *
 * <p>어떤 어댑터가 활성화되는지는 monitor.telegram.enabled 설정으로 결정된다
 * (활성화: {@link TelegramNotifier}, 비활성화 기본값: {@link LogOnlyNotifier}) —
 * 둘 다 {@code @ConditionalOnProperty}로 배타적으로 등록되어 Spring 컨텍스트에는
 * 항상 정확히 하나의 Notifier 빈만 존재한다.
 */
public interface Notifier {

    /**
     * 알림을 보낸다. 구현체는 발송 실패를 삼키고 로그만 남겨야 한다 —
     * 알림 채널 장애가 매매 로직에 영향을 주면 안 되기 때문(부작용 격리).
     *
     * @param level   심각도
     * @param message 알림 본문(사람이 읽는 한국어 요약)
     */
    void notify(NoticeLevel level, String message);
}
