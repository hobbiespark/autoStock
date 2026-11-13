package com.autostock.monitor;

import com.autostock.fx.FxCommandService;
import com.autostock.portfolio.PositionBook;
import com.autostock.risk.KillSwitch;

import java.time.Clock;

import static org.mockito.Mockito.mock;

/** 시험 도우미: 외화 명령 부품을 넣은 {@link AppCommandRunner}(주입 지점이 패키지 범위라 같은 패키지에 둔다, VALUNIVO 870). */
public final class AppCommandRunnerFxAccess {

    private AppCommandRunnerFxAccess() {
    }

    public static AppCommandRunner withFx(FxCommandService fx) {
        KillSwitch killSwitch = mock(KillSwitch.class);
        AppCommandRunner runner = new AppCommandRunner(new TelegramStockCommands(killSwitch, new PositionBook(), Clock.systemUTC()),
                killSwitch, mock(DashboardFacade.class));
        runner.fx(fx);
        return runner;
    }
}
