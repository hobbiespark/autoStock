package com.autostock.monitor;

import com.autostock.monitor.PlatformStockControlReplica.Wire;
import com.autostock.monitor.control.*;
import com.autostock.portfolio.PositionBook;
import com.autostock.risk.KillSwitch;
import com.autostock.risk.RiskStateStore;
import com.autostock.support.PostgresDataJpaTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * P2-2d entry over a real loopback socket and an isolated D-18 DB. The platform side is the independent replica of the
 * VALUNIVO code (same proof/verify/ACK algorithms and HTTP client), so the contract is checked from both ends.
 * Real KillSwitch with a mocked state store; synthetic keys only; no operating DB, bot, broker or port.
 */
class PlatformStockControlEntryDbTest extends PostgresDataJpaTest {
 @Autowired DataSource source;
 static final Instant NOW=Instant.parse("2026-10-27T01:00:00Z");
 static class Time extends Clock{volatile Instant now=NOW;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}}
 /** Shared command adapter with an execution counter (and an optional failure) — the semantics stay the production ones. */
 static class Counting extends TelegramStockCommands{
  final AtomicInteger executed=new AtomicInteger();volatile boolean fail;
  Counting(KillSwitch k,Clock c){super(k,new PositionBook(),c);}
  @Override public Reply stop(){executed.incrementAndGet();if(fail)throw new IllegalStateException("synthetic-failure");return super.stop();}
  @Override public Reply requestResume(){executed.incrementAndGet();return super.requestResume();}
  @Override public Reply confirmResume(String code){executed.incrementAndGet();return super.confirmResume(code);}
  @Override public Reply status(){executed.incrementAndGet();return super.status();}
 }
 final Time clock=new Time();final PlatformStockControlReplica platform=new PlatformStockControlReplica();final ObjectMapper json=new ObjectMapper();
 KillSwitch killSwitch;Counting commands;PlatformStockControlListener listener;int port;

 @BeforeEach void start()throws IOException{
  killSwitch=new KillSwitch(mock(ApplicationEventPublisher.class),mock(RiskStateStore.class),clock);commands=new Counting(killSwitch,clock);
  byte[] storage=new byte[32];Arrays.fill(storage,(byte)9);
  var verifier=new StockControlVerifier(PlatformStockControlReplica.REQUEST_KID,platform.requestPair.getPublic(),platform.bot,PlatformStockControlReplica.SPACE,PlatformStockControlReplica.ACTOR,clock);
  var ackVerifier=new StockControlResultAckVerifier(PlatformStockControlReplica.ACK_KID,platform.ackPair.getPublic(),platform.bot,PlatformStockControlReplica.SPACE,PlatformStockControlReplica.ACTOR,clock);
  var attempts=new StockControlAttemptStore(source,verifier,clock,Duration.ofSeconds(10),new StockControlResultCipher("store_test",storage));
  var entry=new PlatformStockControlEntry(verifier,new StockControlReceiptStore(source,verifier,"store_test",storage),attempts,
    new StockControlResultIssuer(attempts,verifier,PlatformStockControlReplica.RESULT_KID,platform.resultPair.getPrivate(),clock),ackVerifier,new StockControlResultAckStore(source,ackVerifier),commands,source);
  listener=new PlatformStockControlListener(entry,0);listener.start();port=listener.port();
 }
 @AfterEach void close(){if(listener!=null)listener.close();}

 Wire wire(long update,long fence,String command,String code){return new Wire(Long.toString(update),Long.toString(fence),command,code);}
 PlatformStockControlReplica.Response send(String path,Wire w)throws Exception{return platform.post(port,path,platform.proof(w,clock.now));}
 PlatformStockControlReplica.Result answered(PlatformStockControlReplica.Response r,Wire original)throws Exception{assertEquals(200,r.status(),r.text());return platform.verify(r.body(),original.update(),platform.fingerprint(original),clock.now);}
 long count(String table,long update)throws Exception{try(var c=source.getConnection();var q=c.prepareStatement("select count(*) from "+table+" where bot_ref=? and source_update_id=?")){q.setString(1,platform.bot);q.setLong(2,update);try(var rows=q.executeQuery()){rows.next();return rows.getLong(1);}}}
 String attemptState(long update)throws Exception{try(var c=source.getConnection();var q=c.prepareStatement("select state from platform_stock_control_attempt where bot_ref=? and source_update_id=?")){q.setString(1,platform.bot);q.setLong(2,update);try(var rows=q.executeQuery()){return rows.next()?rows.getString(1):null;}}}
 String error(PlatformStockControlReplica.Response r)throws Exception{return json.readTree(r.body()).path("error").asText(null);}

 @Test void stopExecutesOnceIsVerifiedByThePlatformAndAcknowledgedIdempotently()throws Exception{
  assertTrue(platform.available(port));
  var stop=wire(101,1,"stop",null);byte[] firstProof=platform.proof(stop,clock.now);
  var first=platform.post(port,"requests",firstProof);var result=answered(first,stop);
  assertEquals("succeeded",result.outcome());assertEquals("kill-switch-engaged",result.code());assertTrue(killSwitch.isEngaged());assertEquals(1,commands.executed.get());
  // Same bytes again, and a fresh proof for the same update under a newer receiver fence: stored result only.
  var again=answered(platform.post(port,"requests",firstProof),stop);assertEquals(result.attemptId(),again.attemptId());
  clock.now=NOW.plusSeconds(5);var newer=wire(101,2,"stop",null);
  var resent=platform.post(port,"requests",platform.proof(newer,clock.now));var fromNewer=platform.verify(resent.body(),"101",platform.fingerprint(stop),clock.now);
  assertEquals(result.attemptId(),fromNewer.attemptId());assertEquals(1,commands.executed.get());
  // observe: original request fingerprint, no execution.
  var observed=platform.verify(send("observe",wire(101,2,"stop",null)).body(),"101",platform.fingerprint(stop),clock.now);
  assertEquals(result.attemptId(),observed.attemptId());assertEquals(result.resultSha256(),observed.resultSha256());assertEquals(1,commands.executed.get());
  Instant recorded=clock.now;byte[] ack=platform.ack("101",result,first.body(),recorded,clock.now);
  assertEquals(200,platform.post(port,"acks",ack).status());assertEquals(200,platform.post(port,"acks",platform.ack("101",result,first.body(),recorded,clock.now)).status());
  assertEquals(1,count("platform_stock_control_receipt",101));assertEquals(1,count("platform_stock_control_attempt",101));assertEquals("succeeded",attemptState(101));
  assertEquals(1,count("platform_stock_control_result",101));assertEquals(1,count("platform_stock_control_result_ack",101));
  // Already engaged: still the same meaning, reported as such.
  var second=answered(send("requests",wire(102,2,"stop",null)),wire(102,2,"stop",null));assertEquals("kill-switch-already-engaged",second.code());assertTrue(killSwitch.isEngaged());
 }

 @Test void resumeKeepsTheSixtySecondSingleUseCodeAndReleasesOnlyOnExactMatch()throws Exception{
  killSwitch.engage("synthetic");
  var issued=answered(send("requests",wire(201,1,"resume_request",null)),wire(201,1,"resume_request",null));
  assertEquals("resume-code-issued",issued.code());String code=issued.reply().replaceAll("(?s).*/resume ([0-9]{4}) .*","$1");assertTrue(code.matches("[0-9]{4}"));
  String wrong=code.equals("0000")?"0001":"0000";
  var mismatch=answered(send("requests",wire(202,1,"resume_confirm",wrong)),wire(202,1,"resume_confirm",wrong));assertEquals("failed",mismatch.outcome());assertEquals("resume-code-mismatch",mismatch.code());assertTrue(killSwitch.isEngaged());
  var used=answered(send("requests",wire(203,1,"resume_confirm",code)),wire(203,1,"resume_confirm",code));assertEquals("resume-code-missing-or-expired",used.code());assertTrue(killSwitch.isEngaged());
  var later=answered(send("requests",wire(204,1,"resume_request",null)),wire(204,1,"resume_request",null));String late=later.reply().replaceAll("(?s).*/resume ([0-9]{4}) .*","$1");
  clock.now=clock.now.plusSeconds(61);
  var expired=answered(send("requests",wire(205,1,"resume_confirm",late)),wire(205,1,"resume_confirm",late));assertEquals("resume-code-missing-or-expired",expired.code());assertTrue(killSwitch.isEngaged());
  var fresh=answered(send("requests",wire(206,1,"resume_request",null)),wire(206,1,"resume_request",null));String ok=fresh.reply().replaceAll("(?s).*/resume ([0-9]{4}) .*","$1");
  var released=answered(send("requests",wire(207,1,"resume_confirm",ok)),wire(207,1,"resume_confirm",ok));assertEquals("succeeded",released.outcome());assertEquals("kill-switch-released",released.code());assertFalse(killSwitch.isEngaged());
  var notEngaged=answered(send("requests",wire(208,1,"resume_request",null)),wire(208,1,"resume_request",null));assertEquals("failed",notEngaged.outcome());assertEquals("kill-switch-not-engaged",notEngaged.code());
  var status=answered(send("requests",wire(209,1,"status",null)),wire(209,1,"status",null));assertEquals("status",status.code());assertTrue(status.reply().startsWith("킬스위치: 정상"));
  assertEquals(9,commands.executed.get());
 }

 @Test void observeNeverCreatesAReceiptAndAnUnknownExecutionIsNeverRetried()throws Exception{
  var missing=send("observe",wire(301,1,"stop",null));assertEquals(404,missing.status());assertEquals("stock-control-not-received",error(missing));
  assertEquals(0,count("platform_stock_control_receipt",301));assertEquals(0,count("platform_stock_control_attempt",301));assertEquals(0,commands.executed.get());
  commands.fail=true;var stop=wire(302,1,"stop",null);
  var unknown=send("requests",stop);assertEquals(202,unknown.status());assertEquals("unknown",attemptState(302));assertEquals(1,commands.executed.get());
  commands.fail=false;
  assertEquals(202,send("requests",stop).status());assertEquals(202,send("observe",stop).status());assertEquals(1,commands.executed.get());assertFalse(killSwitch.isEngaged());
  assertEquals(1,count("platform_stock_control_receipt",302));assertEquals(0,count("platform_stock_control_result",302));
 }

 @Test void conflictsStaleFenceBadProofsAndTransportViolationsExecuteNothing()throws Exception{
  assertEquals("kill-switch-engaged",answered(send("requests",wire(401,5,"stop",null)),wire(401,5,"stop",null)).code());int executed=commands.executed.get();
  var conflict=send("requests",wire(401,5,"status",null));assertEquals(409,conflict.status());assertEquals("stock-control-update-conflict",error(conflict));
  assertEquals(409,send("observe",wire(401,5,"status",null)).status());
  var stale=send("requests",wire(402,3,"stop",null));assertEquals(403,stale.status());assertEquals(0,count("platform_stock_control_receipt",402));
  var otherKey=platform.post(port,"requests",platform.proof(wire(403,5,"stop",null),clock.now,clock.now.plusSeconds(30),PlatformStockControlReplica.keys().getPrivate()));assertEquals(403,otherKey.status());
  var tooLong=platform.post(port,"requests",platform.proof(wire(403,5,"stop",null),clock.now,clock.now.plusSeconds(31),platform.requestPair.getPrivate()));assertEquals(403,tooLong.status());
  byte[] late=platform.proof(wire(403,5,"stop",null),clock.now);clock.now=clock.now.plusSeconds(30);assertEquals(403,platform.post(port,"requests",late).status());
  var tampered=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(platform.proof(wire(403,5,"stop",null),clock.now));tampered.put("request_base64",Base64.getEncoder().encodeToString(platform.wire(wire(403,5,"status",null))));
  assertEquals(403,platform.post(port,"requests",json.writeValueAsBytes(tampered)).status());
  for(String bad:new String[]{"{}","not json","{\"contract_version\":\"1.0\"}",new String(platform.proof(wire(403,5,"stop",null),clock.now),StandardCharsets.UTF_8).replace("{","{\"extra\":\"x\",")}){
   var r=platform.post(port,"requests",bad.getBytes(StandardCharsets.UTF_8));assertEquals(400,r.status(),bad);assertEquals("stock-control-malformed",error(r));
  }
  assertEquals(0,count("platform_stock_control_receipt",403));assertEquals(executed,commands.executed.get());
  byte[] good=platform.proof(wire(404,5,"status",null),clock.now);
  for(String path:new String[]{"/platform/stock-control/v1/execute","/platform/stock-control/v1/requests/","/platform/stock-control/v2/requests","/receiver-handover/stop","/"})assertEquals(404,raw("POST "+path+" HTTP/1.1",headers(good),good).status,path);
  assertEquals(405,raw("GET /platform/stock-control/v1/requests HTTP/1.1",Map.of(),new byte[0]).status);
  assertEquals(405,raw("POST /platform/stock-control/v1/health HTTP/1.1",headers(good),good).status);
  assertEquals(400,raw("POST /platform/stock-control/v1/requests?x=1 HTTP/1.1",headers(good),good).status);
  var h=headers(good);h.put("Origin","http://evil.example");assertEquals(403,raw("POST /platform/stock-control/v1/requests HTTP/1.1",h,good).status);
  h=headers(good);h.put("Content-Encoding","gzip");assertEquals(400,raw("POST /platform/stock-control/v1/requests HTTP/1.1",h,good).status);
  h=headers(good);h.put("Content-Length","9000");assertEquals(413,raw("POST /platform/stock-control/v1/requests HTTP/1.1",h,new byte[9000]).status);
  assertEquals(0,count("platform_stock_control_receipt",404));assertEquals(executed,commands.executed.get());
  assertEquals(200,raw("GET /platform/stock-control/v1/health HTTP/1.1",Map.of(),new byte[0]).status);
 }

 @Test void ackForMissingResultIsDeniedAndAConflictingBindingIsRefused()throws Exception{
  var stop=wire(501,1,"stop",null);var first=platform.post(port,"requests",platform.proof(stop,clock.now));var result=answered(first,stop);
  var ghost=new PlatformStockControlReplica.Result(UUID.randomUUID(),"succeeded","x","x",result.resultSha256(),result.requestSha256());
  assertEquals(403,platform.post(port,"acks",platform.ack("599",ghost,first.body(),clock.now,clock.now)).status());
  assertEquals(403,platform.post(port,"acks",platform.ack("501",ghost,first.body(),clock.now,clock.now)).status());
  assertEquals(200,platform.post(port,"acks",platform.ack("501",result,first.body(),clock.now,clock.now)).status());
  clock.now=clock.now.plusSeconds(1);var conflict=platform.post(port,"acks",platform.ack("501",result,first.body(),clock.now,clock.now));assertEquals(409,conflict.status());assertEquals("stock-result-ack-conflict",error(conflict));
  assertEquals(400,platform.post(port,"acks","{}".getBytes(StandardCharsets.UTF_8)).status());assertEquals(1,commands.executed.get());
 }

 record Raw(int status,String body){}
 Map<String,String> headers(byte[] body){var h=new LinkedHashMap<String,String>();h.put("Content-Type","application/json");h.put("Content-Length",String.valueOf(body.length));return h;}
 Raw raw(String line,Map<String,String> headers,byte[] body)throws IOException{
  try(var socket=new Socket(InetAddress.getByAddress(new byte[]{127,0,0,1}),port)){
   socket.setSoTimeout(30_000);var head=new StringBuilder(line).append("\r\nHost: 127.0.0.1\r\nConnection: close\r\n");headers.forEach((k,v)->head.append(k).append(": ").append(v).append("\r\n"));head.append("\r\n");
   var out=socket.getOutputStream();out.write(head.toString().getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
   String text=new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
   return new Raw(Integer.parseInt(text.substring(9,12)),text.substring(text.indexOf("\r\n\r\n")+4));
  }
 }
}
