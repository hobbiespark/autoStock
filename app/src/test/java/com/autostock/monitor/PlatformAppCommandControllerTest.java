package com.autostock.monitor;

import com.autostock.risk.KillSwitch;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** VALUNIVO 610(D300): 니보가 전달하는 할 일 입구. 루프백·토큰, 처리·거절·미지원을 지어내지 않고 답한다. */
class PlatformAppCommandControllerTest {
    @TempDir Path dir;

    PlatformAppCommandController controller(TelegramStockCommands commands, KillSwitch killSwitch, DashboardFacade facade) throws Exception {
        Path token = Files.writeString(dir.resolve("token"), "synthetic-app-token-0123456789\n");
        return new PlatformAppCommandController(commands, killSwitch, facade, token.toString());
    }

    static MockHttpServletRequest from(String address) { var request = new MockHttpServletRequest(); request.setRemoteAddr(address); return request; }
    static final String AUTH = "Bearer synthetic-app-token-0123456789";

    @Test void statusStopAndKillSwitchAreHandledResumeIsRefused() throws Exception {
        var commands = mock(TelegramStockCommands.class); var killSwitch = mock(KillSwitch.class); var facade = mock(DashboardFacade.class);
        when(commands.status()).thenReturn(new TelegramStockCommands.Reply(true, "status", "킬스위치 꺼짐, 보유 0종목", null));
        when(commands.stop()).thenReturn(new TelegramStockCommands.Reply(true, "kill-switch-engaged", "킬스위치를 작동했습니다", null));
        when(killSwitch.isEngaged()).thenReturn(true);
        var c = controller(commands, killSwitch, facade);
        var status = c.run("status_view", AUTH, null, from("127.0.0.1"));
        assertEquals(HttpStatus.OK, status.getStatusCode());
        assertEquals(new PlatformAppCommandController.Result("ok", "킬스위치 꺼짐, 보유 0종목"), status.getBody());
        assertEquals("ok", c.run("emergency_stop", AUTH, null, from("127.0.0.1")).getBody().status());
        verify(commands).stop();
        assertEquals("킬스위치: 작동 중(신규 주문 막힘)", c.run("killswitch_view", AUTH, null, from("::1")).getBody().text());
        var resume = c.run("trading_resume", AUTH, null, from("127.0.0.1")).getBody();
        assertEquals("refused", resume.status());
        assertTrue(resume.text().contains("/nivo_autostock_trading_resume"));
        verify(commands, never()).requestResume(anyString());
        verify(killSwitch, never()).release(anyString());
    }

    @Test void notYetCommandsSayFailedAndUnknownIsNotFound() throws Exception {
        var c = controller(mock(TelegramStockCommands.class), mock(KillSwitch.class), mock(DashboardFacade.class));
        for (String id : PlatformAppCommandController.NOT_YET_COMMANDS)
            assertEquals(new PlatformAppCommandController.Result("failed", PlatformAppCommandController.NOT_YET), c.run(id, AUTH, null, from("127.0.0.1")).getBody(), id);
        assertEquals(HttpStatus.NOT_FOUND, c.run("transfer_money", AUTH, null, from("127.0.0.1")).getStatusCode());
    }

    @Test void onlyLoopbackWithTheRightTokenIsServed() throws Exception {
        var commands = mock(TelegramStockCommands.class);
        var c = controller(commands, mock(KillSwitch.class), mock(DashboardFacade.class));
        assertEquals(HttpStatus.FORBIDDEN, c.run("emergency_stop", AUTH, null, from("10.0.0.5")).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, c.run("emergency_stop", null, null, from("127.0.0.1")).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, c.run("emergency_stop", "Bearer wrong", null, from("127.0.0.1")).getStatusCode());
        verify(commands, never()).stop();
        Path empty = Files.writeString(dir.resolve("empty"), "  ");
        assertThrows(IllegalArgumentException.class, () -> new PlatformAppCommandController(commands, mock(KillSwitch.class), mock(DashboardFacade.class), empty.toString()));
    }
}
