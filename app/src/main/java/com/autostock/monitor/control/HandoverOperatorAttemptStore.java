package com.autostock.monitor.control;

import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Durable one-shot claim only. No poller invocation, listener or automatic retry. */
public final class HandoverOperatorAttemptStore {
 public static final class Claim {
  private final HandoverOperatorAttemptStore owner;
  private final UUID requestId,attemptId;
  private final String fingerprint,token;
  private final Instant deadline;
  private Claim(HandoverOperatorAttemptStore owner,UUID requestId,UUID attemptId,String fingerprint,String token,Instant deadline){this.owner=owner;this.requestId=requestId;this.attemptId=attemptId;this.fingerprint=fingerprint;this.token=token;this.deadline=deadline;}
  public Instant executeBefore(){return deadline;}
  @Override public String toString(){return "OperatorStopClaim[private context]";}
 }
 public static final class Dispatch {
  private final HandoverOperatorAttemptStore owner;
  private final Claim claim;
  private final java.util.concurrent.atomic.AtomicBoolean entered=new java.util.concurrent.atomic.AtomicBoolean();
  private Dispatch(HandoverOperatorAttemptStore owner,Claim claim){this.owner=owner;this.claim=claim;}
  @Override public String toString(){return "OperatorStopDispatch[private context]";}
 }
 public record Observation(String state,Instant executeBefore){ }
 private final DataSource source;
 private final HandoverOperatorGate gate;
 private final Clock clock;
 public HandoverOperatorAttemptStore(DataSource source,HandoverOperatorGate gate,Clock clock){this.source=Objects.requireNonNull(source);this.gate=Objects.requireNonNull(gate);this.clock=Objects.requireNonNull(clock);}
 @FunctionalInterface private interface Work<T>{T run(Connection c)throws Exception;}
 private <T>T transaction(UUID requestId,Work<T> work){
  try(var c=source.getConnection()){c.setAutoCommit(false);try{
   try(var q=c.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?,0))")){q.setString(1,"autostock-operator-stop:"+requestId);q.execute();}
   T result=work.run(c);c.commit();return result;
  }catch(Exception failure){c.rollback();if(failure instanceof RuntimeException runtime)throw runtime;throw new IllegalStateException("operator-attempt-unavailable",failure);}}
  catch(SQLException failure){throw new IllegalStateException("operator-attempt-unavailable",failure);}
 }
 private void receipt(Connection c,UUID id,HandoverOperatorGate.Verified value,boolean exact)throws SQLException{
  gate.requireVerified(value);
  try(var q=c.prepareStatement("select operator_key_id,instance_id,request_sha256 from telegram_operator_handover_request where request_id=?")){q.setObject(1,id);try(var rows=q.executeQuery()){
   if(!rows.next()||!rows.getString(1).equals(value.keyId())||!rows.getString(2).equals(value.request().instanceId())||(exact&&!rows.getString(3).equals(value.fingerprint())))throw denied();
  }}
 }
 /** Returns a private claim only after a successful commit; reopening never reconstructs it. */
 public Claim begin(HandoverOperatorGate.Verified value){
  gate.requireVerified(value);if(value.request().action()!=HandoverOperatorGate.Action.stop)throw denied();
  return transaction(value.request().requestId(),c->{
   receipt(c,value.request().requestId(),value,true);
   Instant deadline=value.request().expiresAt().truncatedTo(ChronoUnit.MICROS);if(!clock.instant().isBefore(deadline))throw denied();
   UUID attempt=UUID.randomUUID();String token=UUID.randomUUID().toString();
   try(var q=c.prepareStatement("insert into telegram_operator_handover_attempt(request_id,attempt_id,token_sha256,execute_before) values (?,?,?,?) on conflict(request_id) do nothing")){q.setObject(1,value.request().requestId());q.setObject(2,attempt);q.setString(3,hash(token));q.setTimestamp(4,Timestamp.from(deadline));if(q.executeUpdate()!=1)throw new IllegalStateException("operator-stop-already-attempted");}
   gate.requireVerified(value);if(!clock.instant().isBefore(deadline))throw denied();
   return new Claim(this,value.request().requestId(),attempt,value.fingerprint(),token,deadline);
  });
 }
 /** A later poller adapter must invoke this AFTER its round lock and before its side effect. */
 public void requireCurrent(Claim claim,HandoverOperatorGate.Verified value){
  owned(claim);transaction(claim.requestId,c->{current(c,claim,value);return null;});
 }
 private void current(Connection c,Claim claim,HandoverOperatorGate.Verified value)throws SQLException{
   owned(claim);gate.requireVerified(value);if(value.request().action()!=HandoverOperatorGate.Action.stop||!claim.requestId.equals(value.request().requestId())||!claim.fingerprint.equals(value.fingerprint()))throw denied();
   receipt(c,claim.requestId,value,true);
   try(var q=c.prepareStatement("select 1 from telegram_operator_handover_attempt a where request_id=? and attempt_id=? and token_sha256=? and not exists(select 1 from telegram_operator_handover_unknown u where u.request_id=a.request_id)")){q.setObject(1,claim.requestId);q.setObject(2,claim.attemptId);q.setString(3,hash(claim.token));try(var rows=q.executeQuery()){if(!rows.next())throw denied();}}
   gate.requireVerified(value);if(!clock.instant().isBefore(claim.deadline))throw denied();
 }
 /** Commit a single durable dispatch reservation before waiting for the poller lock. */
 public Dispatch dispatch(Claim claim,HandoverOperatorGate.Verified value){
  owned(claim);return transaction(claim.requestId,c->{
   current(c,claim,value);
   try(var q=c.prepareStatement("insert into telegram_operator_handover_dispatch(request_id,attempt_id) values (?,?) on conflict(request_id) do nothing")){q.setObject(1,claim.requestId);q.setObject(2,claim.attemptId);if(q.executeUpdate()!=1)throw new IllegalStateException("operator-stop-already-dispatched");}
   current(c,claim,value);return new Dispatch(this,claim);
  });
 }
 /** One in-memory entry only, AFTER the poller lock. A lost/failed permit is never reconstructed. */
 public void enter(Dispatch dispatch,HandoverOperatorGate.Verified value){
  if(dispatch==null||dispatch.owner!=this)throw denied();Claim claim=dispatch.claim;
  transaction(claim.requestId,c->{
   current(c,claim,value);
   try(var q=c.prepareStatement("select 1 from telegram_operator_handover_dispatch where request_id=? and attempt_id=?")){q.setObject(1,claim.requestId);q.setObject(2,claim.attemptId);try(var rows=q.executeQuery()){if(!rows.next())throw denied();}}
   current(c,claim,value);if(!dispatch.entered.compareAndSet(false,true))throw denied();return null;
  });
  // A commit may consume time: fail closed after it, without restoring the permit.
  gate.requireVerified(value);if(!clock.instant().isBefore(claim.deadline))throw denied();
 }
 /** Safe failure recording is allowed after request expiry; it never restores execution rights. */
 public void markUnknown(Claim claim){owned(claim);transaction(claim.requestId,c->{
  try(var q=c.prepareStatement("insert into telegram_operator_handover_unknown(request_id,attempt_id) select request_id,attempt_id from telegram_operator_handover_attempt where request_id=? and attempt_id=? and token_sha256=? on conflict(request_id) do nothing")){q.setObject(1,claim.requestId);q.setObject(2,claim.attemptId);q.setString(3,hash(claim.token));q.executeUpdate();}return null;
 });}
 /** Fresh observe can conservatively seal expired attempts unknown, but cannot recover a claim. */
 public Optional<Observation> observe(HandoverOperatorGate.Verified value){
  gate.requireVerified(value);if(value.request().action()!=HandoverOperatorGate.Action.observe)throw denied();UUID id=value.request().targetRequestId();
  return transaction(id,c->{receipt(c,id,value,false);
   try(var q=c.prepareStatement("insert into telegram_operator_handover_unknown(request_id,attempt_id) select request_id,attempt_id from telegram_operator_handover_attempt where request_id=? and execute_before<=? on conflict(request_id) do nothing")){q.setObject(1,id);q.setTimestamp(2,Timestamp.from(clock.instant()));q.executeUpdate();}
   try(var q=c.prepareStatement("select a.execute_before,exists(select 1 from telegram_operator_handover_unknown u where u.request_id=a.request_id) from telegram_operator_handover_attempt a where a.request_id=?")){q.setObject(1,id);try(var rows=q.executeQuery()){Optional<Observation> result=rows.next()?Optional.of(new Observation(rows.getBoolean(2)?"unknown":"running",rows.getTimestamp(1).toInstant())):Optional.empty();gate.requireVerified(value);return result;}}
  });
 }
 private void owned(Claim claim){if(claim==null||claim.owner!=this)throw denied();}
 private static String hash(String token){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
 private static SecurityException denied(){return new SecurityException("operator-attempt-denied");}
}
