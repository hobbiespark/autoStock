package com.autostock.common.event;

import java.time.Instant;

/**
 * 브로커(키움) 인증 실패 — 재시도로 풀리지 않아 사람이 조치해야 하는 오류(Phase 0.6, aiDoc/kiwoom-error-codes.md).
 *
 * <p>kiwoom 모듈(토큰 발급·REST 호출)이 발행하고 monitor가 받아 텔레그램 긴급 알림으로 바꾼다 — kiwoom은 알림 채널을
 * 모른다(KillSwitchChanged와 같은 패턴). 흔한 원인: 허용 IP 목록에 현재 공인 IP가 없음(8010·8040·8050·8103),
 * App Key 오류·서비스 해지(8001·8002 — 키움은 3개월 실서버 미접속 시 자동 해지), 실전·모의 키 혼용(8030·8031).
 *
 * @param code    키움 오류코드(예: "8001")
 * @param message 키움 응답 메시지(비밀값 마스킹 후)
 * @param host    호출한 서버 호스트(mockapi.kiwoom.com = 모의, api.kiwoom.com = 실전)
 * @param at      감지 시각
 */
public record BrokerAuthFailure(String code, String message, String host, Instant at) {
}
