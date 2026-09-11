package com.autostock.monitor;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** TradingAutoStarter — 플래그에 따라 start()를 호출/미호출하는지만 검증한다. */
class TradingAutoStarterTest {

    @Test
    void 플래그가_켜져있으면_기동완료시_start를_호출한다() {
        TradingSystemManager manager = mock(TradingSystemManager.class);
        new TradingAutoStarter(manager, true).onReady();
        verify(manager).start();
    }

    @Test
    void 기본값이면_아무_것도_하지_않는다() {
        TradingSystemManager manager = mock(TradingSystemManager.class);
        new TradingAutoStarter(manager, false).onReady();
        verify(manager, never()).start();
    }
}
