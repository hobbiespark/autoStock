package com.autostock.monitor;

import com.autostock.risk.KillSwitch;
import com.autostock.trading.OrderRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** VALUNIVO 612(D301): 화면 버튼 전환. 확인 문구·미체결 0건·루프백을 지키고, 매매 정지·킬스위치·모드 파일·재기동 순서로만 진행한다. */
class ModeSwitchControllerTest {
    @TempDir Path dir;
    final OrderRepository orders = mock(OrderRepository.class);
    final KillSwitch killSwitch = mock(KillSwitch.class);
    final TradingSystemManager trading = mock(TradingSystemManager.class);
    final List<String> steps = new ArrayList<>();

    ModeSwitchController controller(String profile, String modeFile, String script) {
        var env = new MockEnvironment(); if (profile != null) env.setActiveProfiles(profile);
        var c = new ModeSwitchController(env, orders, killSwitch, trading, mock(ConfigurableApplicationContext.class), modeFile, script);
        c.restarter(new ModeSwitchController.Restarter() {
            public void restartAfterExit(String s) { steps.add("restart:" + s); }
            public void exitSoon() { steps.add("exit"); }
        });
        return c;
    }
    static MockHttpServletRequest local() { var r = new MockHttpServletRequest(); r.setRemoteAddr("127.0.0.1"); return r; }

    @Test void switchesToLiveOnlyWithConfirmationAndNoOpenOrders() throws Exception {
        Path mode = dir.resolve("config").resolve("trading-mode.json");
        var c = controller("paper", mode.toString(), "C:/x/restart.ps1");
        assertEquals(new ModeSwitchController.ModeView("paper", true), c.mode());
        assertEquals(HttpStatus.BAD_REQUEST, c.switchMode(new ModeSwitchController.SwitchRequest("live", "실전"), local()).getStatusCode(), "wrong confirmation");
        assertEquals(HttpStatus.CONFLICT, c.switchMode(new ModeSwitchController.SwitchRequest("paper", "모의 전환"), local()).getStatusCode(), "already paper");
        when(orders.findByStatusIn(any())).thenReturn(List.of(mock(com.autostock.trading.OrderEntity.class)));
        var open = c.switchMode(new ModeSwitchController.SwitchRequest("live", "실전 전환"), local());
        assertEquals(HttpStatus.CONFLICT, open.getStatusCode()); assertTrue(open.getBody().message().contains("미체결 주문이 1건"));
        verify(killSwitch, never()).engage(anyString()); assertFalse(Files.exists(mode));
        when(orders.findByStatusIn(any())).thenReturn(List.of());
        var ok = c.switchMode(new ModeSwitchController.SwitchRequest("live", " 실전 전환 "), local());
        assertEquals(HttpStatus.ACCEPTED, ok.getStatusCode());
        assertEquals("{\"mode\":\"live\"}", Files.readString(mode));
        var order = inOrder(trading, killSwitch);
        order.verify(trading).stop(); order.verify(killSwitch).engage("모드 전환(paper → live)");
        assertEquals(List.of("restart:C:/x/restart.ps1", "exit"), steps);
    }

    @Test void refusesRemoteCallersMissingSettingsAndBadTargets() {
        var c = controller("live", dir.resolve("m.json").toString(), "C:/x/restart.ps1");
        assertEquals("live", c.mode().mode());
        var remote = new MockHttpServletRequest(); remote.setRemoteAddr("10.0.0.5");
        assertEquals(HttpStatus.FORBIDDEN, c.switchMode(new ModeSwitchController.SwitchRequest("paper", "모의 전환"), remote).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, c.switchMode(new ModeSwitchController.SwitchRequest("real", "실전 전환"), local()).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, c.switchMode(null, local()).getStatusCode());
        var unset = controller("paper", "", "");
        assertFalse(unset.mode().switchable());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unset.switchMode(new ModeSwitchController.SwitchRequest("live", "실전 전환"), local()).getStatusCode());
        verify(trading, never()).stop(); verify(killSwitch, never()).engage(anyString()); assertTrue(steps.isEmpty());
    }

    @Test void writeFailureLeavesTheAppRunningWithKillSwitchOn() throws Exception {
        Path blocker = Files.writeString(dir.resolve("not-a-dir"), "x");
        when(orders.findByStatusIn(any())).thenReturn(List.of());
        var c = controller(null, blocker.resolve("trading-mode.json").toString(), "C:/x/restart.ps1");
        var failed = c.switchMode(new ModeSwitchController.SwitchRequest("live", "실전 전환"), local());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, failed.getStatusCode());
        verify(killSwitch).engage(anyString());
        assertTrue(steps.isEmpty(), "no restart and no exit");
    }
}
