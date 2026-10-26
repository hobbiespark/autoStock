package com.autostock.monitor;

import com.autostock.monitor.control.*;
import com.autostock.portfolio.PositionBook;
import com.autostock.risk.KillSwitch;
import com.autostock.support.PostgresDataJpaTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/** Real loopback sockets + isolated D-18 DB + fake Bot API exchange. Sample key only; no operating DB, bot or offset. */
class HandoverOperatorListenerDbTest extends PostgresDataJpaTest {
 @Autowired DataSource source;
 static final String INSTANCE="listener_test",KID="listener_test",SAMPLE="000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
 static final byte[] KEY=HexFormat.of().parseHex(SAMPLE);
 static class Time extends Clock {volatile Instant now=Instant.parse("2026-10-26T12:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}}
 record Reply(int status,JsonNode body){String error(){return body.path("error").asText(null);}}
 record Signed(UUID id,String path,byte[] body,String signature){}
 final Time clock=new Time();final List<String> urls=new CopyOnWriteArrayList<>();final HandoverOperatorGate gate=new HandoverOperatorGate(KID,KEY,INSTANCE,clock);
 TelegramReceiverStopStore stops;HandoverOperatorListener listener;
 @TempDir Path dir;
 @BeforeEach void setup(){stops=new TelegramReceiverStopStore(source);stops.active().ifPresent(s->stops.withdraw(s.stopId(),"test-cleanup"));}
 @AfterEach void close(){if(listener!=null)listener.close();stops.active().ifPresent(s->stops.withdraw(s.stopId(),"test-cleanup"));}
 static Mono<ClientResponse> json(){return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type","application/json").body("{\"ok\":true,\"result\":[]}").build());}
 void start(Function<String,Mono<ClientResponse>> respond)throws IOException{
  var builder=WebClient.builder().exchangeFunction(request->{urls.add(request.url().toString());return respond.apply(request.url().toString());});
  var poller=new TelegramCommandPoller(builder,new TelegramProperties(true,"synthetic-bot","123"),mock(KillSwitch.class),new PositionBook(),(level,message)->{},clock);poller.receiverStopStore(stops);
  var coordinator=new OperatorHandoverCoordinator(gate,new HandoverOperatorRequestStore(source,gate),new HandoverOperatorAttemptStore(source,gate,clock),stops,poller);
  listener=new HandoverOperatorListener(gate,coordinator,stops,0);listener.start();
 }
 Signed signed(HandoverOperatorGate.Action action,UUID id,UUID target,Instant issued,Instant expires){
  String path=HandoverOperatorSigner.path(action);byte[] body=HandoverOperatorSigner.body(id,action,INSTANCE,target,issued,expires);return new Signed(id,path,body,HandoverOperatorSigner.signature(KID,KEY,path,body));
 }
 Signed stop(){return signed(HandoverOperatorGate.Action.stop,UUID.randomUUID(),null,clock.now,clock.now.plusSeconds(20));}
 Signed observe(UUID target){return signed(HandoverOperatorGate.Action.observe,UUID.randomUUID(),target,clock.now,clock.now.plusSeconds(20));}
 Map<String,String> headers(Signed s){var h=new LinkedHashMap<String,String>();h.put("Content-Type","application/json");h.put("Content-Length",String.valueOf(s.body().length));h.put(HandoverOperatorSigner.KEY_ID_HEADER,KID);h.put(HandoverOperatorSigner.SIGNATURE_HEADER,s.signature());return h;}
 Reply post(Signed s)throws IOException{return raw("POST "+s.path()+" HTTP/1.1",headers(s),s.body());}
 Reply raw(String line,Map<String,String> headers,byte[] body)throws IOException{
  try(var socket=new Socket(InetAddress.getByAddress(new byte[]{127,0,0,1}),listener.port())){
   socket.setSoTimeout(30_000);var head=new StringBuilder(line).append("\r\nHost: 127.0.0.1\r\nConnection: close\r\n");headers.forEach((k,v)->head.append(k).append(": ").append(v).append("\r\n"));head.append("\r\n");
   var out=socket.getOutputStream();out.write(head.toString().getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
   String text=new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
   String payload=text.substring(text.indexOf("\r\n\r\n")+4);
   return new Reply(Integer.parseInt(text.substring(9,12)),payload.startsWith("{")?new ObjectMapper().readTree(payload):com.fasterxml.jackson.databind.node.NullNode.getInstance());
  }
 }

 @Test void authenticatedStopRunsOnceAndSameRequestNeverExecutesAgain()throws Exception{
  start(url->json());var request=stop();var headers=headers(request);
  // Host/Forwarded strings are not authentication inputs: only the real socket and the HMAC gate decide.
  headers.put("Forwarded","for=203.0.113.9");headers.put("X-Forwarded-For","203.0.113.9");
  var first=raw("POST "+request.path()+" HTTP/1.1",headers,request.body());
  assertEquals(200,first.status());assertEquals("stopped",first.body().get("result").asText());assertEquals(request.id().toString(),first.body().get("request_id").asText());
  assertEquals(stops.active().orElseThrow().stopId().toString(),first.body().get("stop").get("stop_id").asText());assertEquals(0,first.body().get("stop").get("confirmed_next_offset").asLong());assertEquals(1,urls.size());
  var replay=post(request);assertEquals(409,replay.status());assertEquals("operator-receiver-already-stopped",replay.error());assertEquals(1,urls.size());
  var observed=post(observe(request.id()));assertEquals(200,observed.status());assertEquals("stopped",observed.body().get("state").asText());assertTrue(observed.body().get("stop").get("active").asBoolean());
  assertEquals(first.body().get("stop").get("stop_id"),observed.body().get("stop").get("stop_id"));assertEquals(1,urls.size());
 }
 @Test void unconfirmedStopIsObservedAsUnknownAndReplayOrConflictNeverRetries()throws Exception{
  start(url->Mono.just(ClientResponse.create(HttpStatus.CONFLICT).build()));var request=stop();
  var failed=post(request);assertEquals(503,failed.status());assertEquals("operator-stop-outcome-unconfirmed-use-observe",failed.error());assertEquals(1,urls.size());assertTrue(stops.active().isEmpty());
  var replay=post(request);assertEquals(409,replay.status());assertEquals("operator-request-already-received-use-observe",replay.error());
  var changed=signed(HandoverOperatorGate.Action.stop,request.id(),null,clock.now,clock.now.plusSeconds(25));var conflict=post(changed);assertEquals(409,conflict.status());assertEquals("operator-request-id-conflict",conflict.error());
  assertEquals(1,urls.size());clock.now=clock.now.plusSeconds(40);
  var observed=post(observe(request.id()));assertEquals(200,observed.status());assertEquals("unknown",observed.body().get("state").asText());assertTrue(observed.body().get("stop").isNull());assertEquals(1,urls.size());
 }
 @Test void lostResponseIsRecoveredByObserveWithoutSecondExecution()throws Exception{
  start(url->json());var request=stop();var h=headers(request);
  try(var socket=new Socket(InetAddress.getByAddress(new byte[]{127,0,0,1}),listener.port())){
   var head=new StringBuilder("POST "+request.path()+" HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n");h.forEach((k,v)->head.append(k).append(": ").append(v).append("\r\n"));head.append("\r\n");
   socket.getOutputStream().write(head.toString().getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(request.body());socket.getOutputStream().flush();
  } // closed without reading: the caller never sees the answer
  long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();while(stops.active().isEmpty()&&System.nanoTime()<deadline)Thread.sleep(50);
  var active=stops.active().orElseThrow();
  var observed=post(observe(request.id()));assertEquals(200,observed.status());assertEquals("stopped",observed.body().get("state").asText());assertEquals(active.stopId().toString(),observed.body().get("stop").get("stop_id").asText());
  assertEquals(1,urls.size());assertEquals(409,post(request).status());assertEquals(1,urls.size());
 }
 @Test void observeOfUnknownTargetIsNotFound()throws Exception{
  start(url->json());var missing=post(observe(UUID.randomUUID()));assertEquals(404,missing.status());assertEquals("operator-request-not-found",missing.error());assertTrue(urls.isEmpty());
 }
 @Test void badSignatureExpiryKeyAndPathAreDeniedBeforeAnyEffect()throws Exception{
  start(url->json());var good=stop();
  String flipped=(good.signature().charAt(0)=='0'?"1":"0")+good.signature().substring(1);
  assertEquals(403,post(new Signed(good.id(),good.path(),good.body(),flipped)).status());
  var h=headers(good);h.put(HandoverOperatorSigner.KEY_ID_HEADER,"other_key");assertEquals(403,raw("POST "+good.path()+" HTTP/1.1",h,good.body()).status());
  h=headers(good);h.remove(HandoverOperatorSigner.SIGNATURE_HEADER);assertEquals(403,raw("POST "+good.path()+" HTTP/1.1",h,good.body()).status());
  // Signed for observe framing but sent to stop, and vice versa.
  var obs=observe(UUID.randomUUID());assertEquals(403,raw("POST "+HandoverOperatorListener.STOP_PATH+" HTTP/1.1",headers(obs),obs.body()).status());
  assertEquals(403,raw("POST "+HandoverOperatorListener.OBSERVE_PATH+" HTTP/1.1",headers(good),good.body()).status());
  var future=signed(HandoverOperatorGate.Action.stop,UUID.randomUUID(),null,clock.now.plusSeconds(5),clock.now.plusSeconds(20));assertEquals(403,post(future).status());
  var tooLong=signed(HandoverOperatorGate.Action.stop,UUID.randomUUID(),null,clock.now,clock.now.plusSeconds(31));assertEquals(403,post(tooLong).status());
  var late=stop();clock.now=clock.now.plusSeconds(20);var expired=post(late);assertEquals(403,expired.status());assertEquals("operator-handover-denied",expired.error());
  assertTrue(urls.isEmpty());assertTrue(stops.active().isEmpty());
  clock.now=clock.now.plusSeconds(1);assertEquals(404,post(observe(late.id())).status());
 }
 @Test void onlyExactPostStopAndObserveWithoutQueryEncodingOrOriginAreAccepted()throws Exception{
  start(url->json());var good=stop();
  for(String path:new String[]{"/receiver-handover/withdraw","/receiver-handover/resume","/receiver-handover/stop/","/RECEIVER-HANDOVER/STOP","/receiver-handover/%73top","/api/trading/stop","/"})assertEquals(404,raw("POST "+path+" HTTP/1.1",headers(good),good.body()).status(),path);
  assertEquals(400,raw("POST "+good.path()+"?x=1 HTTP/1.1",headers(good),good.body()).status());
  assertEquals(400,raw("POST http://127.0.0.1"+good.path()+" HTTP/1.1",headers(good),good.body()).status());
  for(String method:new String[]{"GET","PUT","OPTIONS","DELETE"})assertEquals(405,raw(method+" "+good.path()+" HTTP/1.1",headers(good),good.body()).status(),method);
  var h=headers(good);h.put("Content-Encoding","gzip");assertEquals(400,raw("POST "+good.path()+" HTTP/1.1",h,good.body()).status());
  h=headers(good);h.put("Origin","http://evil.example");var origin=raw("POST "+good.path()+" HTTP/1.1",h,good.body());assertEquals(403,origin.status());
  h=headers(good);h.remove("Content-Length");h.put("Transfer-Encoding","chunked");String chunk=Integer.toHexString(good.body().length)+"\r\n"+new String(good.body(),StandardCharsets.UTF_8)+"\r\n0\r\n\r\n";
  assertEquals(400,raw("POST "+good.path()+" HTTP/1.1",h,chunk.getBytes(StandardCharsets.UTF_8)).status());
  h=headers(good);h.put("Content-Length","5000");assertEquals(413,raw("POST "+good.path()+" HTTP/1.1",h,new byte[5000]).status());
  h=headers(good);h.put("Content-Length","0");assertEquals(400,raw("POST "+good.path()+" HTTP/1.1",h,new byte[0]).status());
  assertTrue(urls.isEmpty());assertTrue(stops.active().isEmpty());
  var accepted=post(good);assertEquals(200,accepted.status());assertFalse(accepted.body().has("Access-Control-Allow-Origin"));assertEquals(1,urls.size());
 }
 @Test void listenerIsBoundToLoopbackOnlyAndChecksTheRealSocket()throws Exception{
  start(url->json());int port=listener.port();var loop=InetAddress.getByAddress(new byte[]{127,0,0,1});
  assertTrue(HandoverOperatorListener.loopback(new InetSocketAddress(loop,port),new InetSocketAddress(loop,50000),port));
  assertTrue(HandoverOperatorListener.loopback(new InetSocketAddress(InetAddress.getByName("::1"),port),new InetSocketAddress(InetAddress.getByName("::1"),50000),port));
  var remote=InetAddress.getByAddress(new byte[]{10,1,2,3});
  assertFalse(HandoverOperatorListener.loopback(new InetSocketAddress(loop,port),new InetSocketAddress(remote,50000),port));
  assertFalse(HandoverOperatorListener.loopback(new InetSocketAddress(remote,port),new InetSocketAddress(loop,50000),port));
  assertFalse(HandoverOperatorListener.loopback(new InetSocketAddress(loop,port+1),new InetSocketAddress(loop,50000),port));
  assertFalse(HandoverOperatorListener.loopback(null,new InetSocketAddress(loop,50000),port));
  InetAddress external=null;
  for(var nic:Collections.list(NetworkInterface.getNetworkInterfaces()))if(nic.isUp()&&!nic.isLoopback())for(var address:Collections.list(nic.getInetAddresses()))if(address instanceof Inet4Address&&!address.isLoopbackAddress()&&!address.isLinkLocalAddress())external=address;
  assumeTrue(external!=null,"no non-loopback IPv4 address on this host");
  try(var socket=new Socket()){var target=new InetSocketAddress(external,port);assertThrows(IOException.class,()->socket.connect(target,2000));}
  assertTrue(urls.isEmpty());
 }
 @Test void cliSignsStopAndObserveAgainstTheListener()throws Exception{
  start(url->json());Path key=dir.toRealPath().resolve("operator.key");Files.writeString(key,SAMPLE+"\n",StandardCharsets.US_ASCII);
  var out=new ByteArrayOutputStream();var err=new ByteArrayOutputStream();
  String[] common={"--port",String.valueOf(listener.port()),"--key-id",KID,"--key-file",key.toString(),"--instance",INSTANCE};
  String[] stopArgs=Arrays.copyOf(common,common.length+1);stopArgs[common.length]="stop";
  assertEquals(0,HandoverOperatorCli.run(stopArgs,new PrintStream(out,true,StandardCharsets.UTF_8),new PrintStream(err,true,StandardCharsets.UTF_8),clock));
  String id=err.toString(StandardCharsets.UTF_8).replaceAll("(?s).*request_id=([0-9a-f-]{36}).*","$1");assertTrue(out.toString(StandardCharsets.UTF_8).startsWith("status=200"));assertEquals(1,urls.size());
  assertFalse(out.toString(StandardCharsets.UTF_8).contains(SAMPLE)||err.toString(StandardCharsets.UTF_8).contains(SAMPLE));
  out.reset();String[] observeArgs=Arrays.copyOf(common,common.length+3);observeArgs[common.length]="observe";observeArgs[common.length+1]="--target";observeArgs[common.length+2]=id;
  assertEquals(0,HandoverOperatorCli.run(observeArgs,new PrintStream(out,true,StandardCharsets.UTF_8),new PrintStream(err,true,StandardCharsets.UTF_8),clock));
  assertTrue(out.toString(StandardCharsets.UTF_8).contains("\"state\":\"stopped\""));
  out.reset();assertEquals(2,HandoverOperatorCli.run(stopArgs,new PrintStream(out,true,StandardCharsets.UTF_8),new PrintStream(err,true,StandardCharsets.UTF_8),clock));assertTrue(out.toString(StandardCharsets.UTF_8).contains("operator-receiver-already-stopped"));
  assertEquals(1,urls.size());
  listener.close();err.reset();assertEquals(3,HandoverOperatorCli.run(stopArgs,new PrintStream(out,true,StandardCharsets.UTF_8),new PrintStream(err,true,StandardCharsets.UTF_8),clock));assertTrue(err.toString(StandardCharsets.UTF_8).contains("operator-response-unknown-use-observe"));listener=null;
  Files.writeString(key,SAMPLE.toUpperCase());assertEquals(64,HandoverOperatorCli.run(stopArgs,new PrintStream(out,true,StandardCharsets.UTF_8),new PrintStream(err,true,StandardCharsets.UTF_8),clock));
  assertEquals(64,HandoverOperatorCli.run(new String[]{"--key","00","stop"},new PrintStream(out,true,StandardCharsets.UTF_8),new PrintStream(err,true,StandardCharsets.UTF_8),clock));
  assertEquals(1,urls.size());
 }
}
