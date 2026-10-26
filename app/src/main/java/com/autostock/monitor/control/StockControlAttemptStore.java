package com.autostock.monitor.control;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One attempt only, no command executor and no retry or token recovery. */
public final class StockControlAttemptStore {
 public static final class Claim {
  private final StockControlAttemptStore owner;private final StockControlRequest request;private final UUID id;private final String token;private final Instant deadline;
  private Claim(StockControlAttemptStore owner,StockControlRequest request,UUID id,String token,Instant deadline){this.owner=owner;this.request=request;this.id=id;this.token=token;this.deadline=deadline;}
  public Instant executeBefore(){return deadline;}
  @Override public String toString(){return "StockControlClaim[private context]";}
 }
 public record ResultSnapshot(UUID attemptId,String requestFingerprint,StockControlResultCipher.Result result,String resultSha256,Instant recordedAt){
  @Override public String toString(){return "StockControlResultSnapshot[outcome="+result.outcome()+"]";}
 }
 public record Observation(String state,UUID attemptId,String receiverFence,Instant executeBefore){}
 private final DataSource source;private final StockControlVerifier verifier;private final Clock clock;private final Duration budget;private final StockControlResultCipher resultCipher;
 public StockControlAttemptStore(DataSource source,StockControlVerifier verifier,Clock clock,Duration budget){this(source,verifier,clock,budget,null);}
 public StockControlAttemptStore(DataSource source,StockControlVerifier verifier,Clock clock,Duration budget,StockControlResultCipher resultCipher){
  if(source==null||verifier==null||clock==null||budget==null||budget.compareTo(Duration.ofSeconds(1))<0||budget.compareTo(Duration.ofSeconds(30))>0)throw new IllegalArgumentException("invalid-stock-control-attempt-store");
  this.source=source;this.verifier=verifier;this.clock=clock;this.budget=budget;this.resultCipher=resultCipher;
 }
 @FunctionalInterface private interface Work<T>{T apply(Connection c)throws Exception;}
 private <T>T transaction(StockControlRequest r,Work<T> work){
  try(var c=source.getConnection()){c.setAutoCommit(false);try{try(var q=c.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?,0))")){q.setString(1,"autostock-control:"+r.botRef());q.execute();}T result=work.apply(c);c.commit();return result;}catch(Exception e){c.rollback();if(e instanceof RuntimeException runtime)throw runtime;throw new IllegalStateException("stock-control-attempt-unavailable",e);}}catch(SQLException e){throw new IllegalStateException("stock-control-attempt-unavailable",e);}
 }
 private void receipt(Connection c,StockControlRequest r,boolean current)throws SQLException{
  try(var q=c.prepareStatement("select p.intent_sha256,w.highest_fence from platform_stock_control_receipt p join platform_stock_control_receiver w on p.bot_ref=w.bot_ref where p.bot_ref=? and p.source_update_id=?")){q.setString(1,r.botRef());q.setLong(2,Long.parseLong(r.sourceUpdateId()));try(var rows=q.executeQuery()){if(!rows.next()||!StockControlReceiptStore.intent(r).equals(rows.getString(1))||(current&&rows.getLong(2)!=Long.parseLong(r.receiverFence())))throw denied();}}
 }
 public Claim begin(StockControlVerifier.Verified value){
  verifier.requireVerified(value);var r=value.request();return transaction(r,c->{verifier.requireVerified(value);receipt(c,r,true);Instant deadline=clock.instant().plus(budget);if(value.expiresAt().isBefore(deadline))deadline=value.expiresAt();deadline=deadline.truncatedTo(java.time.temporal.ChronoUnit.MICROS);if(!deadline.isAfter(clock.instant()))throw denied();UUID id=UUID.randomUUID();String token=UUID.randomUUID().toString();
   try(var q=c.prepareStatement("insert into platform_stock_control_attempt(bot_ref,source_update_id,attempt_id,receiver_fence,token_sha256,request_sha256,state,execute_before) values (?,?,?,?,?,?,'running',?) on conflict(bot_ref,source_update_id) do nothing")){q.setString(1,r.botRef());q.setLong(2,Long.parseLong(r.sourceUpdateId()));q.setObject(3,id);q.setLong(4,Long.parseLong(r.receiverFence()));q.setString(5,hash(token));q.setString(6,r.fingerprint());q.setTimestamp(7,Timestamp.from(deadline));if(q.executeUpdate()!=1)throw new IllegalStateException("stock-control-already-attempted");}
   verifier.requireVerified(value);if(!clock.instant().isBefore(deadline))throw denied();return new Claim(this,r,id,token,deadline);
  });
 }
 public void requireCurrent(Claim claim,StockControlVerifier.Verified value){
  requireOwned(claim);verifier.requireVerified(value);if(!claim.request.fingerprint().equals(value.request().fingerprint()))throw denied();
  transaction(claim.request,c->{current(c,claim,value);return null;});
 }
 private void current(Connection c,Claim claim,StockControlVerifier.Verified value)throws SQLException{
  verifier.requireVerified(value);receipt(c,claim.request,true);var state=find(c,claim.request);if(!state.state().equals("running")||!state.attemptId().equals(claim.id)||!clock.instant().isBefore(state.executeBefore()))throw denied();
  try(var q=c.prepareStatement("select 1 from platform_stock_control_attempt where attempt_id=? and token_sha256=?")){q.setObject(1,claim.id);q.setString(2,hash(claim.token));try(var rows=q.executeQuery()){if(!rows.next())throw denied();}}
  verifier.requireVerified(value);if(!clock.instant().isBefore(state.executeBefore()))throw denied();
 }
 public ResultSnapshot complete(Claim claim,StockControlVerifier.Verified value,StockControlResultCipher.Result result){
  requireOwned(claim);verifier.requireVerified(value);if(resultCipher==null)throw new IllegalStateException("stock-control-result-key-required");if(!claim.request.fingerprint().equals(value.request().fingerprint()))throw denied();String expected=StockControlResultCipher.digest(StockControlResultCipher.encode(result));
  return transaction(claim.request,c->{
   verifier.requireVerified(value);receipt(c,claim.request,false);var existing=readResult(c,claim.request);
   if(existing!=null){if(!existing.attemptId().equals(claim.id)||!existing.resultSha256().equals(expected))throw new IllegalStateException("stock-control-result-conflict");verifier.requireVerified(value);return existing;}
   current(c,claim,value);String context=claim.request.botRef()+":"+claim.request.sourceUpdateId()+":"+claim.id+":"+claim.request.fingerprint();var sealed=resultCipher.seal(result,context);
   try(var q=c.prepareStatement("insert into platform_stock_control_result(bot_ref,source_update_id,attempt_id,request_sha256,outcome,result_sha256,key_id,result_enc) values (?,?,?,?,?,?,?,?)")){q.setString(1,claim.request.botRef());q.setLong(2,Long.parseLong(claim.request.sourceUpdateId()));q.setObject(3,claim.id);q.setString(4,claim.request.fingerprint());q.setString(5,result.outcome().name());q.setString(6,sealed.sha256());q.setString(7,sealed.keyId());q.setString(8,sealed.encrypted());q.executeUpdate();}
   current(c,claim,value);
   try(var q=c.prepareStatement("update platform_stock_control_attempt set state=? where attempt_id=? and token_sha256=? and state='running'")){q.setString(1,result.outcome().name());q.setObject(2,claim.id);q.setString(3,hash(claim.token));if(q.executeUpdate()!=1)throw denied();}
   verifier.requireVerified(value);receipt(c,claim.request,true);if(!clock.instant().isBefore(claim.deadline))throw denied();return readResult(c,claim.request);
  });
 }
 public ResultSnapshot result(StockControlVerifier.Verified value){
  verifier.requireVerified(value);if(resultCipher==null)throw new IllegalStateException("stock-control-result-key-required");var r=value.request();return transaction(r,c->{verifier.requireVerified(value);receipt(c,r,false);var result=readResult(c,r);verifier.requireVerified(value);return result;});
 }
 private ResultSnapshot readResult(Connection c,StockControlRequest r)throws SQLException{
  try(var q=c.prepareStatement("select r.attempt_id,r.request_sha256,r.outcome,r.result_sha256,r.key_id,r.result_enc,r.recorded_at,a.state from platform_stock_control_result r join platform_stock_control_attempt a on r.attempt_id=a.attempt_id where r.bot_ref=? and r.source_update_id=?")){q.setString(1,r.botRef());q.setLong(2,Long.parseLong(r.sourceUpdateId()));try(var rows=q.executeQuery()){if(!rows.next())return null;UUID id=rows.getObject(1,UUID.class);String fingerprint=rows.getString(2),sha=rows.getString(4);var result=resultCipher.open(rows.getString(5),rows.getString(6),sha,r.botRef()+":"+r.sourceUpdateId()+":"+id+":"+fingerprint);if(!result.outcome().name().equals(rows.getString(3))||!rows.getString(3).equals(rows.getString(8)))throw denied();return new ResultSnapshot(id,fingerprint,result,sha,rows.getTimestamp(7).toInstant());}}
 }
 public void markUnknown(Claim claim){
  requireOwned(claim);transaction(claim.request,c->{try(var q=c.prepareStatement("update platform_stock_control_attempt set state='unknown' where attempt_id=? and token_sha256=? and state='running'")){q.setObject(1,claim.id);q.setString(2,hash(claim.token));q.executeUpdate();}return null;});
 }
 public Observation recover(StockControlVerifier.Verified value){
  verifier.requireVerified(value);var r=value.request();return transaction(r,c->{verifier.requireVerified(value);receipt(c,r,false);try(var q=c.prepareStatement("update platform_stock_control_attempt set state='unknown' where bot_ref=? and source_update_id=? and state='running' and execute_before<=?")){q.setString(1,r.botRef());q.setLong(2,Long.parseLong(r.sourceUpdateId()));q.setTimestamp(3,Timestamp.from(clock.instant()));q.executeUpdate();}var result=find(c,r);verifier.requireVerified(value);return result;});
 }
 private Observation find(Connection c,StockControlRequest r)throws SQLException{try(var q=c.prepareStatement("select state,attempt_id,receiver_fence,execute_before from platform_stock_control_attempt where bot_ref=? and source_update_id=?")){q.setString(1,r.botRef());q.setLong(2,Long.parseLong(r.sourceUpdateId()));try(var rows=q.executeQuery()){if(!rows.next())throw new IllegalStateException("stock-control-attempt-not-found");return new Observation(rows.getString(1),rows.getObject(2,UUID.class),Long.toString(rows.getLong(3)),rows.getTimestamp(4).toInstant());}}}
 private void requireOwned(Claim c){if(c==null||c.owner!=this)throw denied();}
 private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
 private static SecurityException denied(){return new SecurityException("stock-control-attempt-denied");}
}
