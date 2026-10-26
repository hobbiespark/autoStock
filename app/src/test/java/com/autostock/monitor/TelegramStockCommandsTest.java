package com.autostock.monitor;

import com.autostock.portfolio.PositionBook;
import com.autostock.risk.KillSwitch;
import com.autostock.risk.RiskStateStore;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.reactive.function.client.WebClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** P2-2d shared command meaning: the legacy poller and the platform entry use one adapter and one /resume code. */
class TelegramStockCommandsTest {
 static class Time extends Clock{Instant now=Instant.parse("2026-10-27T01:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}}
 record Notice(NoticeLevel level,String text){}
 final Time clock=new Time();final KillSwitch killSwitch=new KillSwitch(mock(ApplicationEventPublisher.class),mock(RiskStateStore.class),clock);
 final TelegramStockCommands commands=new TelegramStockCommands(killSwitch,new PositionBook(),clock);
 static String code(String reply){return reply.replaceAll("(?s).*/resume ([0-9]{4}) .*","$1");}

 @Test void stopEngagesImmediatelyAndResumeNeedsTheExactCodeWithinSixtySecondsOnce(){
  var stop=commands.stop();assertTrue(stop.succeeded());assertEquals("kill-switch-engaged",stop.code());assertNull(stop.legacyNotice());assertTrue(killSwitch.isEngaged());
  assertEquals("kill-switch-already-engaged",commands.stop().code());
  var issued=commands.requestResume();assertEquals(NoticeLevel.WARN,issued.legacyNotice());String code=code(issued.reply());assertTrue(code.matches("[0-9]{4}"));assertFalse(issued.toString().contains(code));
  String wrong=code.equals("9999")?"9998":"9999";
  assertEquals("resume-code-mismatch",commands.confirmResume(wrong).code());assertEquals("resume-code-missing-or-expired",commands.confirmResume(code).code());assertTrue(killSwitch.isEngaged());
  String second=code(commands.requestResume().reply());clock.now=clock.now.plusSeconds(60);
  var released=commands.confirmResume(second);assertTrue(released.succeeded());assertEquals("kill-switch-released",released.code());assertNull(released.legacyNotice());assertFalse(killSwitch.isEngaged());
  killSwitch.engage("again");String third=code(commands.requestResume().reply());clock.now=clock.now.plusSeconds(61);
  assertEquals("resume-code-missing-or-expired",commands.confirmResume(third).code());assertTrue(killSwitch.isEngaged());
  killSwitch.release("test");var idle=commands.requestResume();assertFalse(idle.succeeded());assertEquals(NoticeLevel.INFO,idle.legacyNotice());assertEquals("kill-switch-not-engaged",idle.code());
  var status=commands.status();assertEquals(NoticeLevel.INFO,status.legacyNotice());assertEquals("킬스위치: 정상\n보유 종목 수: 0",status.reply());
 }

 @Test void legacyPollerAndPlatformPathShareOneCodeAndHandoverDropsIt(){
  List<Notice> sent=new CopyOnWriteArrayList<>();
  var poller=new TelegramCommandPoller(WebClient.builder(),new TelegramProperties(true,"synthetic-bot","123"),killSwitch,new PositionBook(),(level,text)->sent.add(new Notice(level,text)),clock);
  poller.stockCommands(commands);
  poller.handleUpdate(update(1,"/stop"));assertTrue(killSwitch.isEngaged());assertTrue(sent.isEmpty());
  poller.handleUpdate(update(2,"/resume"));assertEquals(1,sent.size());assertEquals(NoticeLevel.WARN,sent.get(0).level());String code=code(sent.get(0).text());
  // The code issued on the legacy path is the same single-use code the platform entry confirms.
  assertEquals("kill-switch-released",commands.confirmResume(code).code());assertFalse(killSwitch.isEngaged());
  poller.handleUpdate(update(3,"/resume"));assertEquals("킬스위치는 이미 해제 상태입니다(주문 허용).",sent.get(1).text());assertEquals(NoticeLevel.INFO,sent.get(1).level());
  killSwitch.engage("again");commands.requestResume();assertTrue(commands.dropPendingResume());assertFalse(commands.dropPendingResume());
  poller.handleUpdate(update(4,"/resume 1234"));assertEquals("해제 확인 코드가 없거나 만료됐습니다 — /resume 부터 다시 보내세요. (해제하지 않았음)",sent.get(2).text());
  poller.handleUpdate(update(5,"/status"));assertTrue(sent.get(3).text().startsWith("킬스위치: 작동 중"));
  poller.handleUpdate(Map.of("update_id",6,"message",Map.of("chat",Map.of("id",999),"text","/stop")));assertEquals(4,sent.size());
 }
 static Map<String,Object> update(long id,String text){return Map.of("update_id",id,"message",Map.of("chat",Map.of("id",123),"text",text));}
}
