package com.autostock.monitor.control;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.EdECPrivateKey;
import java.time.*;
import java.util.*;

/** Signs app-recorded terminal results only. No key loader, endpoint or business executor. */
public final class StockControlResultIssuer {
 private final StockControlAttemptStore store;private final StockControlVerifier verifier;private final String kid;private final PrivateKey key;private final Clock clock;
 public StockControlResultIssuer(StockControlAttemptStore store,StockControlVerifier verifier,String kid,PrivateKey key,Clock clock){
  if(store==null||verifier==null||kid==null||!kid.matches("[A-Za-z0-9_-]{1,80}")||!(key instanceof EdECPrivateKey ed)||!ed.getParams().getName().equals("Ed25519")||clock==null)throw new IllegalArgumentException("invalid-stock-result-issuer");this.store=store;this.verifier=verifier;this.kid=kid;this.key=key;this.clock=clock;
 }
 public byte[] issue(StockControlVerifier.Verified value){
  verifier.requireVerified(value);var stored=store.result(value);if(stored==null)throw denied();var request=value.request();Instant issued=clock.instant(),expires=issued.plusSeconds(30);if(value.expiresAt().isBefore(expires))expires=value.expiresAt();if(!expires.isAfter(issued))throw denied();
  try{
   var json=new ObjectMapper();var statement=json.createObjectNode();statement.put("contract_version","1.0");statement.put("app_id","autostock");statement.put("bot_ref",request.botRef());statement.put("space_id",request.spaceId());statement.put("actor_ref",request.actorRef());statement.put("source_update_id",request.sourceUpdateId());statement.put("attempt_id",stored.attemptId().toString());statement.put("request_sha256",stored.requestFingerprint());statement.put("outcome",stored.result().outcome().name());statement.put("result_sha256",stored.resultSha256());statement.put("content_base64",Base64.getEncoder().encodeToString(StockControlResultCipher.encode(stored.result())));
   byte[] raw=json.writeValueAsBytes(statement);if(raw.length>24576)throw denied();String encoded=Base64.getEncoder().encodeToString(raw);var bytes=new ByteArrayOutputStream();var framed=new DataOutputStream(bytes);
   for(String field:List.of("autostock-telegram-control-result-proof-v1",kid,issued.toString(),expires.toString(),encoded)){byte[] utf8=field.getBytes(StandardCharsets.UTF_8);framed.writeInt(utf8.length);framed.write(utf8);}
   var signature=Signature.getInstance("Ed25519");signature.initSign(key);signature.update(bytes.toByteArray());byte[] result=json.writeValueAsBytes(Map.of("contract_version","1.0","key_id",kid,"issued_at",issued.toString(),"expires_at",expires.toString(),"statement_base64",encoded,"signature",Base64.getEncoder().encodeToString(signature.sign())));
   if(result.length>32768)throw denied();verifier.requireVerified(value);if(!clock.instant().isBefore(expires))throw denied();return result;
  }catch(GeneralSecurityException|IOException invalid){throw denied();}
 }
 private static SecurityException denied(){return new SecurityException("stock-control-result-issue-denied");}
}
