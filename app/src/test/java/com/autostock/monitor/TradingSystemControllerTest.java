package com.autostock.monitor;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/trading/start|stop — 정상 전이는 200 + 새 상태, 불법 전이는 409 ProblemDetail + 현재 상태.
 */
class TradingSystemControllerTest {

    private final TradingSystemManager manager = mock(TradingSystemManager.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TradingSystemController(manager))
            .setControllerAdvice(new ApiExceptionHandler()).build();

    @Test
    void 시작_명령은_새_상태로_답한다() throws Exception {
        when(manager.start()).thenReturn(TradingSystemStatus.STARTING);

        mvc.perform(post("/api/trading/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STARTING"));
    }

    @Test
    void 불법_전이는_409와_현재_상태를_ProblemDetail로_답한다() throws Exception {
        when(manager.stop()).thenThrow(new IllegalStateException("STOPPED → STOPPING 불가"));
        when(manager.status()).thenReturn(TradingSystemStatus.STOPPED);

        mvc.perform(post("/api/trading/stop"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.currentStatus").value("STOPPED"));
    }
}
