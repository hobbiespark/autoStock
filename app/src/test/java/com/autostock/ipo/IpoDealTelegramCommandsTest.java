package com.autostock.ipo;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 텔레그램 {@code /ipo 청약|배정|매도}(2026-11-05, aiDoc/ipo-automation.md 8절) — 해석·검증·저장 경로·답. */
class IpoDealTelegramCommandsTest {

    private static final Clock NOW = Clock.fixed(Instant.parse("2026-11-05T01:00:00Z"), ZoneOffset.UTC);

    private final IpoDealRepository repository = mock(IpoDealRepository.class);
    private final IpoDealCommandService commandService = mock(IpoDealCommandService.class);
    private final IpoLiveSellService liveSell = mock(IpoLiveSellService.class);
    private final IpoDealTelegramCommands commands = new IpoDealTelegramCommands(repository, commandService, liveSell, NOW);

    private IpoDealEntity deal() {
        IpoDealEntity deal = IpoLiveSellServiceTest.listedDeal(0);
        ReflectionTestUtils.setField(deal, "id", 7L);
        return deal;
    }

    @Test
    void 하위_명령만_고른다() {
        assertTrue(IpoDealTelegramCommands.matches("/ipo 청약 브릴스 20 165000"));
        assertTrue(IpoDealTelegramCommands.matches(" /ipo 배정 브릴스 3 "));
        assertTrue(IpoDealTelegramCommands.matches("/ipo 매도 1234"));
        assertTrue(IpoDealTelegramCommands.matches("/ipo 매도"));
        assertFalse(IpoDealTelegramCommands.matches("/ipo 브릴스 1500"), "기존 재판단");
        assertFalse(IpoDealTelegramCommands.matches("/ipo 청약회사 1500"));
    }

    @Test
    void 회사_이름과_끝의_숫자를_나눈다() {
        IpoDealTelegramCommands.Parsed p = IpoDealTelegramCommands.parse("한화 플러스 20 1,650,000원", 2).orElseThrow();
        assertEquals("한화 플러스", p.query());
        assertEquals(0, new BigDecimal("1650000").compareTo(p.numbers().get(1)));
        assertTrue(IpoDealTelegramCommands.parse("브릴스 20", 2).isEmpty(), "증거금 빠짐");
        assertTrue(IpoDealTelegramCommands.parse("20 165000", 2).isEmpty(), "회사 빠짐");
        assertEquals(3, IpoDealTelegramCommands.parse("브릴스 3주", 1).orElseThrow().numbers().get(0).intValue());
    }

    @Test
    void 기록_API와_같은_검증을_한다() {
        assertEquals(null, IpoDealTelegramCommands.quantity(new BigDecimal("2.5")));
        assertEquals(0, IpoDealTelegramCommands.quantity(BigDecimal.ZERO));
        assertEquals(null, IpoDealTelegramCommands.deposit(new BigDecimal("1.234")), "소수 3자리");
        assertEquals(null, IpoDealTelegramCommands.deposit(new BigDecimal("123456789012345")), "정수 15자리");
        assertEquals(0, new BigDecimal("165000.5").compareTo(IpoDealTelegramCommands.deposit(new BigDecimal("165000.5"))));
    }

    @Test
    void 청약은_수량과_증거금을_기록_서비스로_저장한다() {
        IpoDealEntity deal = deal();
        when(repository.findBySubscriptionStartGreaterThanEqualOrSubscriptionStartIsNull(any())).thenReturn(List.of(deal));
        when(commandService.recordMyDeal(eq(7L), any())).thenReturn(Optional.of(deal));
        String reply = commands.reply("/ipo 청약 브릴스 20 165,000");
        ArgumentCaptor<IpoDealCommandService.RecordCommand> captor = ArgumentCaptor.forClass(IpoDealCommandService.RecordCommand.class);
        verify(commandService).recordMyDeal(eq(7L), captor.capture());
        assertEquals(20, captor.getValue().appliedQty());
        assertEquals(0, new BigDecimal("165000").compareTo(captor.getValue().deposit()));
        assertEquals(null, captor.getValue().allocatedQty());
        assertTrue(reply.contains("청약 20주 · 증거금 165,000원 저장"), reply);
    }

    @Test
    void 배정은_수량을_저장하고_상장일과_매도_계획을_안내한다() {
        IpoDealEntity deal = deal();
        when(repository.findBySubscriptionStartGreaterThanEqualOrSubscriptionStartIsNull(any())).thenReturn(List.of(deal));
        when(commandService.recordMyDeal(eq(7L), any())).thenReturn(Optional.of(deal));
        when(liveSell.stateLabel()).thenReturn("꺼짐 — 실전 키 없음(KIWOOM_LIVE_APP_KEY/SECRET)");
        when(liveSell.ordersEnabled()).thenReturn(false);
        String reply = commands.reply("/ipo 배정 브릴스 11");
        ArgumentCaptor<IpoDealCommandService.RecordCommand> captor = ArgumentCaptor.forClass(IpoDealCommandService.RecordCommand.class);
        verify(commandService).recordMyDeal(eq(7L), captor.capture());
        assertEquals(11, captor.getValue().allocatedQty());
        assertEquals(null, captor.getValue().appliedQty());
        assertTrue(reply.contains("배정 11주 저장"), reply);
        assertTrue(reply.contains("상장일: 11/12"), reply);
        assertTrue(reply.contains("1차 6주 장전 지정가 9,900원"), reply);
        assertTrue(reply.contains("2차 3주") && reply.contains("3차 2주"), reply);
        assertTrue(reply.contains("실전 키 없음") && reply.contains("직접 매도"), reply);
    }

    @Test
    void 잘못된_값이나_없는_회사는_저장하지_않는다() {
        when(repository.findBySubscriptionStartGreaterThanEqualOrSubscriptionStartIsNull(any())).thenReturn(List.of(deal()));
        assertTrue(commands.reply("/ipo 배정 브릴스 2.5").contains("저장하지 않았습니다"));
        assertTrue(commands.reply("/ipo 배정 없는회사 3").contains("찾지 못함"));
        assertTrue(commands.reply("/ipo 청약 브릴스 20").contains("형식"));
        verify(commandService, never()).recordMyDeal(anyLong(), any());
    }

    @Test
    void 매도는_확인_코드를_넘기거나_새_코드를_요청한다() {
        when(liveSell.confirm("1234")).thenReturn("확인됨");
        when(liveSell.requestCode()).thenReturn("새 코드");
        assertEquals("확인됨", commands.reply("/ipo 매도 1234"));
        assertEquals("새 코드", commands.reply("/ipo 매도"));
        assertTrue(commands.reply("/ipo 매도 12345").contains("4자리"));
    }
}
