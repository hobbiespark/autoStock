package com.autostock.monitor.control;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class HandoverOperatorGateTest {
 static final Instant NOW=Instant.parse("2026-10-26T12:00:00Z");
 static final String KID="operator_test",INSTANCE="autostock_test",DOMAIN="autostock-operator-handover-request-v1";
 static class Time extends Clock{Instant now=NOW;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}}
 final Time clock=new Time();final byte[] key=new byte[32];final ObjectMapper json=new ObjectMapper();final HandoverOperatorGate gate=new HandoverOperatorGate(KID,key,INSTANCE,clock);
 ObjectNode body(String action){var n=json.createObjectNode();n.put("contract_version","1.0");n.put("request_id",UUID.randomUUID().toString());n.put("action",action);n.put("instance_id",INSTANCE);n.putNull("expected_active_stop_id");if(action.equals("observe"))n.put("target_request_id",UUID.randomUUID().toString());else n.putNull("target_request_id");n.put("issued_at",NOW.toString());n.put("expires_at",NOW.plusSeconds(30).toString());return n;}
 String mac(String path,byte[] bytes,String domain,byte[] secret)throws Exception{var buffer=new ByteArrayOutputStream();var framed=new DataOutputStream(buffer);for(String value:List.of(domain,KID,"POST",path)){byte[] raw=value.getBytes(StandardCharsets.UTF_8);framed.writeInt(raw.length);framed.write(raw);}framed.writeInt(bytes.length);framed.write(bytes);var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(buffer.toByteArray()));}
 HandoverOperatorGate.Verified verify(ObjectNode node)throws Exception{byte[] bytes=json.writeValueAsBytes(node);String path="/receiver-handover/"+node.get("action").textValue();return gate.verify("POST",path,KID,mac(path,bytes,DOMAIN,key),bytes);}
 void deniedRaw(byte[] bytes,String path)throws Exception{String signature=mac(path,bytes,DOMAIN,key);assertEquals("operator-handover-denied",assertThrows(SecurityException.class,()->gate.verify("POST",path,KID,signature,bytes)).getMessage());}
 @Test void stopAndObserveProduceOnlyPrivateVerifiedRequest()throws Exception{
  for(String action:List.of("stop","observe")){var n=body(action);var verified=verify(n);gate.requireVerified(verified);assertEquals(action,verified.request().action().name());assertEquals(INSTANCE,verified.request().instanceId());assertEquals(64,verified.fingerprint().length());assertFalse(verified.toString().contains(n.get("request_id").textValue()));assertEquals(action.equals("observe"),verified.request().targetRequestId()!=null);}
  for(var ctor:HandoverOperatorGate.Verified.class.getDeclaredConstructors())assertTrue(java.lang.reflect.Modifier.isPrivate(ctor.getModifiers()));
 }
 @Test void methodPathKeyPurposeMacAndBodyAreBound()throws Exception{
  byte[] bytes=json.writeValueAsBytes(body("stop"));String path="/receiver-handover/stop",signature=mac(path,bytes,DOMAIN,key);assertThrows(SecurityException.class,()->gate.verify("GET",path,KID,signature,bytes));assertThrows(SecurityException.class,()->gate.verify("POST","/receiver-handover/observe",KID,signature,bytes));assertThrows(SecurityException.class,()->gate.verify("POST",path,"other",signature,bytes));assertThrows(SecurityException.class,()->gate.verify("POST",path,KID,mac(path,bytes,"other-purpose",key),bytes));byte[] wrong=key.clone();wrong[0]=1;assertThrows(SecurityException.class,()->gate.verify("POST",path,KID,mac(path,bytes,DOMAIN,wrong),bytes));bytes[bytes.length-1]^=1;assertThrows(SecurityException.class,()->gate.verify("POST",path,KID,signature,bytes));
 }
 @Test void unsupportedActionsAndNullableCorrelationsAreDeniedEvenWithValidMac()throws Exception{
  for(int mode=0;mode<5;mode++){var n=body(mode>=3?"observe":"stop");switch(mode){case 0->n.put("action","withdraw");case 1->n.put("expected_active_stop_id",UUID.randomUUID().toString());case 2->n.put("target_request_id",UUID.randomUUID().toString());case 3->n.putNull("target_request_id");case 4->n.put("expected_active_stop_id",UUID.randomUUID().toString());}assertThrows(SecurityException.class,()->verify(n));}
  var instance=body("stop");instance.put("instance_id","other_instance");assertThrows(SecurityException.class,()->verify(instance));
 }
 @Test void replayLedgerIsSeparateButGateOwnershipAndDeadlineAreRechecked()throws Exception{
  var n=body("stop");var first=verify(n);assertEquals(first.fingerprint(),verify(n).fingerprint());assertThrows(SecurityException.class,()->new HandoverOperatorGate(KID,key,INSTANCE,clock).requireVerified(first));clock.now=NOW.plusSeconds(30);assertThrows(SecurityException.class,()->gate.requireVerified(first));assertThrows(SecurityException.class,()->verify(n));clock.now=NOW;
  for(int mode=0;mode<3;mode++){var time=body("stop");if(mode==0)time.put("issued_at",NOW.plusSeconds(1).toString());if(mode==1)time.put("expires_at",NOW.plusSeconds(31).toString());if(mode==2)time.put("issued_at","2026-10-26T12:00:00+00:00");assertThrows(SecurityException.class,()->verify(time));}
 }
 @Test void strictJsonUuidUtf8AndByteLimitsRejectAuthenticatedMalformedBodies()throws Exception{
  String valid=json.writeValueAsString(body("stop"));for(String raw:List.of(valid+"{}",valid.replace("{","{\"extra\":true,"),valid.replace("{","{\"action\":\"observe\","),valid.replace("\"request_id\":", "\"request_id\":null,\"duplicate\":")))deniedRaw(raw.getBytes(StandardCharsets.UTF_8),"/receiver-handover/stop");deniedRaw(new byte[]{(byte)0xc3,0x28},"/receiver-handover/stop");deniedRaw(new byte[4097],"/receiver-handover/stop");var id=body("stop");id.put("request_id","1-1-1-1-1");assertThrows(SecurityException.class,()->verify(id));
 }
 @Test void inputAndConfiguredKeyAreDefensivelyCaptured()throws Exception{
  byte[] secret=new byte[32],original=secret.clone();var isolated=new HandoverOperatorGate(KID,secret,INSTANCE,clock);secret[0]=9;byte[] bytes=json.writeValueAsBytes(body("stop"));var verified=isolated.verify("POST","/receiver-handover/stop",KID,mac("/receiver-handover/stop",bytes,DOMAIN,original),bytes);String fingerprint=verified.fingerprint();bytes[0]=0;isolated.requireVerified(verified);assertEquals(fingerprint,verified.fingerprint());
 }
 @Test void queryRoutesBadSignatureAndInvalidConfigHaveNoFallback()throws Exception{
  byte[] bytes=json.writeValueAsBytes(body("stop"));assertThrows(SecurityException.class,()->gate.verify("POST","/receiver-handover/stop?x=1",KID,"0".repeat(64),bytes));assertThrows(SecurityException.class,()->gate.verify("POST","/receiver-handover/stop",KID,"0".repeat(63),bytes));assertThrows(IllegalArgumentException.class,()->new HandoverOperatorGate(KID,new byte[31],INSTANCE,clock));assertThrows(IllegalArgumentException.class,()->new HandoverOperatorGate(KID,key,"../instance",clock));
 }
}
