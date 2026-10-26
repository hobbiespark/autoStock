package com.autostock.monitor.control;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/**
 * Local operator legacy polling resume request (P2-2d, user decision 2026-10-27 alternative A). Pure authentication and
 * strict parsing only. Its own HMAC domain, key and path: a signature or key of the platform-facing operator listener
 * (stop/observe, 158) can never authorise a resume. Release evidence fields are nullable so a request without evidence is
 * still authenticated, recorded and rejected by {@code LegacyResumeOperator}, never silently dropped.
 */
public final class LegacyResumeGate {
 public static final String DOMAIN="autostock-local-legacy-resume-request-v1",PATH="/legacy-receiver/resume",KEY_ID_HEADER="X-Operator-Key-Id",SIGNATURE_HEADER="X-Operator-Signature";
 /** Evidence as copied by the operator from the platform release answer (161 §3); nothing here is trusted until compared. */
 public record Evidence(UUID stopId,UUID platformRunId,UUID platformStopRequestId,String platformRunState,Boolean platformLeaseReturned,Long platformNextOffset,Long platformFence){}
 public record Request(String contractVersion,UUID requestId,String instanceId,Evidence evidence,String operatorConfirmation,Instant issuedAt,Instant expiresAt){
  public Request{
   if(!"1.0".equals(contractVersion)||requestId==null||instanceId==null||!instanceId.matches("[A-Za-z0-9_-]{1,80}")||evidence==null||issuedAt==null||expiresAt==null||!expiresAt.isAfter(issuedAt)||expiresAt.isAfter(issuedAt.plusSeconds(30))
     ||(operatorConfirmation!=null&&!operatorConfirmation.matches("[A-Za-z0-9:_-]{1,120}"))||(evidence.platformRunState()!=null&&!evidence.platformRunState().matches("[a-z_]{1,40}")))throw new IllegalArgumentException("invalid-legacy-resume-request");
  }
  @Override public String toString(){return "LegacyResumeRequest[private context]";}
 }
 public static final class Verified {
  private final LegacyResumeGate owner;private final Request request;private final String fingerprint;
  private Verified(LegacyResumeGate owner,Request request,String fingerprint){this.owner=owner;this.request=request;this.fingerprint=fingerprint;}
  public Request request(){return request;}public String fingerprint(){return fingerprint;}public String keyId(){return owner.keyId;}
  @Override public String toString(){return "VerifiedLegacyResume[private context]";}
 }
 private static final Set<String> FIELDS=Set.of("contract_version","request_id","instance_id","stop_id","platform_run_id","platform_stop_request_id","platform_run_state","platform_lease_returned","platform_next_offset","platform_fence","operator_confirmation","issued_at","expires_at");
 private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(2).maxStringLength(256).maxNumberLength(20).build()).enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
 private final String keyId,instance;private final byte[] key;private final Clock clock;
 public LegacyResumeGate(String keyId,byte[] key,String instance,Clock clock){
  if(keyId==null||!keyId.matches("[A-Za-z0-9_-]{1,80}")||key==null||key.length!=32||instance==null||!instance.matches("[A-Za-z0-9_-]{1,80}")||clock==null)throw new IllegalArgumentException("invalid-legacy-resume-trust");
  this.keyId=keyId;this.key=key.clone();this.instance=instance;this.clock=clock;
 }
 public Verified verify(String method,String path,String presentedKeyId,String signature,byte[] body){
  try{
   if(!"POST".equals(method)||!PATH.equals(path)||!keyId.equals(presentedKeyId)||signature==null||!signature.matches("[a-f0-9]{64}")||body==null||body.length==0||body.length>4096)throw denied();
   byte[] snapshot=body.clone(),framed=frame(keyId,snapshot);Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));if(!MessageDigest.isEqual(mac.doFinal(framed),HexFormat.of().parseHex(signature)))throw denied();
   String utf8=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(snapshot)).toString();JsonNode root=JSON.readTree(utf8);if(root==null||!root.isObject())throw denied();var fields=new HashSet<String>();root.fieldNames().forEachRemaining(fields::add);if(!fields.equals(FIELDS))throw denied();
   if(!instance.equals(text(root,"instance_id")))throw denied();
   String issued=text(root,"issued_at"),expires=text(root,"expires_at");Instant issuedAt=Instant.parse(issued),expiresAt=Instant.parse(expires);if(!issued.equals(issuedAt.toString())||!expires.equals(expiresAt.toString()))throw denied();
   JsonNode returned=root.get("platform_lease_returned");if(!returned.isNull()&&!returned.isBoolean())throw denied();
   var evidence=new Evidence(nullableUuid(root.get("stop_id")),nullableUuid(root.get("platform_run_id")),nullableUuid(root.get("platform_stop_request_id")),nullableText(root.get("platform_run_state")),returned.isNull()?null:returned.booleanValue(),nullableDecimal(root.get("platform_next_offset")),nullableDecimal(root.get("platform_fence")));
   var request=new Request(text(root,"contract_version"),uuid(text(root,"request_id")),text(root,"instance_id"),evidence,nullableText(root.get("operator_confirmation")),issuedAt,expiresAt);fresh(request);
   return new Verified(this,request,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(framed)));
  }catch(Exception invalid){throw denied();}
 }
 public void requireVerified(Verified value){if(value==null||value.owner!=this)throw denied();fresh(value.request);}
 private void fresh(Request request){Instant now=clock.instant();if(request.issuedAt().isAfter(now)||!now.isBefore(request.expiresAt()))throw denied();}

 /** The explicit confirmation text the operator must type for exactly this stop. */
 public static String confirmation(UUID stopId){return "resume-legacy-polling:"+stopId;}
 /** Client side: canonical body. Evidence values are emitted as given; the operator path decides. */
 public static byte[] body(UUID requestId,String instanceId,Evidence evidence,String confirmation,Instant issuedAt,Instant expiresAt){
  var request=new Request("1.0",requestId,instanceId,evidence,confirmation,issuedAt,expiresAt);
  var root=JSON.createObjectNode();root.put("contract_version","1.0");root.put("request_id",requestId.toString());root.put("instance_id",instanceId);
  put(root,"stop_id",evidence.stopId());put(root,"platform_run_id",evidence.platformRunId());put(root,"platform_stop_request_id",evidence.platformStopRequestId());put(root,"platform_run_state",evidence.platformRunState());
  if(evidence.platformLeaseReturned()==null)root.putNull("platform_lease_returned");else root.put("platform_lease_returned",evidence.platformLeaseReturned().booleanValue());
  put(root,"platform_next_offset",evidence.platformNextOffset());put(root,"platform_fence",evidence.platformFence());put(root,"operator_confirmation",request.operatorConfirmation());
  root.put("issued_at",issuedAt.toString());root.put("expires_at",expiresAt.toString());
  try{return JSON.writeValueAsBytes(root);}catch(IOException impossible){throw new IllegalStateException(impossible);}
 }
 public static String signature(String keyId,byte[] key,byte[] body){
  try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(frame(keyId,body)));}
  catch(IOException|java.security.GeneralSecurityException failure){throw new IllegalStateException("legacy-resume-signature-unavailable");}
 }
 private static byte[] frame(String keyId,byte[] body)throws IOException{
  var bytes=new ByteArrayOutputStream();var data=new DataOutputStream(bytes);
  for(String value:List.of(DOMAIN,keyId,"POST",PATH)){byte[] utf8=value.getBytes(StandardCharsets.UTF_8);data.writeInt(utf8.length);data.write(utf8);}data.writeInt(body.length);data.write(body);return bytes.toByteArray();
 }
 private static void put(com.fasterxml.jackson.databind.node.ObjectNode root,String name,Object value){if(value==null)root.putNull(name);else root.put(name,value.toString());}
 private static String text(JsonNode root,String name){var value=root.get(name);if(value==null||!value.isTextual())throw denied();return value.textValue();}
 private static String nullableText(JsonNode value){if(value==null)throw denied();if(value.isNull())return null;if(!value.isTextual())throw denied();return value.textValue();}
 private static Long nullableDecimal(JsonNode value){String text=nullableText(value);if(text==null)return null;if(!text.matches("0|[1-9][0-9]{0,18}"))throw denied();long number=Long.parseLong(text);if(number==Long.MAX_VALUE)throw denied();return number;}
 private static UUID nullableUuid(JsonNode value){String text=nullableText(value);return text==null?null:uuid(text);}
 private static UUID uuid(String value){UUID id=UUID.fromString(value);if(!id.toString().equals(value))throw denied();return id;}
 private static SecurityException denied(){return new SecurityException("legacy-resume-denied");}
}
