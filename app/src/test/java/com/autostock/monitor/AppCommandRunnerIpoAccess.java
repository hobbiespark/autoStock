package com.autostock.monitor;

import com.autostock.ipo.IpoCommandService;
import com.autostock.portfolio.PositionBook;
import com.autostock.risk.KillSwitch;
import java.time.Clock;

import static org.mockito.Mockito.mock;

/** 시험 도우미: 공모주 명령 부품을 넣은 {@link AppCommandRunner}(주입 지점이 패키지 범위라 같은 패키지에 둔다, VALUNIVO 900). */
public final class AppCommandRunnerIpoAccess {

    private AppCommandRunnerIpoAccess() {
    }

    public static AppCommandRunner withIpo(IpoCommandService ipo) {
        KillSwitch killSwitch = mock(KillSwitch.class);
        AppCommandRunner runner = new AppCommandRunner(new TelegramStockCommands(killSwitch, new PositionBook(), Clock.systemUTC()),
                killSwitch, mock(DashboardFacade.class));
        runner.ipoCommands(ipo);
        return runner;
    }
}
