package com.autostock.monitor.control;
import javax.sql.DataSource;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Explicit durable receipt only. No execution, control endpoint or automatic worker. */
public final class StockControlReceiptStore {
 public record Receipt(String botRef,String sourceUpdateId,String receiverFence,String requestFingerprint,String state,Instant receivedAt,boolean duplicate){
  @Override public String toString(){return "StockControlReceipt[state="+state+",duplicate="+duplicate+"]";}
 }
 private final DataSource source;private final StockControlVerifier verifier;private final byte[] key;private final String keyId;
 public StockControlReceiptStore(DataSource source,StockControlVerifier verifier,String keyId,byte[] key){
  if(source==null||verifier==null||keyId==null||!keyId.matches("[A-Za-z0-9_-]{1,80}")||key==null||key.length!=32)throw new IllegalArgumentException("invalid-stock-control-store");
  this.source=source;this.verifier=verifier;this.keyId=keyId;this.key=key.clone();
 }
 public Receipt receive(StockControlVerifier.Verified value){
  verifier.requireVerified(value);var request=value.request();String intent=intent(request);long update=Long.parseLong(request.sourceUpdateId()),fence=Long.parseLong(request.receiverFence());
  try(var c=source.getConnection()){
   c.setAutoCommit(false);
   try{
    try(var q=c.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?,0))")){q.setString(1,"autostock-control:"+request.botRef());q.execute();}
    verifier.requireVerified(value);
    try(var q=c.prepareStatement("select intent_sha256,receiver_fence,request_sha256,state,received_at from platform_stock_control_receipt where bot_ref=? and source_update_id=?")){
     q.setString(1,request.botRef());q.setLong(2,update);try(var rows=q.executeQuery()){
      if(rows.next()){
       if(!intent.equals(rows.getString(1)))throw new SecurityException("stock-control-update-conflict");
       var result=new Receipt(request.botRef(),request.sourceUpdateId(),Long.toString(rows.getLong(2)),rows.getString(3),rows.getString(4),rows.getTimestamp(5).toInstant(),true);
       verifier.requireVerified(value);c.commit();return result;
      }
     }
    }
    try(var q=c.prepareStatement("select highest_fence from platform_stock_control_receiver where bot_ref=?")){q.setString(1,request.botRef());try(var rows=q.executeQuery()){if(rows.next()&&rows.getLong(1)>fence)throw new SecurityException("stale-stock-control-fence");}}
    try(var q=c.prepareStatement("insert into platform_stock_control_receiver(bot_ref,highest_fence) values (?,?) on conflict(bot_ref) do update set highest_fence=greatest(platform_stock_control_receiver.highest_fence,excluded.highest_fence)")){q.setString(1,request.botRef());q.setLong(2,fence);q.executeUpdate();}
    byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(("autostock-control-receipt-v1:"+request.botRef()+":"+request.sourceUpdateId()+":"+keyId).getBytes(StandardCharsets.UTF_8));byte[] sealed=cipher.doFinal(value.proofBytes());byte[] packed=new byte[nonce.length+sealed.length];System.arraycopy(nonce,0,packed,0,nonce.length);System.arraycopy(sealed,0,packed,nonce.length,sealed.length);
    Instant received;
    try(var q=c.prepareStatement("insert into platform_stock_control_receipt(bot_ref,source_update_id,receiver_fence,intent_sha256,request_sha256,key_id,proof_enc) values (?,?,?,?,?,?,?) returning received_at")){
     q.setString(1,request.botRef());q.setLong(2,update);q.setLong(3,fence);q.setString(4,intent);q.setString(5,request.fingerprint());q.setString(6,keyId);q.setString(7,Base64.getEncoder().encodeToString(packed));try(var rows=q.executeQuery()){rows.next();received=rows.getTimestamp(1).toInstant();}
    }
    verifier.requireVerified(value);c.commit();return new Receipt(request.botRef(),request.sourceUpdateId(),request.receiverFence(),request.fingerprint(),"received",received,false);
   }catch(Exception failure){c.rollback();if(failure instanceof RuntimeException runtime)throw runtime;throw new IllegalStateException("stock-control-receipt-unavailable",failure);}
  }catch(SQLException failure){throw new IllegalStateException("stock-control-receipt-unavailable",failure);}
 }
 private static String intent(StockControlRequest r){
  var normalized=new StockControlRequest(r.contractVersion(),r.appId(),r.botRef(),r.spaceId(),r.actorRef(),r.sourceUpdateId(),"1",r.command(),r.confirmationCode());
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("autostock-control-intent-v1:"+normalized.fingerprint()).getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
 }
}
