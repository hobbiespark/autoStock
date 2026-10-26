package com.autostock.monitor.control;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StockControlVerifierTest {
 static final Instant NOW=Instant.parse("2026-10-26T07:35:00Z");
 static class Time extends Clock {Instant now=NOW;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}}
 final Time clock=new Time();final KeyPair pair=keys();final ObjectMapper json=new ObjectMapper();
 static KeyPair keys(){try{return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();}catch(Exception e){throw new IllegalStateException(e);}}
 StockControlVerifier verifier(){return new StockControlVerifier("platform_test",pair.getPublic(),"a".repeat(64),"sp_self","b".repeat(64),clock);}
 StockControlRequest request(String space){return new StockControlRequest("1.0","autostock","a".repeat(64),space,"b".repeat(64),"7","3",StockControlRequest.Command.resume_confirm,"0427");}
 byte[] proof(byte[] request,Instant issued,Instant expires,PrivateKey privateKey,String domain)throws Exception{
  String base64=Base64.getEncoder().encodeToString(request);var output=new ByteArrayOutputStream();var framed=new DataOutputStream(output);
  for(String value:List.of(domain,"platform_test",issued.toString(),expires.toString(),base64)){byte[] bytes=value.getBytes(StandardCharsets.UTF_8);framed.writeInt(bytes.length);framed.write(bytes);}
  var sign=Signature.getInstance("Ed25519");sign.initSign(privateKey);sign.update(output.toByteArray());return json.writeValueAsBytes(Map.of("contract_version","1.0","key_id","platform_test","issued_at",issued.toString(),"expires_at",expires.toString(),"request_base64",base64,"signature",Base64.getEncoder().encodeToString(sign.sign())));
 }
 byte[] valid()throws Exception{return proof(StockControlWire.encode(request("sp_self")),NOW,NOW.plusSeconds(30),pair.getPrivate(),"valunivo-telegram-stock-control-proof-v1");}
 @Test void verifiedValueIsPrivateDefensiveAndBoundToConfiguredVerifier()throws Exception{
  var verifier=verifier();byte[] original=valid();var value=verifier.verify(original);verifier.requireVerified(value);assertEquals(request("sp_self"),value.request());assertEquals(NOW.plusSeconds(30),value.expiresAt());original[0]=0;byte[] exposed=value.proofBytes();exposed[0]=0;assertEquals('{',value.proofBytes()[0]);assertFalse(value.toString().contains("0427"));assertThrows(SecurityException.class,()->verifier().requireVerified(value));
  for(var constructor:StockControlVerifier.Verified.class.getDeclaredConstructors())assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
 }
 @Test void unsignedWrongKeyAndWrongPurposeCannotCreateVerifiedValue()throws Exception{
  assertThrows(SecurityException.class,()->verifier().verify(StockControlWire.encode(request("sp_self"))));assertThrows(SecurityException.class,()->verifier().verify(proof(StockControlWire.encode(request("sp_self")),NOW,NOW.plusSeconds(30),keys().getPrivate(),"valunivo-telegram-stock-control-proof-v1")));assertThrows(SecurityException.class,()->verifier().verify(proof(StockControlWire.encode(request("sp_self")),NOW,NOW.plusSeconds(30),pair.getPrivate(),"other-purpose")));
 }
 @Test void signedWrongSpaceTamperedPayloadAndMalformedUnsignedRequestAreDenied()throws Exception{
  assertThrows(SecurityException.class,()->verifier().verify(proof(StockControlWire.encode(request("sp_other")),NOW,NOW.plusSeconds(30),pair.getPrivate(),"valunivo-telegram-stock-control-proof-v1")));
  var tampered=json.readTree(valid());((com.fasterxml.jackson.databind.node.ObjectNode)tampered).put("request_base64",Base64.getEncoder().encodeToString(StockControlWire.encode(request("sp_other"))));assertThrows(SecurityException.class,()->verifier().verify(json.writeValueAsBytes(tampered)));
  assertThrows(SecurityException.class,()->verifier().verify(proof("{}".getBytes(StandardCharsets.UTF_8),NOW,NOW.plusSeconds(30),pair.getPrivate(),"valunivo-telegram-stock-control-proof-v1")));
 }
 @Test void deadlineIsCheckedAgainAndOldProofCannotBeRenewedByObservation()throws Exception{
  var verifier=verifier();var value=verifier.verify(valid());clock.now=NOW.plusSeconds(30);assertThrows(SecurityException.class,()->verifier.requireVerified(value));assertThrows(SecurityException.class,()->verifier.verify(valid()));clock.now=NOW;
  for(Instant[] times:List.of(new Instant[]{NOW.plusSeconds(1),NOW.plusSeconds(30)},new Instant[]{NOW,NOW.plusSeconds(31)},new Instant[]{NOW,NOW}))assertThrows(SecurityException.class,()->verifier.verify(proof(StockControlWire.encode(request("sp_self")),times[0],times[1],pair.getPrivate(),"valunivo-telegram-stock-control-proof-v1")));
 }
 @Test void envelopeAmbiguityAndOversizeNeverExposePayloadInError()throws Exception{
  String valid=new String(valid(),StandardCharsets.UTF_8);for(String bad:List.of(valid+"{}",valid.replace("{","{\"extra\":true,"),valid.replace("{","{\"key_id\":\"duplicate\",")))assertEquals("stock-control-proof-denied",assertThrows(SecurityException.class,()->verifier().verify(bad.getBytes(StandardCharsets.UTF_8))).getMessage());assertThrows(SecurityException.class,()->verifier().verify(new byte[8193]));assertThrows(SecurityException.class,()->verifier().verify(new byte[]{(byte)0xc3,0x28}));
 }
}
