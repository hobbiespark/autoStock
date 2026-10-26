package com.autostock.monitor;

import com.autostock.monitor.control.*;
import com.autostock.portfolio.PositionBook;
import com.autostock.risk.KillSwitch;
import com.autostock.support.PostgresDataJpaTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class OperatorHandoverCoordinatorDbTest extends PostgresDataJpaTest {
 @Autowired DataSource source;
 static final String INSTANCE="operator_test",KID="operator_test";
 static class Time extends Clock {Instant now=Instant.parse("2026-10-26T12:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}}
 final Time clock=new Time();final byte[] key=new byte[32];final HandoverOperatorGate gate=new HandoverOperatorGate(KID,key,INSTANCE,clock);final List<String> urls=new ArrayList<>();
 TelegramReceiverStopStore stops;TelegramCommandPoller poller;
 @BeforeEach void setup(){stops=new TelegramReceiverStopStore(source);stops.active().ifPresent(s->stops.withdraw(s.stopId(),"test-cleanup"));}
 static Mono<ClientResponse> json(){return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type","application/json").body("{\"ok\":true,\"result\":[]}").build());}
 OperatorHandoverCoordinator coordinator(Function<String,Mono<ClientResponse>> respond){
  var builder=WebClient.builder().exchangeFunction(request->{urls.add(request.url().toString());return respond.apply(request.url().toString());});
  poller=new TelegramCommandPoller(builder,new TelegramProperties(true,"synthetic-bot","123"),mock(KillSwitch.class),new PositionBook(),(level,message)->{},clock);poller.receiverStopStore(stops);
  return new OperatorHandoverCoordinator(gate,new HandoverOperatorRequestStore(source,gate),new HandoverOperatorAttemptStore(source,gate,clock),stops,poller);
 }
 HandoverOperatorGate.Verified request(String action,UUID target)throws Exception{
  var body=new ObjectMapper().createObjectNode();body.put("contract_version","1.0");body.put("request_id",UUID.randomUUID().toString());body.put("action",action);body.put("instance_id",INSTANCE);body.putNull("expected_active_stop_id");if(target==null)body.putNull("target_request_id");else body.put("target_request_id",target.toString());body.put("issued_at",clock.now.toString());body.put("expires_at",clock.now.plusSeconds(30).toString());
  byte[] bytes=new ObjectMapper().writeValueAsBytes(body);String path="/receiver-handover/"+action;var buffer=new ByteArrayOutputStream();var frame=new DataOutputStream(buffer);for(String text:List.of("autostock-operator-handover-request-v1",KID,"POST",path)){byte[] raw=text.getBytes(StandardCharsets.UTF_8);frame.writeInt(raw.length);frame.write(raw);}frame.writeInt(bytes.length);frame.write(bytes);var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return gate.verify("POST",path,KID,HexFormat.of().formatHex(mac.doFinal(buffer.toByteArray())),bytes);
 }
 @Test void authenticatedStopObservesOriginalEvidenceAndReplayNeverConfirmsAgain()throws Exception{
  var coordinator=coordinator(url->json());var request=request("stop",null);var stop=coordinator.stop(request);assertEquals(1,urls.size());assertTrue(urls.getFirst().contains("offset=0")&&urls.getFirst().contains("limit=1"));
  assertThrows(IllegalStateException.class,()->coordinator.stop(request));assertEquals(1,urls.size());clock.now=clock.now.plusSeconds(40);var observed=coordinator.observe(request("observe",request.request().requestId())).orElseThrow();assertEquals("stopped",observed.state());assertEquals(stop,observed.stop().orElseThrow().stop());assertEquals(1,urls.size());poller.poll();assertEquals(1,urls.size());
  stops.withdraw(stop.stopId(),INSTANCE);assertEquals("withdrawn",coordinator.observe(request("observe",request.request().requestId())).orElseThrow().state());assertEquals(1,urls.size());
 }
 @Test void expiryAtPollerStateReadRejectsAfterDispatchWithoutConfirming()throws Exception{
  stops=new TelegramReceiverStopStore(source){@Override public Optional<Stop> active(){var result=super.active();clock.now=clock.now.plusSeconds(30);return result;}};
  var coordinator=coordinator(url->json());var request=request("stop",null);assertThrows(IllegalStateException.class,()->coordinator.stop(request));assertTrue(urls.isEmpty());assertEquals("unknown",coordinator.observe(request("observe",request.request().requestId())).orElseThrow().state());
 }
 @Test void failedConfirmationBecomesUnknownAndSameRequestCannotRetry()throws Exception{
  var coordinator=coordinator(url->url.contains("limit=1")?Mono.just(ClientResponse.create(HttpStatus.CONFLICT).build()):json());var request=request("stop",null);assertThrows(IllegalStateException.class,()->coordinator.stop(request));assertEquals("unknown",coordinator.observe(request("observe",request.request().requestId())).orElseThrow().state());assertTrue(stops.active().isEmpty());assertThrows(IllegalStateException.class,()->coordinator.stop(request));assertEquals(1,urls.size());poller.poll();assertEquals(2,urls.size());
 }
 @Test void stopCompletedAfterRequestExpiryStillKeepsDurableEvidence()throws Exception{
  var coordinator=coordinator(url->{clock.now=clock.now.plusSeconds(31);return json();});var request=request("stop",null);var stop=coordinator.stop(request);assertEquals(stop,coordinator.observe(request("observe",request.request().requestId())).orElseThrow().stop().orElseThrow().stop());assertEquals(1,urls.size());assertThrows(SecurityException.class,()->coordinator.stop(request));
 }
}
