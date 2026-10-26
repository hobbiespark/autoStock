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
 public record Observation(String state,UUID attemptId,String receiverFence,Instant executeBefore){}
 private final DataSource source;private final StockControlVerifier verifier;private final Clock clock;private final Duration budget;
 public StockControlAttemptStore(DataSource source,StockControlVerifier verifier,Clock clock,Duration budget){
  if(source==null||verifier==null||clock==null||budget==null||budget.compareTo(Duration.ofSeconds(1))<0||budget.compareTo(Duration.ofSeconds(30))>0)throw new IllegalArgumentException("invalid-stock-control-attempt-store");
  this.source=source;this.verifier=verifier;this.clock=clock;this.budget=budget;
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
  transaction(claim.request,c->{verifier.requireVerified(value);receipt(c,claim.request,true);var state=find(c,claim.request);if(!state.state().equals("running")||!state.attemptId().equals(claim.id)||!clock.instant().isBefore(state.executeBefore()))throw denied();try(var q=c.prepareStatement("select 1 from platform_stock_control_attempt where attempt_id=? and token_sha256=?")){q.setObject(1,claim.id);q.setString(2,hash(claim.token));try(var rows=q.executeQuery()){if(!rows.next())throw denied();}}verifier.requireVerified(value);if(!clock.instant().isBefore(state.executeBefore()))throw denied();return null;});
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
