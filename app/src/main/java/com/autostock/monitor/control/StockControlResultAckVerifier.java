package com.autostock.monitor.control;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.security.interfaces.EdECPublicKey;
import java.time.*;
import java.util.*;

/** Pinned platform durable-receipt ACK only. Not Telegram delivery, trading effect or a new control request. */
public final class StockControlResultAckVerifier {
 public record Ack(String sourceUpdateId,UUID attemptId,String requestFingerprint,String outcome,String resultSha256,String resultProofSha256,Instant platformRecordedAt){}
 public static final class Verified {
  private final StockControlResultAckVerifier owner;private final Ack ack;private final Instant issued,expires;private final byte[] envelope;
  private Verified(StockControlResultAckVerifier owner,Ack ack,Instant issued,Instant expires,byte[] envelope){this.owner=owner;this.ack=ack;this.issued=issued;this.expires=expires;this.envelope=envelope.clone();}
  public Ack ack(){return ack;}public String botRef(){return owner.bot;}public String keyId(){return owner.keyId;}public Instant expiresAt(){return expires;}public byte[] envelopeBytes(){return envelope.clone();}
  @Override public String toString(){return "VerifiedStockControlResultAck[outcome="+ack.outcome()+"]";}
 }
 private static final Set<String> ENVELOPE=Set.of("contract_version","key_id","issued_at","expires_at","statement_base64","signature");
 private static final Set<String> STATEMENT=Set.of("contract_version","app_id","bot_ref","space_id","actor_ref","source_update_id","attempt_id","request_sha256","outcome","result_sha256","result_proof_sha256","platform_recorded_at");
 private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(2).maxStringLength(6000).maxNumberLength(20).build()).enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
 private final String keyId,bot,space,actor;private final PublicKey key;private final Clock clock;
 public StockControlResultAckVerifier(String keyId,PublicKey key,String bot,String space,String actor,Clock clock){
  if(keyId==null||!keyId.matches("[A-Za-z0-9_-]{1,80}")||!(key instanceof EdECPublicKey ed)||!ed.getParams().getName().equals("Ed25519")||bot==null||!bot.matches("[a-f0-9]{64}")||space==null||!space.matches("[A-Za-z0-9_-]{1,80}")||actor==null||!actor.matches("[a-f0-9]{64}")||clock==null)throw new IllegalArgumentException("invalid-stock-result-ack-trust");
  this.keyId=keyId;this.key=key;this.bot=bot;this.space=space;this.actor=actor;this.clock=clock;
 }
 public Verified verify(byte[] envelope){
  try{
   if(envelope==null||envelope.length==0||envelope.length>8192)throw denied();byte[] snapshot=envelope.clone();var root=parse(snapshot);fields(root,ENVELOPE);if(!text(root,"contract_version").equals("1.0")||!text(root,"key_id").equals(keyId))throw denied();
   String issuedText=text(root,"issued_at"),expiresText=text(root,"expires_at");Instant issued=Instant.parse(issuedText),expires=Instant.parse(expiresText);if(!issued.toString().equals(issuedText)||!expires.toString().equals(expiresText))throw denied();fresh(issued,expires);
   String encoded=text(root,"statement_base64");byte[] statementBytes=base64(encoded,4096),signature=base64(text(root,"signature"),64);if(signature.length!=64)throw denied();
   var bytes=new ByteArrayOutputStream();var framed=new DataOutputStream(bytes);for(String field:List.of("valunivo-telegram-control-result-ack-v1",keyId,issuedText,expiresText,encoded)){byte[] utf8=field.getBytes(StandardCharsets.UTF_8);framed.writeInt(utf8.length);framed.write(utf8);}
   var check=Signature.getInstance("Ed25519");check.initVerify(key);check.update(bytes.toByteArray());if(!check.verify(signature))throw denied();
   var s=parse(statementBytes);fields(s,STATEMENT);if(!text(s,"contract_version").equals("1.0")||!text(s,"app_id").equals("autostock")||!text(s,"bot_ref").equals(bot)||!text(s,"space_id").equals(space)||!text(s,"actor_ref").equals(actor))throw denied();
   String update=text(s,"source_update_id");if(!update.matches("[1-9][0-9]{0,18}")||Long.parseLong(update)==Long.MAX_VALUE)throw denied();String id=text(s,"attempt_id");UUID attempt=UUID.fromString(id);if(!attempt.toString().equals(id))throw denied();
   String request=text(s,"request_sha256"),outcome=text(s,"outcome"),result=text(s,"result_sha256"),proof=text(s,"result_proof_sha256"),recordedText=text(s,"platform_recorded_at");if(!request.matches("[a-f0-9]{64}")||!Set.of("succeeded","failed").contains(outcome)||!result.matches("[a-f0-9]{64}")||!proof.matches("[a-f0-9]{64}"))throw denied();
   Instant recorded=Instant.parse(recordedText);if(!recorded.toString().equals(recordedText)||recorded.isAfter(issued))throw denied();fresh(issued,expires);
   return new Verified(this,new Ack(update,attempt,request,outcome,result,proof,recorded),issued,expires,snapshot);
  }catch(Exception invalid){throw denied();}
 }
 public void requireVerified(Verified value){if(value==null||value.owner!=this)throw denied();fresh(value.issued,value.expires);}
 private void fresh(Instant issued,Instant expires){Instant now=clock.instant();if(!expires.isAfter(issued)||expires.isAfter(issued.plusSeconds(30))||issued.isAfter(now)||!now.isBefore(expires))throw denied();}
 private static JsonNode parse(byte[] raw)throws Exception{return JSON.readTree(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString());}
 private static void fields(JsonNode root,Set<String> expected){if(root==null||!root.isObject())throw denied();var actual=new HashSet<String>();root.fieldNames().forEachRemaining(actual::add);if(!actual.equals(expected))throw denied();}
 private static byte[] base64(String text,int maximum){byte[] bytes=Base64.getDecoder().decode(text);if(bytes.length>maximum||!Base64.getEncoder().encodeToString(bytes).equals(text))throw denied();return bytes;}
 private static String text(JsonNode root,String field){var value=root.get(field);if(value==null||!value.isTextual())throw denied();return value.textValue();}
 private static SecurityException denied(){return new SecurityException("stock-result-ack-denied");}
}
