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

/** Pure operator request authentication. No listener, replay ledger, key loader or poller calls. */
public final class HandoverOperatorGate {
    public enum Action { stop, observe }
    public record Request(String contractVersion,UUID requestId,Action action,String instanceId,UUID expectedActiveStopId,UUID targetRequestId,Instant issuedAt,Instant expiresAt) {
        public Request {
            if(!"1.0".equals(contractVersion)||requestId==null||action==null||instanceId==null||!instanceId.matches("[A-Za-z0-9_-]{1,80}")||expectedActiveStopId!=null||issuedAt==null||expiresAt==null||!expiresAt.isAfter(issuedAt)||expiresAt.isAfter(issuedAt.plusSeconds(30))||(action==Action.stop?targetRequestId!=null:targetRequestId==null))throw new IllegalArgumentException("invalid-operator-handover-request");
        }
        @Override public String toString(){return "OperatorHandoverRequest[action="+action+"]";}
    }
    public static final class Verified {
        private final HandoverOperatorGate owner;private final Request request;private final String fingerprint;
        private Verified(HandoverOperatorGate owner,Request request,String fingerprint){this.owner=owner;this.request=request;this.fingerprint=fingerprint;}
        public Request request(){return request;}public String fingerprint(){return fingerprint;}
        @Override public String toString(){return "VerifiedOperatorHandover[action="+request.action()+"]";}
    }
    private static final Set<String> FIELDS=Set.of("contract_version","request_id","action","instance_id","expected_active_stop_id","target_request_id","issued_at","expires_at");
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(2).maxStringLength(256).maxNumberLength(20).build()).enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final String keyId,instance;private final byte[] key;private final Clock clock;
    public HandoverOperatorGate(String keyId,byte[] key,String instance,Clock clock){
        if(keyId==null||!keyId.matches("[A-Za-z0-9_-]{1,80}")||key==null||key.length!=32||instance==null||!instance.matches("[A-Za-z0-9_-]{1,80}")||clock==null)throw new IllegalArgumentException("invalid-operator-handover-trust");
        this.keyId=keyId;this.key=key.clone();this.instance=instance;this.clock=clock;
    }
    public Verified verify(String method,String path,String presentedKeyId,String signature,byte[] body){
        try {
            if(!"POST".equals(method)||!Set.of("/receiver-handover/stop","/receiver-handover/observe").contains(path)||!keyId.equals(presentedKeyId)||signature==null||!signature.matches("[a-f0-9]{64}")||body==null||body.length==0||body.length>4096)throw denied();
            byte[] snapshot=body.clone(),framed=frame(path,snapshot);Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));if(!MessageDigest.isEqual(mac.doFinal(framed),HexFormat.of().parseHex(signature)))throw denied();
            String utf8=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(snapshot)).toString();JsonNode root=JSON.readTree(utf8);if(root==null||!root.isObject())throw denied();var fields=new HashSet<String>();root.fieldNames().forEachRemaining(fields::add);if(!fields.equals(FIELDS))throw denied();
            Action action=Action.valueOf(text(root,"action"));if(!path.equals("/receiver-handover/"+action.name())||!instance.equals(text(root,"instance_id")))throw denied();
            String issued=text(root,"issued_at"),expires=text(root,"expires_at");Instant issuedAt=Instant.parse(issued),expiresAt=Instant.parse(expires);if(!issued.equals(issuedAt.toString())||!expires.equals(expiresAt.toString()))throw denied();
            var request=new Request(text(root,"contract_version"),uuid(text(root,"request_id")),action,text(root,"instance_id"),nullableUuid(root.get("expected_active_stop_id")),nullableUuid(root.get("target_request_id")),issuedAt,expiresAt);fresh(request);
            return new Verified(this,request,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(framed)));
        }catch(Exception invalid){throw denied();}
    }
    /** Rechecks this gate's identity and request time only. Durable replay/OS identity checks remain separate. */
    public void requireVerified(Verified value){if(value==null||value.owner!=this)throw denied();fresh(value.request);}
    private void fresh(Request request){Instant now=clock.instant();if(request.issuedAt().isAfter(now)||!now.isBefore(request.expiresAt()))throw denied();}
    private byte[] frame(String path,byte[] body)throws IOException{
        var bytes=new ByteArrayOutputStream();var data=new DataOutputStream(bytes);
        for(String value:List.of("autostock-operator-handover-request-v1",keyId,"POST",path)){byte[] utf8=value.getBytes(StandardCharsets.UTF_8);data.writeInt(utf8.length);data.write(utf8);}data.writeInt(body.length);data.write(body);return bytes.toByteArray();
    }
    private static String text(JsonNode root,String name){var value=root.get(name);if(value==null||!value.isTextual())throw denied();return value.textValue();}
    private static UUID nullableUuid(JsonNode value){if(value==null)throw denied();if(value.isNull())return null;if(!value.isTextual())throw denied();return uuid(value.textValue());}
    private static UUID uuid(String value){UUID id=UUID.fromString(value);if(!id.toString().equals(value))throw denied();return id;}
    private static SecurityException denied(){return new SecurityException("operator-handover-denied");}
}
