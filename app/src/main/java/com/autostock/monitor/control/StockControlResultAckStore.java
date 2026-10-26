package com.autostock.monitor.control;
import javax.sql.DataSource;
import java.security.*;
import java.sql.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Records a platform durable-receipt ACK for an app terminal result. No resend, delivery or control execution. */
public final class StockControlResultAckStore {
 public record Recorded(String sourceUpdateId,UUID attemptId,String outcome,String resultSha256,String resultProofSha256,Instant platformRecordedAt,Instant acknowledgedAt){}
 private final DataSource source;private final StockControlResultAckVerifier verifier;
 public StockControlResultAckStore(DataSource source,StockControlResultAckVerifier verifier){if(source==null||verifier==null)throw new IllegalArgumentException("invalid-stock-result-ack-store");this.source=source;this.verifier=verifier;}
 public Recorded record(StockControlResultAckVerifier.Verified value){
  verifier.requireVerified(value);var a=value.ack();String bot=value.botRef();
  try(var c=source.getConnection()){c.setAutoCommit(false);try{
   try(var q=c.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?,0))")){q.setString(1,"autostock-control:"+bot);q.execute();}verifier.requireVerified(value);
   try(var q=c.prepareStatement("select r.attempt_id,r.request_sha256,r.outcome,r.result_sha256 from platform_stock_control_result r join platform_stock_control_attempt t on t.attempt_id=r.attempt_id where r.bot_ref=? and r.source_update_id=? and t.state=r.outcome")){q.setString(1,bot);q.setLong(2,Long.parseLong(a.sourceUpdateId()));try(var rows=q.executeQuery()){if(!rows.next()||!rows.getObject(1,UUID.class).equals(a.attemptId())||!rows.getString(2).equals(a.requestFingerprint())||!rows.getString(3).equals(a.outcome())||!rows.getString(4).equals(a.resultSha256()))throw denied();}}
   var existing=find(c,bot,a.sourceUpdateId());
   if(existing!=null){if(!existing.attemptId().equals(a.attemptId())||!existing.resultProofSha256().equals(a.resultProofSha256())||!existing.platformRecordedAt().equals(a.platformRecordedAt())||!existing.resultSha256().equals(a.resultSha256())||!keyId(c,bot,a.sourceUpdateId()).equals(value.keyId()))throw new IllegalStateException("stock-result-ack-conflict");verifier.requireVerified(value);c.commit();return existing;}
   byte[] envelope=value.envelopeBytes();
   try(var q=c.prepareStatement("insert into platform_stock_control_result_ack(bot_ref,source_update_id,attempt_id,request_sha256,outcome,result_sha256,result_proof_sha256,platform_key_id,platform_recorded_at,ack_sha256,ack_envelope) values (?,?,?,?,?,?,?,?,?,?,?)")){q.setString(1,bot);q.setLong(2,Long.parseLong(a.sourceUpdateId()));q.setObject(3,a.attemptId());q.setString(4,a.requestFingerprint());q.setString(5,a.outcome());q.setString(6,a.resultSha256());q.setString(7,a.resultProofSha256());q.setString(8,value.keyId());q.setTimestamp(9,Timestamp.from(a.platformRecordedAt()));q.setString(10,hash(envelope));q.setString(11,new String(envelope,java.nio.charset.StandardCharsets.UTF_8));q.executeUpdate();}
   var saved=Objects.requireNonNull(find(c,bot,a.sourceUpdateId()));verifier.requireVerified(value);c.commit();return saved;
  }catch(Exception e){c.rollback();if(e instanceof RuntimeException runtime)throw runtime;throw new IllegalStateException("stock-result-ack-unavailable",e);}}catch(SQLException e){throw new IllegalStateException("stock-result-ack-unavailable",e);}
 }
 private Recorded find(Connection c,String bot,String update)throws SQLException{try(var q=c.prepareStatement("select attempt_id,outcome,result_sha256,result_proof_sha256,platform_recorded_at,acknowledged_at from platform_stock_control_result_ack where bot_ref=? and source_update_id=?")){q.setString(1,bot);q.setLong(2,Long.parseLong(update));try(var rows=q.executeQuery()){if(!rows.next())return null;return new Recorded(update,rows.getObject(1,UUID.class),rows.getString(2),rows.getString(3),rows.getString(4),rows.getTimestamp(5).toInstant(),rows.getTimestamp(6).toInstant());}}}
 private String keyId(Connection c,String bot,String update)throws SQLException{try(var q=c.prepareStatement("select platform_key_id from platform_stock_control_result_ack where bot_ref=? and source_update_id=?")){q.setString(1,bot);q.setLong(2,Long.parseLong(update));try(var rows=q.executeQuery()){rows.next();return rows.getString(1);}}}
 private static String hash(byte[] raw){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
 private static SecurityException denied(){return new SecurityException("stock-result-ack-denied");}
}
