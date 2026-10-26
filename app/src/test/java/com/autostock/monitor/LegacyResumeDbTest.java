package com.autostock.monitor;

import com.autostock.monitor.control.*;
import com.autostock.portfolio.PositionBook;
import com.autostock.risk.KillSwitch;
import com.autostock.support.PostgresDataJpaTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * P2-2d local operator resume (user decision 2026-10-27, alternative A). The stop is made through the real P2-1 operator
 * listener exactly as the platform runner does; the platform release answer is then written to a file as the platform
 * returns it (161 §3) and fed to the CLI. Isolated D-18 DB, fake Bot API, sample keys only.
 */
class LegacyResumeDbTest extends PostgresDataJpaTest {
 @Autowired DataSource source;
 static final String INSTANCE="listener_test",KID="listener_test",SAMPLE="000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
 static final String RESUME_KID="local_resume_test",RESUME_INSTANCE="local_resume",RESUME_SAMPLE="1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020100";
 static final byte[] KEY=HexFormat.of().parseHex(SAMPLE),RESUME_KEY=HexFormat.of().parseHex(RESUME_SAMPLE);
 static class Time extends Clock{volatile Instant now=Instant.parse("2026-10-27T02:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}}
 final Time clock=new Time();final List<String> urls=new CopyOnWriteArrayList<>();final ObjectMapper json=new ObjectMapper();
 final HandoverOperatorGate operatorGate=new HandoverOperatorGate(KID,KEY,INSTANCE,clock);final LegacyResumeGate resumeGate=new LegacyResumeGate(RESUME_KID,RESUME_KEY,RESUME_INSTANCE,clock);
 TelegramReceiverStopStore stops;TelegramCommandPoller poller;HandoverOperatorListener operatorListener;LegacyResumeListener resumeListener;
 /** update_id 41 is processed before the stop, so the stop confirms offset 42. */
 volatile String updates="{\"ok\":true,\"result\":[{\"update_id\":41,\"message\":{\"chat\":{\"id\":999},\"text\":\"hi\"}}]}";
 @TempDir Path dir;

 @BeforeEach void setup()throws IOException{
  stops=new TelegramReceiverStopStore(source);stops.active().ifPresent(s->stops.withdraw(s.stopId(),"test-cleanup"));
  var builder=WebClient.builder().exchangeFunction(request->{urls.add(request.url().toString());String body=updates;updates="{\"ok\":true,\"result\":[]}";
   return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type","application/json").body(body).build());});
  poller=new TelegramCommandPoller(builder,new TelegramProperties(true,"synthetic-bot","123"),mock(KillSwitch.class),new PositionBook(),(level,message)->{},clock);poller.receiverStopStore(stops);
  operatorListener=new HandoverOperatorListener(operatorGate,new OperatorHandoverCoordinator(operatorGate,new HandoverOperatorRequestStore(source,operatorGate),new HandoverOperatorAttemptStore(source,operatorGate,clock),stops,poller),stops,0);operatorListener.start();
  resumeListener=new LegacyResumeListener(resumeGate,new LegacyResumeOperator(resumeGate,source,stops,poller),0);resumeListener.start();
 }
 @AfterEach void close(){operatorListener.close();resumeListener.close();stops.active().ifPresent(s->stops.withdraw(s.stopId(),"test-cleanup"));}

 record Stopped(UUID requestId,UUID stopId,long confirmed){}
 /** The platform runner's 158 stop, over the real operator listener. */
 Stopped stopLikeThePlatform()throws Exception{
  poller.poll();assertTrue(urls.getLast().contains("offset=0"));
  UUID id=UUID.randomUUID();String path=HandoverOperatorSigner.path(HandoverOperatorGate.Action.stop);byte[] body=HandoverOperatorSigner.body(id,HandoverOperatorGate.Action.stop,INSTANCE,null,clock.now,clock.now.plusSeconds(20));
  var reply=post(operatorListener.port(),path,KID,HandoverOperatorSigner.signature(KID,KEY,path,body),body);assertEquals(200,reply.status,reply.body);
  var stop=json.readTree(reply.body).get("stop");return new Stopped(id,UUID.fromString(stop.get("stop_id").asText()),stop.get("confirmed_next_offset").asLong());
 }
 /** The platform release answer (161 §3, TelegramReceiverHandoverController.release) as the operator would save it. */
 Path release(Stopped s,boolean returned,long nextOffset,long fence,String state,UUID stopId,UUID stopRequestId)throws Exception{
  var root=json.createObjectNode();root.put("lease_returned",returned);root.put("platform_next_offset",nextOffset);root.put("fence",fence);
  var run=root.putObject("run");run.put("run_id",UUID.randomUUID().toString());run.put("state",state);run.put("stop_request_id",stopRequestId.toString());run.put("stop_id",stopId.toString());
  run.put("confirmed_next_offset",s.confirmed());run.putNull("halt_code");root.put("legacy_resume","not-performed-autostock-operator-action-required");
  Path file=dir.toRealPath().resolve(UUID.randomUUID()+".json");Files.write(file,json.writeValueAsBytes(root));return file;
 }
 Path resumeKey()throws Exception{Path key=dir.toRealPath().resolve("resume.key");Files.writeString(key,RESUME_SAMPLE+"\n",StandardCharsets.US_ASCII);return key;}
 record Cli(int exit,String out,String err){}
 Cli cli(Path evidence,String confirm)throws Exception{
  var out=new ByteArrayOutputStream();var err=new ByteArrayOutputStream();
  int exit=LegacyResumeCli.run(new String[]{"--port",String.valueOf(resumeListener.port()),"--key-id",RESUME_KID,"--key-file",resumeKey().toString(),"--instance",RESUME_INSTANCE,"--evidence-file",evidence.toString(),"--confirm",confirm},
   new PrintStream(out,true,StandardCharsets.UTF_8),new PrintStream(err,true,StandardCharsets.UTF_8),clock);
  return new Cli(exit,out.toString(StandardCharsets.UTF_8),err.toString(StandardCharsets.UTF_8));
 }
 String ledger(String column,UUID stop,String state)throws SQLException{
  try(var c=source.getConnection();var q=c.prepareStatement("select "+column+" from telegram_legacy_resume_request where stop_id=? and state=? order by received_at desc limit 1")){q.setObject(1,stop);q.setString(2,state);try(var rows=q.executeQuery()){return rows.next()?rows.getString(1):null;}}
 }
 long rows(String sql,UUID stop)throws SQLException{try(var c=source.getConnection();var q=c.prepareStatement(sql)){q.setObject(1,stop);try(var r=q.executeQuery()){r.next();return r.getLong(1);}}}

 @Test void matchingReleaseEvidenceAndConfirmationResumeOnceFromThePlatformOffset()throws Exception{
  var s=stopLikeThePlatform();assertEquals(42,s.confirmed());int calls=urls.size();poller.poll();assertEquals(calls,urls.size());
  var done=cli(release(s,true,57,3,"released",s.stopId(),s.requestId()),LegacyResumeGate.confirmation(s.stopId()));
  assertEquals(0,done.exit(),done.out()+done.err());assertTrue(done.out().contains("\"result\":\"resumed\""));assertTrue(done.out().contains("\"legacy_next_offset\":57"));
  assertFalse(done.out().contains(RESUME_SAMPLE)||done.err().contains(RESUME_SAMPLE));
  assertTrue(stops.active().isEmpty());assertEquals("57",ledger("legacy_next_offset",s.stopId(),"resumed"));
  assertEquals(1,rows("select count(*) from telegram_receiver_stop where stop_id=? and kind='withdrawn' and instance_id='local_resume'",s.stopId()));
  // Legacy polling restarts above everything the platform admitted.
  poller.poll();assertTrue(urls.getLast().contains("offset=57"),urls.getLast());
  // Same evidence again: a new request, but the stop is no longer active — rejected and recorded, nothing withdrawn twice.
  var again=cli(release(s,true,57,3,"released",s.stopId(),s.requestId()),LegacyResumeGate.confirmation(s.stopId()));
  assertEquals(2,again.exit());assertTrue(again.out().contains("legacy-stop-not-active"));assertEquals("legacy-stop-not-active",ledger("reason",s.stopId(),"rejected"));
  assertEquals(1,rows("select count(*) from telegram_receiver_stop where stop_id=? and kind='withdrawn'",s.stopId()));
 }

 @Test void missingOrMismatchedEvidenceIsRejectedRecordedAndLeavesTheStopActive()throws Exception{
  var s=stopLikeThePlatform();UUID other=UUID.randomUUID();String confirm=LegacyResumeGate.confirmation(s.stopId());
  Map<String,Cli> cases=new LinkedHashMap<>();
  cases.put("platform-lease-not-returned",cli(release(s,false,57,3,"released",s.stopId(),s.requestId()),confirm));
  cases.put("platform-run-not-released",cli(release(s,true,57,3,"halted",s.stopId(),s.requestId()),confirm));
  cases.put("platform-fence-invalid",cli(release(s,true,57,0,"released",s.stopId(),s.requestId()),confirm));
  cases.put("operator-confirmation-mismatch",cli(release(s,true,57,3,"released",s.stopId(),s.requestId()),"resume-legacy-polling:"+other));
  cases.put("stop-id-mismatch",cli(release(s,true,57,3,"released",other,s.requestId()),LegacyResumeGate.confirmation(other)));
  cases.put("platform-stop-request-mismatch",cli(release(s,true,57,3,"released",s.stopId(),UUID.randomUUID()),confirm));
  cases.put("platform-offset-below-legacy-stop",cli(release(s,true,41,3,"released",s.stopId(),s.requestId()),confirm));
  Path empty=dir.toRealPath().resolve("empty.json");Files.writeString(empty,"{\"lease_returned\":true}");
  cases.put("release-evidence-missing",cli(empty,confirm));
  cases.forEach((reason,result)->{assertEquals(2,result.exit(),reason);assertTrue(result.out().startsWith("status=409"),reason);assertTrue(result.out().contains("\"reason\":\""+reason+"\""),reason+" "+result.out());});
  try(var c=source.getConnection();var q=c.prepareStatement("select reason from telegram_legacy_resume_request where state='rejected' and received_at>=now()-interval '10 minutes'");var r=q.executeQuery()){
   var reasons=new HashSet<String>();while(r.next())reasons.add(r.getString(1));assertTrue(reasons.containsAll(cases.keySet()),reasons.toString());
  }
  assertEquals(s.stopId(),stops.active().orElseThrow().stopId());assertEquals(0,rows("select count(*) from telegram_receiver_stop where stop_id=? and kind='withdrawn'",s.stopId()));
  int calls=urls.size();poller.poll();assertEquals(calls,urls.size());
  // Unreadable evidence file or missing confirmation never leaves the CLI.
  Path broken=dir.toRealPath().resolve("broken.json");Files.writeString(broken,"{\"run\":{\"stop_id\":42}}");assertEquals(64,cli(broken,confirm).exit());
  assertEquals(64,LegacyResumeCli.run(new String[]{"--port","18999"},new PrintStream(new ByteArrayOutputStream()),new PrintStream(new ByteArrayOutputStream()),clock));
 }

 @Test void onlyTheLocalResumeKeyAndDomainAuthoriseAndARepeatedRequestNeverRunsTwice()throws Exception{
  var s=stopLikeThePlatform();
  var evidence=new LegacyResumeGate.Evidence(s.stopId(),UUID.randomUUID(),s.requestId(),"released",true,57L,3L);
  UUID id=UUID.randomUUID();byte[] body=LegacyResumeGate.body(id,RESUME_INSTANCE,evidence,LegacyResumeGate.confirmation(s.stopId()),clock.now,clock.now.plusSeconds(20));
  // The platform's 158 key (same framing shape, other domain) cannot authorise a resume, on either listener.
  String platformSigned=HandoverOperatorSigner.signature(KID,KEY,LegacyResumeGate.PATH,body);
  assertEquals(403,post(resumeListener.port(),LegacyResumeGate.PATH,KID,platformSigned,body).status);
  assertEquals(403,post(resumeListener.port(),LegacyResumeGate.PATH,RESUME_KID,LegacyResumeGate.signature(RESUME_KID,KEY,body),body).status);
  assertEquals(404,post(operatorListener.port(),LegacyResumeGate.PATH,RESUME_KID,LegacyResumeGate.signature(RESUME_KID,RESUME_KEY,body),body).status);
  for(String path:new String[]{"/receiver-handover/withdraw","/receiver-handover/resume"})assertEquals(404,post(operatorListener.port(),path,KID,HandoverOperatorSigner.signature(KID,KEY,path,body),body).status);
  assertEquals(404,post(resumeListener.port(),"/receiver-handover/stop",RESUME_KID,LegacyResumeGate.signature(RESUME_KID,RESUME_KEY,body),body).status);
  assertEquals(0,rows("select count(*) from telegram_legacy_resume_request where stop_id=?",s.stopId()));assertTrue(stops.active().isPresent());
  String signature=LegacyResumeGate.signature(RESUME_KID,RESUME_KEY,body);
  var first=post(resumeListener.port(),LegacyResumeGate.PATH,RESUME_KID,signature,body);assertEquals(200,first.status,first.body);assertTrue(stops.active().isEmpty());
  var replay=post(resumeListener.port(),LegacyResumeGate.PATH,RESUME_KID,signature,body);assertEquals(409,replay.status);assertTrue(replay.body.contains("legacy-resume-already-received-resumed"));
  byte[] changed=LegacyResumeGate.body(id,RESUME_INSTANCE,evidence,LegacyResumeGate.confirmation(s.stopId()),clock.now,clock.now.plusSeconds(25));
  assertTrue(post(resumeListener.port(),LegacyResumeGate.PATH,RESUME_KID,LegacyResumeGate.signature(RESUME_KID,RESUME_KEY,changed),changed).body.contains("legacy-resume-request-id-conflict"));
  assertEquals(1,rows("select count(*) from telegram_receiver_stop where stop_id=? and kind='withdrawn'",s.stopId()));
  byte[] late=LegacyResumeGate.body(UUID.randomUUID(),RESUME_INSTANCE,evidence,"x",clock.now,clock.now.plusSeconds(20));clock.now=clock.now.plusSeconds(20);
  assertEquals(403,post(resumeListener.port(),LegacyResumeGate.PATH,RESUME_KID,LegacyResumeGate.signature(RESUME_KID,RESUME_KEY,late),late).status);
  var h=new LinkedHashMap<String,String>();h.put("Origin","http://evil.example");assertEquals(403,post(resumeListener.port(),LegacyResumeGate.PATH,RESUME_KID,signature,body,h).status);
  assertEquals(400,post(resumeListener.port(),LegacyResumeGate.PATH+"?x=1",RESUME_KID,signature,body).status);
 }

 @Test void pollerResumeRestoresOffsetWhenWithdrawIsRefusedAndLedgerDecisionsAreFinal()throws Exception{
  var s=stopLikeThePlatform();
  assertThrows(IllegalStateException.class,()->poller.resumeAfterPlatformRelease(UUID.randomUUID(),RESUME_INSTANCE,99));
  assertThrows(IllegalArgumentException.class,()->poller.resumeAfterPlatformRelease(s.stopId(),RESUME_INSTANCE,-1));
  assertEquals(42,poller.resumeAfterPlatformRelease(s.stopId(),RESUME_INSTANCE,10));poller.poll();assertTrue(urls.getLast().contains("offset=42"),urls.getLast());
  UUID id=UUID.randomUUID();
  try(var c=source.getConnection();var q=c.createStatement()){
   q.execute("insert into telegram_legacy_resume_request(request_id,operator_key_id,instance_id,request_sha256,state,reason,decided_at) values ('"+id+"','k','i','"+"a".repeat(64)+"','rejected','synthetic',now())");
   for(String sql:new String[]{"update telegram_legacy_resume_request set state='resumed' where request_id='"+id+"'","update telegram_legacy_resume_request set reason='other' where request_id='"+id+"'","delete from telegram_legacy_resume_request where request_id='"+id+"'",
     "insert into telegram_legacy_resume_request(request_id,operator_key_id,instance_id,request_sha256,state,legacy_next_offset,decided_at) values ('"+UUID.randomUUID()+"','k','i','"+"a".repeat(64)+"','resumed',1,now())"}){
    var e=assertThrows(SQLException.class,()->q.execute(sql),sql);assertTrue(Set.of("42501","23514").contains(e.getSQLState()),sql+" "+e.getSQLState());
   }
  }
 }

 record Reply(int status,String body){}
 Reply post(int port,String path,String kid,String signature,byte[] body)throws IOException{return post(port,path,kid,signature,body,Map.of());}
 Reply post(int port,String path,String kid,String signature,byte[] body,Map<String,String> extra)throws IOException{
  try(var socket=new Socket(InetAddress.getByAddress(new byte[]{127,0,0,1}),port)){
   socket.setSoTimeout(30_000);var head=new StringBuilder("POST "+path+" HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\n")
    .append(HandoverOperatorSigner.KEY_ID_HEADER).append(": ").append(kid).append("\r\n").append(HandoverOperatorSigner.SIGNATURE_HEADER).append(": ").append(signature).append("\r\n");
   extra.forEach((k,v)->head.append(k).append(": ").append(v).append("\r\n"));head.append("\r\n");
   var out=socket.getOutputStream();out.write(head.toString().getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
   String text=new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
   return new Reply(Integer.parseInt(text.substring(9,12)),text.substring(text.indexOf("\r\n\r\n")+4));
  }
 }
}
