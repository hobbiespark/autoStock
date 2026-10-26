package com.autostock.monitor.control;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Client side of the operator listener contract: canonical body and HMAC-SHA256 over the gate's framing.
 * Signing authorises nothing by itself; the listener gate, ledger and coordinator decide. */
public final class HandoverOperatorSigner {
 public static final String DOMAIN="autostock-operator-handover-request-v1",KEY_ID_HEADER="X-Operator-Key-Id",SIGNATURE_HEADER="X-Operator-Signature";
 private HandoverOperatorSigner(){}
 public static String path(HandoverOperatorGate.Action action){return "/receiver-handover/"+action.name();}
 /** Canonical body; instance IDs are restricted to [A-Za-z0-9_-] so no JSON escaping is needed. */
 public static byte[] body(UUID requestId,HandoverOperatorGate.Action action,String instanceId,UUID targetRequestId,Instant issuedAt,Instant expiresAt){
  if(requestId==null||action==null||instanceId==null||!instanceId.matches("[A-Za-z0-9_-]{1,80}")||issuedAt==null||expiresAt==null||(action==HandoverOperatorGate.Action.stop?targetRequestId!=null:targetRequestId==null))throw new IllegalArgumentException("invalid-operator-handover-request");
  String target=targetRequestId==null?"null":"\""+targetRequestId+"\"";
  return ("{\"contract_version\":\"1.0\",\"request_id\":\""+requestId+"\",\"action\":\""+action.name()+"\",\"instance_id\":\""+instanceId+"\",\"expected_active_stop_id\":null,\"target_request_id\":"+target+",\"issued_at\":\""+issuedAt+"\",\"expires_at\":\""+expiresAt+"\"}").getBytes(StandardCharsets.UTF_8);
 }
 public static String signature(String keyId,byte[] key,String path,byte[] body){
  try {
   var bytes=new ByteArrayOutputStream();var data=new DataOutputStream(bytes);
   for(String value:List.of(DOMAIN,keyId,"POST",path)){byte[] utf8=value.getBytes(StandardCharsets.UTF_8);data.writeInt(utf8.length);data.write(utf8);}data.writeInt(body.length);data.write(body);
   var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(bytes.toByteArray()));
  }catch(IOException|java.security.GeneralSecurityException failure){throw new IllegalStateException("operator-signature-unavailable");}
 }
}
