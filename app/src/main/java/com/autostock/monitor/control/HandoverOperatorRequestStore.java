package com.autostock.monitor.control;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Immutable operator stop receipt only: no execution, polling, listener or key loading. */
public final class HandoverOperatorRequestStore {
 public record Receipt(UUID requestId,String keyId,String instanceId,String fingerprint,String state,Instant issuedAt,Instant expiresAt,Instant receivedAt,boolean duplicate){
  @Override public String toString(){return "OperatorStopReceipt[state="+state+",duplicate="+duplicate+"]";}
 }
 private final DataSource source;private final HandoverOperatorGate gate;
 public HandoverOperatorRequestStore(DataSource source,HandoverOperatorGate gate){this.source=Objects.requireNonNull(source);this.gate=Objects.requireNonNull(gate);}
 @FunctionalInterface private interface Work<T>{T run(Connection c)throws Exception;}
 private <T>T transaction(HandoverOperatorGate.Verified value,Work<T> work){
  gate.requireVerified(value);try(var c=source.getConnection()){c.setAutoCommit(false);try{gate.requireVerified(value);T result=work.run(c);gate.requireVerified(value);c.commit();return result;}catch(Exception failure){c.rollback();if(failure instanceof RuntimeException runtime)throw runtime;throw new IllegalStateException("operator-request-store-unavailable",failure);}}catch(SQLException failure){throw new IllegalStateException("operator-request-store-unavailable",failure);}
 }
 public Receipt receive(HandoverOperatorGate.Verified value){
  gate.requireVerified(value);var r=value.request();if(r.action()!=HandoverOperatorGate.Action.stop)throw new SecurityException("operator-stop-request-required");
  return transaction(value,c->{
   try(var q=c.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?,0))")){q.setString(1,"autostock-operator-stop:"+r.requestId());q.execute();}gate.requireVerified(value);Receipt old=find(c,r.requestId(),true);
   if(old!=null){if(!old.keyId().equals(value.keyId())||!old.instanceId().equals(r.instanceId())||!old.fingerprint().equals(value.fingerprint()))throw new SecurityException("operator-request-id-conflict");return old;}
   try(var q=c.prepareStatement("insert into telegram_operator_handover_request(request_id,operator_key_id,instance_id,request_sha256,action,issued_at_text,expires_at_text) values (?,?,?,?,'stop',?,?)")){q.setObject(1,r.requestId());q.setString(2,value.keyId());q.setString(3,r.instanceId());q.setString(4,value.fingerprint());q.setString(5,r.issuedAt().toString());q.setString(6,r.expiresAt().toString());q.executeUpdate();}
   return Objects.requireNonNull(find(c,r.requestId(),false));
  });
 }
 /** Fresh authenticated observe is the only expired-request recovery path; it never enqueues another stop. */
 public Optional<Receipt> observe(HandoverOperatorGate.Verified value){
  gate.requireVerified(value);var r=value.request();if(r.action()!=HandoverOperatorGate.Action.observe)throw new SecurityException("operator-observe-request-required");
  return transaction(value,c->{Receipt receipt=find(c,r.targetRequestId(),true);if(receipt==null)return Optional.empty();if(!receipt.keyId().equals(value.keyId())||!receipt.instanceId().equals(r.instanceId()))throw new SecurityException("operator-request-context-denied");return Optional.of(receipt);});
 }
 private Receipt find(Connection c,UUID id,boolean duplicate)throws SQLException{
  try(var q=c.prepareStatement("select operator_key_id,instance_id,request_sha256,state,issued_at_text,expires_at_text,received_at from telegram_operator_handover_request where request_id=?")){q.setObject(1,id);try(var rows=q.executeQuery()){if(!rows.next())return null;return new Receipt(id,rows.getString(1),rows.getString(2),rows.getString(3),rows.getString(4),Instant.parse(rows.getString(5)),Instant.parse(rows.getString(6)),rows.getTimestamp(7).toInstant(),duplicate);}}
 }
}
