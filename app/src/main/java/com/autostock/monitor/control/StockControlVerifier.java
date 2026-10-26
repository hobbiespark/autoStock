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

/** Explicit pinned verifier only; no bean, key loader, endpoint or control execution. */
public final class StockControlVerifier {
 private static final Set<String> FIELDS=Set.of("contract_version","key_id","issued_at","expires_at","request_base64","signature");
 private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(2).maxStringLength(5500).maxNumberLength(20).build()).enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
 private final String keyId,bot,space,actor;private final PublicKey key;private final Clock clock;
 public StockControlVerifier(String keyId,PublicKey key,String bot,String space,String actor,Clock clock){
  if(keyId==null||!keyId.matches("[A-Za-z0-9_-]{1,80}")||!(key instanceof EdECPublicKey ed)||!ed.getParams().getName().equals("Ed25519")||bot==null||!bot.matches("[a-f0-9]{64}")||space==null||!space.matches("[A-Za-z0-9_-]{1,80}")||actor==null||!actor.matches("[a-f0-9]{64}")||clock==null)throw new IllegalArgumentException("invalid-stock-control-trust");
  this.keyId=keyId;this.key=key;this.bot=bot;this.space=space;this.actor=actor;this.clock=clock;
 }
 public static final class Verified {
  private final StockControlVerifier owner;private final StockControlRequest request;private final Instant issued,expires;private final byte[] proof;
  private Verified(StockControlVerifier owner,StockControlRequest request,Instant issued,Instant expires,byte[] proof){this.owner=owner;this.request=request;this.issued=issued;this.expires=expires;this.proof=proof.clone();}
  public StockControlRequest request(){return request;}public Instant expiresAt(){return expires;}public byte[] proofBytes(){return proof.clone();}
  @Override public String toString(){return "VerifiedStockControl[private context]";}
 }
 public Verified verify(byte[] envelope){
  try{
   if(envelope==null||envelope.length==0||envelope.length>8192)throw denied();byte[] snapshot=envelope.clone();
   var root=JSON.readTree(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(snapshot)).toString());if(root==null||!root.isObject())throw denied();var fields=new HashSet<String>();root.fieldNames().forEachRemaining(fields::add);if(!fields.equals(FIELDS)||!text(root,"contract_version").equals("1.0")||!text(root,"key_id").equals(keyId))throw denied();
   String issuedText=text(root,"issued_at"),expiresText=text(root,"expires_at");Instant issued=Instant.parse(issuedText),expires=Instant.parse(expiresText);if(!issued.toString().equals(issuedText)||!expires.toString().equals(expiresText))throw denied();fresh(issued,expires);
   byte[] requestBytes=base64(text(root,"request_base64"),4096),signature=base64(text(root,"signature"),64);if(signature.length!=64)throw denied();
   var verifier=Signature.getInstance("Ed25519");verifier.initVerify(key);verifier.update(signingBytes(keyId,issuedText,expiresText,requestBytes));if(!verifier.verify(signature))throw denied();
   var request=StockControlWire.decode(requestBytes);if(!request.botRef().equals(bot)||!request.spaceId().equals(space)||!request.actorRef().equals(actor))throw denied();fresh(issued,expires);
   return new Verified(this,request,issued,expires,snapshot);
  }catch(Exception invalid){throw denied();}
 }
 /** Downstream trusted composition must use its own verifier identity and recheck time. Not live receiver authority. */
 public void requireVerified(Verified value){if(value==null||value.owner!=this)throw denied();fresh(value.issued,value.expires);}
 private void fresh(Instant issued,Instant expires){Instant now=clock.instant();if(!expires.isAfter(issued)||expires.isAfter(issued.plusSeconds(30))||issued.isAfter(now)||!now.isBefore(expires))throw denied();}
 private static byte[] base64(String text,int maximum){byte[] bytes=Base64.getDecoder().decode(text);if(bytes.length>maximum||!Base64.getEncoder().encodeToString(bytes).equals(text))throw denied();return bytes;}
 private static String text(JsonNode root,String field){var value=root.get(field);if(value==null||!value.isTextual())throw denied();return value.textValue();}
 private static byte[] signingBytes(String kid,String issued,String expires,byte[] request)throws IOException{
  var bytes=new ByteArrayOutputStream();var data=new DataOutputStream(bytes);
  for(String value:List.of("valunivo-telegram-stock-control-proof-v1",kid,issued,expires,Base64.getEncoder().encodeToString(request))){byte[] utf8=value.getBytes(StandardCharsets.UTF_8);data.writeInt(utf8.length);data.write(utf8);}return bytes.toByteArray();
 }
 private static SecurityException denied(){return new SecurityException("stock-control-proof-denied");}
}
