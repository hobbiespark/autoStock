package com.autostock.monitor.control;

import com.autostock.support.PostgresDataJpaTest;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class HandoverOperatorAttemptStoreDbTest extends PostgresDataJpaTest {
 @Autowired DataSource source;
 final HandoverOperatorGateTest f=new HandoverOperatorGateTest();
 HandoverOperatorAttemptStore store(){return new HandoverOperatorAttemptStore(source,f.gate,f.clock);}
 HandoverOperatorGate.Verified stop()throws Exception{var value=f.verify(f.body("stop"));new HandoverOperatorRequestStore(source,f.gate).receive(value);return value;}
 HandoverOperatorGate.Verified observe(UUID target)throws Exception{var body=f.body("observe");body.put("target_request_id",target.toString());body.put("issued_at",f.clock.now.toString());body.put("expires_at",f.clock.now.plusSeconds(30).toString());return f.verify(body);}
 void sql(String sql)throws Exception{try(var c=source.getConnection();var q=c.createStatement()){q.execute(sql);}}
 long attempts(UUID id)throws Exception{try(var c=source.getConnection();var q=c.prepareStatement("select count(*) from telegram_operator_handover_attempt where request_id=?")){q.setObject(1,id);try(var rows=q.executeQuery()){rows.next();return rows.getLong(1);}}}
 @Test void oneClaimOnlyAndReopenCannotRecoverExecutionRights()throws Exception{
  var value=stop();var store=store();assertTrue(store.observe(observe(value.request().requestId())).isEmpty());var claim=store.begin(value);store.requireCurrent(claim,value);
  assertThrows(IllegalStateException.class,()->store.begin(value));assertThrows(IllegalStateException.class,()->store().begin(value));assertThrows(SecurityException.class,()->store().requireCurrent(claim,value));assertEquals(1,attempts(value.request().requestId()));
  assertEquals("running",store.observe(observe(value.request().requestId())).orElseThrow().state());assertFalse(claim.toString().contains(value.request().requestId().toString()));
 }
 @Test void concurrentRequestsForSameReceiptIssueOnlyOneClaim()throws Exception{
  var value=stop();var store=store();var start=new CountDownLatch(1);
  try(var pool=Executors.newFixedThreadPool(2)){Callable<Boolean> work=()->{start.await();try{store.begin(value);return true;}catch(IllegalStateException already){assertEquals("operator-stop-already-attempted",already.getMessage());return false;}};var a=pool.submit(work);var b=pool.submit(work);start.countDown();assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));}
  assertEquals(1,attempts(value.request().requestId()));
 }
 @Test void missingReceiptChangedFingerprintObserveAndForeignGateCannotBegin()throws Exception{
  var store=store();var body=f.body("stop");var value=f.verify(body);assertThrows(SecurityException.class,()->store.begin(value));new HandoverOperatorRequestStore(source,f.gate).receive(value);
  body.put("expires_at",f.clock.now.plusSeconds(29).toString());var changed=f.verify(body);assertThrows(SecurityException.class,()->store.begin(changed));assertThrows(SecurityException.class,()->store.begin(observe(value.request().requestId())));
  var foreign=new HandoverOperatorGateTest();assertThrows(SecurityException.class,()->store.begin(foreign.verify(body)));assertEquals(0,attempts(value.request().requestId()));
 }
 @Test void expiryAndFreshObserveSealUnknownWithoutDeadlineExtensionOrReclaim()throws Exception{
  var value=stop();var store=store();var claim=store.begin(value);f.clock.now=f.clock.now.plusSeconds(30);
  assertThrows(SecurityException.class,()->store.requireCurrent(claim,value));assertThrows(SecurityException.class,()->store.begin(value));var observation=store.observe(observe(value.request().requestId())).orElseThrow();assertEquals("unknown",observation.state());assertEquals(claim.executeBefore(),observation.executeBefore());
  f.clock.now=f.clock.now.plusSeconds(30);assertEquals(observation,store().observe(observe(value.request().requestId())).orElseThrow());assertEquals(1,attempts(value.request().requestId()));
 }
 @Test void explicitFailureIsIdempotentAndCannotResumeOrRecoverClaim()throws Exception{
  var value=stop();var store=store();var claim=store.begin(value);store.markUnknown(claim);store.markUnknown(claim);assertEquals("unknown",store.observe(observe(value.request().requestId())).orElseThrow().state());assertThrows(SecurityException.class,()->store.requireCurrent(claim,value));assertThrows(IllegalStateException.class,()->store.begin(value));assertThrows(SecurityException.class,()->store().markUnknown(claim));
 }
 @Test void commitFailureReturnsNoClaimAndRollsBackAttempt()throws Exception{
  var value=stop();sql("create function test_operator_attempt_fail() returns trigger language plpgsql as $$ begin raise exception 'injected commit failure'; end $$; create constraint trigger test_operator_attempt_fail after insert on telegram_operator_handover_attempt deferrable initially deferred for each row execute function test_operator_attempt_fail()");
  try{assertThrows(IllegalStateException.class,()->store().begin(value));}finally{sql("drop trigger test_operator_attempt_fail on telegram_operator_handover_attempt; drop function test_operator_attempt_fail()");}assertEquals(0,attempts(value.request().requestId()));var store=store();store.requireCurrent(store.begin(value),value);
 }
 @Test void databaseRejectsMutationAndExtendedDeadline()throws Exception{
  var value=stop();var store=store();var claim=store.begin(value);String id=value.request().requestId().toString();
  assertEquals("42501",assertThrows(SQLException.class,()->sql("update telegram_operator_handover_attempt set token_sha256=repeat('a',64) where request_id='"+id+"'")).getSQLState());
  assertEquals("42501",assertThrows(SQLException.class,()->sql("delete from telegram_operator_handover_attempt where request_id='"+id+"'")).getSQLState());store.markUnknown(claim);
  assertEquals("42501",assertThrows(SQLException.class,()->sql("delete from telegram_operator_handover_unknown where request_id='"+id+"'")).getSQLState());
  var another=stop();assertEquals("23514",assertThrows(SQLException.class,()->sql("insert into telegram_operator_handover_attempt(request_id,attempt_id,token_sha256,execute_before) values ('"+another.request().requestId()+"','"+UUID.randomUUID()+"',repeat('a',64),'"+f.clock.now.plusSeconds(31)+"')")).getSQLState());
 }
}
