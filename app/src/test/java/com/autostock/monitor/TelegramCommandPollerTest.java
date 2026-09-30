package com.autostock.monitor;

import org.junit.jupiter.api.Test;

import static com.autostock.monitor.TelegramCommandPoller.Command;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * TelegramCommandPoller.parseCommand 단위테스트 — 순수 static 메서드라 Spring 컨텍스트나
 * 네트워크 없이 검증 가능하다. 핵심은 "등록된 chat_id가 아니면 무조건 무시"(보안)이다.
 */
class TelegramCommandPollerTest {

    private static final String ALLOWED = "123456";

    @Test
    void 등록된_chatId의_stop은_STOP() {
        assertEquals(Command.STOP, TelegramCommandPoller.parseCommand(ALLOWED, "/stop", ALLOWED));
    }

    @Test
    void 등록된_chatId의_resume은_RESUME() {
        assertEquals(Command.RESUME, TelegramCommandPoller.parseCommand(ALLOWED, "/resume", ALLOWED));
    }

    @Test
    void 등록된_chatId의_status는_STATUS() {
        assertEquals(Command.STATUS, TelegramCommandPoller.parseCommand(ALLOWED, "/status", ALLOWED));
    }

    @Test
    void 무관한_텍스트는_IGNORED() {
        assertEquals(Command.IGNORED, TelegramCommandPoller.parseCommand(ALLOWED, "안녕", ALLOWED));
    }

    @Test
    void 빈_텍스트는_IGNORED() {
        assertEquals(Command.IGNORED, TelegramCommandPoller.parseCommand(ALLOWED, "", ALLOWED));
    }

    @Test
    void 텍스트가_null이면_IGNORED() {
        assertEquals(Command.IGNORED, TelegramCommandPoller.parseCommand(ALLOWED, null, ALLOWED));
    }

    @Test
    void 타_chatId의_stop_명령은_차단된다() {
        // 보안 핵심 케이스 — 봇 토큰을 알아낸 제3자가 /stop을 보내도 무시해야 한다.
        assertEquals(Command.IGNORED, TelegramCommandPoller.parseCommand("999999", "/stop", ALLOWED));
    }

    @Test
    void senderChatId가_null이면_IGNORED() {
        assertEquals(Command.IGNORED, TelegramCommandPoller.parseCommand(null, "/stop", ALLOWED));
    }

    @Test
    void allowedChatId가_비어있으면_모두_IGNORED() {
        // 설정 실수로 chat-id가 비어있는 극단적 상황에서도 명령을 실행하면 안 된다.
        assertEquals(Command.IGNORED, TelegramCommandPoller.parseCommand(ALLOWED, "/stop", ""));
    }

    @Test
    void 앞뒤_공백은_trim되어_인식된다() {
        assertEquals(Command.STOP, TelegramCommandPoller.parseCommand(ALLOWED, "  /stop  ", ALLOWED));
    }

    // ---- Phase 0.7: /resume 2단계 확인 ----

    @Test
    void resume_뒤에_4자리_코드면_RESUME_CONFIRM() {
        assertEquals(Command.RESUME_CONFIRM, TelegramCommandPoller.parseCommand(ALLOWED, "/resume 0427", ALLOWED));
        assertEquals(Command.RESUME_CONFIRM, TelegramCommandPoller.parseCommand(ALLOWED, "  /resume   0427 ", ALLOWED));
        assertEquals("0427", TelegramCommandPoller.confirmCodeOf(" /resume 0427 "));
    }

    @Test
    void 코드_형식이_아니면_IGNORED() {
        assertEquals(Command.IGNORED, TelegramCommandPoller.parseCommand(ALLOWED, "/resume 12", ALLOWED));
        assertEquals(Command.IGNORED, TelegramCommandPoller.parseCommand(ALLOWED, "/resume abcd", ALLOWED));
        assertEquals(Command.IGNORED, TelegramCommandPoller.parseCommand(ALLOWED, "/resume 12345", ALLOWED));
    }

    @Test
    void 타_chatId의_확인_코드도_차단된다() {
        assertEquals(Command.IGNORED, TelegramCommandPoller.parseCommand("999999", "/resume 0427", ALLOWED));
    }
}
