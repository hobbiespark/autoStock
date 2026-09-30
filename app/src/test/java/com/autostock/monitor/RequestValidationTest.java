package com.autostock.monitor;

import com.autostock.common.event.Signal;
import com.autostock.ipo.IpoDealCommandService;
import com.autostock.ipo.IpoDealRepository;
import com.autostock.market.MarketDataPort;
import com.autostock.risk.KillSwitch;
import com.autostock.trading.TradingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 요청 본문 형식 검증(B2) — JSON 바인딩부터 @Valid, 400까지 실제 MVC 경로로 확인한다(컨텍스트 없는 standalone).
 * 형식 오류는 500이 아니라 400이고, 킬스위치는 잘못된 요청으로 해제되지 않는다(fail-closed).
 */
class RequestValidationTest {

    private final KillSwitch killSwitch = mock(KillSwitch.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final IpoDealCommandService ipoCommands = mock(IpoDealCommandService.class);

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new DashboardController(mock(DashboardFacade.class), killSwitch, publisher,
                    mock(MarketDataPort.class),
            new TradingProperties(TradingProperties.Mode.SIM, Duration.ofMinutes(5)), Clock.systemUTC()),
            new IpoController(mock(IpoDealRepository.class), ipoCommands))
            .setControllerAdvice(new ApiExceptionHandler()).build();

    private org.springframework.test.web.servlet.ResultActions postJson(String path, String json) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    // ── 킬스위치 ────────────────────────────────────────────────

    @Test
    void 킬스위치_작동_요청은_작동시킨다() throws Exception {
        postJson("/api/dashboard/killswitch", "{\"engage\":true}").andExpect(status().isOk());
        verify(killSwitch).engage(anyString());
    }

    @Test
    void engage가_없는_킬스위치_요청은_400이고_해제되지_않는다() throws Exception {
        // 예전(Map 본문)에는 키가 없거나 오타면 "해제"로 떨어졌다 — 비상 정지가 풀리는 fail-open
        postJson("/api/dashboard/killswitch", "{\"engaged\":true}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("engage"));
        verify(killSwitch, never()).release(anyString());
        verify(killSwitch, never()).engage(anyString());
    }

    @Test
    void engage가_불리언이_아니면_400이다() throws Exception {
        postJson("/api/dashboard/killswitch", "{\"engage\":\"yes\"}").andExpect(status().isBadRequest());
        verify(killSwitch, never()).release(anyString());
    }

    // ── 테스트 시그널 ──────────────────────────────────────────

    @Test
    void 정상_테스트_시그널은_지정_수량으로_발행되고_실행_모드를_돌려준다() throws Exception {
        postJson("/api/dashboard/test-signal",
                "{\"symbol\":\"005930\",\"side\":\"BUY\",\"price\":\"258000\",\"quantity\":\"3\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.executionMode").value("SIM"));
        verify(publisher).publishEvent(argThat((Object e) -> e instanceof Signal signal
                && Long.valueOf(3L).equals(signal.fixedQuantity())));
    }

    @Test
    void 수량이_비었거나_없으면_400이다_자동_사이징_폐지() throws Exception {
        // Phase 0.4(D-06): 2026-09-18 수량 공란 → 자동 사이징 193주 체결 사고의 재발 방지
        postJson("/api/dashboard/test-signal",
                "{\"symbol\":\"005930\",\"side\":\"BUY\",\"price\":\"258000\",\"quantity\":\"\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("quantity"));
        postJson("/api/dashboard/test-signal",
                "{\"symbol\":\"005930\",\"side\":\"BUY\",\"price\":\"258000\"}")
                .andExpect(status().isBadRequest());
        verifyNoInteractions(publisher);
    }

    @Test
    void 테스트_시그널의_형식_오류는_500이_아니라_400이다() throws Exception {
        String[] invalid = {
                "{\"symbol\":\"005930\",\"side\":\"HOLD\",\"price\":\"258000\",\"quantity\":\"\"}",
                "{\"symbol\":\"005930\",\"side\":\"BUY\",\"price\":\"abc\",\"quantity\":\"\"}",
                "{\"symbol\":\"005930\",\"side\":\"BUY\",\"price\":\"0\",\"quantity\":\"\"}",
                "{\"symbol\":\"005930\",\"side\":\"BUY\",\"price\":\"258000\",\"quantity\":\"-1\"}",
                "{\"symbol\":\"5930\",\"side\":\"BUY\",\"price\":\"258000\",\"quantity\":\"\"}",
                "{\"side\":\"BUY\",\"price\":\"258000\"}"
        };
        for (String body : invalid) {
            postJson("/api/dashboard/test-signal", body).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(publisher);
    }

    // ── 공모주 수동 입력 ─────────────────────────────────────────

    @Test
    void 확약률이_1을_넘으면_400이다() throws Exception {
        postJson("/api/ipo/1/metrics", "{\"lockupCommitRate\":1.5}").andExpect(status().isBadRequest());
        verifyNoInteractions(ipoCommands);
    }

    @Test
    void 음수_청약수량은_400이다() throws Exception {
        postJson("/api/ipo/1/record", "{\"appliedQty\":-1}").andExpect(status().isBadRequest());
        verifyNoInteractions(ipoCommands);
    }

    @Test
    void DB_정밀도를_넘는_기관경쟁률은_400이다() throws Exception {
        // ipo_deals.institutional_competition_rate NUMERIC(10,2) — 넘치면 DB 오류(500)였다
        postJson("/api/ipo/1/metrics", "{\"institutionalCompetitionRate\":123456789.123}")
                .andExpect(status().isBadRequest());
        verifyNoInteractions(ipoCommands);
    }
}
