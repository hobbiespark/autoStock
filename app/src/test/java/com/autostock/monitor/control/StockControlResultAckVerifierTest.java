package com.autostock.monitor.control;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StockControlResultAckVerifierTest {
 static final Instant NOW=StockControlVerifierTest.NOW;static final String PURPOSE="valunivo-telegram-control-result-ack-v1";
 final StockControlVerifierTest.Time clock=new StockControlVerifierTest.Time();final KeyPair pair=StockControlVerifierTest.keys();final ObjectMapper json=new ObjectMapper();
 StockControlResultAckVerifier verifier(String bot){return new StockControlResultAckVerifier("platform_ack_test",pair.getPublic(),bot,"sp_self","b".repeat(64),clock);}
 ObjectNode statement(String bot,String update,UUID attempt,String request,String outcome,String result){var s=json.createObjectNode();s.put("contract_version","1.0");s.put("app_id","autostock");s.put("bot_ref",bot);s.put("space_id","sp_self");s.put("actor_ref","b".repeat(64));s.put("source_update_id",update);s.put("attempt_id",attempt.toString());s.put("request_sha256",request);s.put("outcome",outcome);s.put("result_sha256",result);s.put("result_proof_sha256","d".repeat(64));s.put("platform_recorded_at",NOW.minusSeconds(1).toString());return s;}
 ObjectNode statement(){return statement("a".repeat(64),"7",UUID.fromString("00000000-0000-4000-8000-000000000007"),"e".repeat(64),"succeeded","f".repeat(64));}
 byte[] sign(ObjectNode statement,Instant issued,Instant expires,PrivateKey key,String purpose)throws Exception{
  String encoded=Base64.getEncoder().encodeToString(json.writeValueAsBytes(statement));var bytes=new ByteArrayOutputStream();var framed=new DataOutputStream(bytes);
  for(String field:List.of(purpose,"platform_ack_test",issued.toString(),expires.toString(),encoded)){byte[] utf8=field.getBytes(StandardCharsets.UTF_8);framed.writeInt(utf8.length);framed.write(utf8);}
  var sign=Signature.getInstance("Ed25519");sign.initSign(key);sign.update(bytes.toByteArray());return json.writeValueAsBytes(Map.of("contract_version","1.0","key_id","platform_ack_test","issued_at",issued.toString(),"expires_at",expires.toString(),"statement_base64",encoded,"signature",Base64.getEncoder().encodeToString(sign.sign())));
 }
 byte[] sign(ObjectNode statement)throws Exception{return sign(statement,NOW,NOW.plusSeconds(30),pair.getPrivate(),PURPOSE);}
 @Test void verifiedAckIsPrivateDefensiveAndBoundToVerifier()throws Exception{
  var verifier=verifier("a".repeat(64));byte[] raw=sign(statement());var value=verifier.verify(raw);verifier.requireVerified(value);assertEquals("7",value.ack().sourceUpdateId());assertEquals("succeeded",value.ack().outcome());assertEquals(NOW.minusSeconds(1),value.ack().platformRecordedAt());raw[0]=0;byte[] exposed=value.envelopeBytes();exposed[0]=0;assertEquals('{',value.envelopeBytes()[0]);assertThrows(SecurityException.class,()->verifier("a".repeat(64)).requireVerified(value));
  for(var ctor:StockControlResultAckVerifier.Verified.class.getDeclaredConstructors())assertTrue(java.lang.reflect.Modifier.isPrivate(ctor.getModifiers()));
 }
 @Test void otherPurposesKeysScopesAndFieldsAreDenied()throws Exception{
  var verifier=verifier("a".repeat(64));
  for(String purpose:List.of("autostock-telegram-control-result-proof-v1","valunivo-telegram-stock-control-proof-v1","other"))assertThrows(SecurityException.class,()->verifier.verify(sign(statement(),NOW,NOW.plusSeconds(30),pair.getPrivate(),purpose)));
  assertThrows(SecurityException.class,()->verifier.verify(sign(statement(),NOW,NOW.plusSeconds(30),StockControlVerifierTest.keys().getPrivate(),PURPOSE)));
  for(int mode=0;mode<10;mode++){var s=statement();switch(mode){case 0->s.put("space_id","sp_other");case 1->s.put("bot_ref","c".repeat(64));case 2->s.put("actor_ref","c".repeat(64));case 3->s.put("outcome","unknown");case 4->s.put("extra",true);case 5->s.remove("result_proof_sha256");case 6->s.put("platform_recorded_at",NOW.plusSeconds(1).toString());case 7->s.put("source_update_id",7);case 8->s.put("attempt_id","00000000-0000-4000-8000-00000000000A");case 9->s.put("app_id","writing");}
   byte[] signed=sign(s);assertEquals("stock-result-ack-denied",assertThrows(SecurityException.class,()->verifier.verify(signed)).getMessage());}
 }
 @Test void freshnessIsRecheckedAndEnvelopeAmbiguityDenied()throws Exception{
  var verifier=verifier("a".repeat(64));var value=verifier.verify(sign(statement()));clock.now=NOW.plusSeconds(30);assertThrows(SecurityException.class,()->verifier.requireVerified(value));clock.now=NOW;
  for(Instant[] t:List.of(new Instant[]{NOW.plusSeconds(1),NOW.plusSeconds(30)},new Instant[]{NOW,NOW.plusSeconds(31)},new Instant[]{NOW,NOW}))assertThrows(SecurityException.class,()->verifier.verify(sign(statement(),t[0],t[1],pair.getPrivate(),PURPOSE)));
  String valid=new String(sign(statement()),StandardCharsets.UTF_8);for(String bad:List.of(valid+"{}",valid.replace("{","{\"extra\":true,"),valid.replace("{","{\"key_id\":\"dup\",")))assertThrows(SecurityException.class,()->verifier.verify(bad.getBytes(StandardCharsets.UTF_8)));assertThrows(SecurityException.class,()->verifier.verify(new byte[8193]));assertThrows(SecurityException.class,()->verifier.verify(null));
 }
 @Test void verifiesActualPlatformAckFixturesAndRejectsTampering()throws Exception{
  String directory=System.getenv("AUTOSTOCK_RESULT_ACK_INTEROP_DIR");org.junit.jupiter.api.Assumptions.assumeTrue(directory!=null&&!directory.isBlank(),"explicit synthetic public fixture required");Path root=Path.of(directory);var metadata=json.readTree(Files.readAllBytes(root.resolve("ack-public-metadata.json")));
  var key=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(metadata.get("public_key").textValue())));var verifier=new StockControlResultAckVerifier(metadata.get("key_id").textValue(),key,metadata.get("bot_ref").textValue(),"sp_self","b".repeat(64),Clock.fixed(Instant.parse(metadata.get("now").textValue()),ZoneOffset.UTC));
  for(String outcome:List.of("succeeded","failed")){String id=outcome.equals("succeeded")?"50":"51";byte[] raw=Files.readAllBytes(root.resolve("ack-"+outcome+".json"));var value=verifier.verify(raw);var expected=metadata.get("bindings").get(id);var ack=value.ack();assertEquals(id,ack.sourceUpdateId());assertEquals(expected.get("attempt_id").textValue(),ack.attemptId().toString());assertEquals(expected.get("request_sha256").textValue(),ack.requestFingerprint());assertEquals(expected.get("result_sha256").textValue(),ack.resultSha256());assertEquals(outcome,ack.outcome());
   var tampered=(ObjectNode)json.readTree(raw);var s=(ObjectNode)json.readTree(Base64.getDecoder().decode(tampered.get("statement_base64").textValue()));s.put("result_sha256","0".repeat(64));tampered.put("statement_base64",Base64.getEncoder().encodeToString(json.writeValueAsBytes(s)));assertThrows(SecurityException.class,()->verifier.verify(json.writeValueAsBytes(tampered)));
   var asResultProof=new StockControlVerifier(metadata.get("key_id").textValue(),key,metadata.get("bot_ref").textValue(),"sp_self","b".repeat(64),Clock.fixed(Instant.parse(metadata.get("now").textValue()),ZoneOffset.UTC));assertThrows(SecurityException.class,()->asResultProof.verify(raw));}
 }
}
