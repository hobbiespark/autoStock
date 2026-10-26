package com.autostock.monitor.control;

import com.autostock.support.PostgresDataJpaTest;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class HandoverOperatorDispatchStoreDbTest extends PostgresDataJpaTest {
 @Autowired DataSource source;
 final HandoverOperatorGateTest f=new HandoverOperatorGateTest();
 HandoverOperatorAttemptStore store(){return new HandoverOperatorAttemptStore(source,f.gate,f.clock);}
 HandoverOperatorGate.Verified stop()throws Exception{var value=f.verify(f.body("stop"));new HandoverOperatorRequestStore(source,f.gate).receive(value);return value;}
 void sql(String sql)throws Exception{try(var c=source.getConnection();var q=c.createStatement()){q.execute(sql);}}
 @Test void dispatchIsDurableAndCannotBeReissuedOrUsedByAnotherStore()throws Exception{
  var value=stop();var store=store();var claim=store.begin(value);var dispatch=store.dispatch(claim,value);
  assertEquals("operator-stop-already-dispatched",assertThrows(IllegalStateException.class,()->store.dispatch(claim,value)).getMessage());assertThrows(SecurityException.class,()->store().enter(dispatch,value));assertThrows(SecurityException.class,()->store().dispatch(claim,value));assertThrows(IllegalStateException.class,()->store().begin(value));
 }
 @Test void samePermitCanEnterOnlyOnceEvenWhenEffectFails()throws Exception{
  var value=stop();var store=store();var claim=store.begin(value);var dispatch=store.dispatch(claim,value);var effects=new AtomicInteger();
  Runnable effect=()->{store.enter(dispatch,value);effects.incrementAndGet();throw new IllegalStateException("synthetic lost response");};
  assertThrows(IllegalStateException.class,effect::run);assertThrows(SecurityException.class,effect::run);assertEquals(1,effects.get());store.markUnknown(claim);assertThrows(SecurityException.class,()->store.enter(dispatch,value));
 }
 @Test void concurrentEntryConsumesPermitOnlyOnce()throws Exception{
  var value=stop();var store=store();var dispatch=store.dispatch(store.begin(value),value);var start=new CountDownLatch(1);
  try(var pool=Executors.newFixedThreadPool(2)){Callable<Boolean> work=()->{start.await();try{store.enter(dispatch,value);return true;}catch(SecurityException denied){return false;}};var a=pool.submit(work);var b=pool.submit(work);start.countDown();assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));}
 }
 @Test void expiredDispatchCannotEnterAndFreshObserveReportsUnknown()throws Exception{
  var value=stop();var store=store();var dispatch=store.dispatch(store.begin(value),value);f.clock.now=f.clock.now.plusSeconds(30);assertThrows(SecurityException.class,()->store.enter(dispatch,value));
  var body=f.body("observe");body.put("target_request_id",value.request().requestId().toString());body.put("issued_at",f.clock.now.toString());body.put("expires_at",f.clock.now.plusSeconds(30).toString());assertEquals("unknown",store.observe(f.verify(body)).orElseThrow().state());
 }
 @Test void dispatchCommitFailureReturnsNoPermitAndRollsBackReservation()throws Exception{
  var value=stop();var store=store();var claim=store.begin(value);
  sql("create function test_operator_dispatch_fail() returns trigger language plpgsql as $$ begin raise exception 'injected commit failure'; end $$; create constraint trigger test_operator_dispatch_fail after insert on telegram_operator_handover_dispatch deferrable initially deferred for each row execute function test_operator_dispatch_fail()");
  try{assertThrows(IllegalStateException.class,()->store.dispatch(claim,value));}finally{sql("drop trigger test_operator_dispatch_fail on telegram_operator_handover_dispatch; drop function test_operator_dispatch_fail()");}
  store.enter(store.dispatch(claim,value),value);
 }
 @Test void dispatchRowsCannotBeChangedOrDeleted()throws Exception{
  var value=stop();var store=store();store.dispatch(store.begin(value),value);String id=value.request().requestId().toString();
  assertEquals("42501",assertThrows(SQLException.class,()->sql("delete from telegram_operator_handover_dispatch where request_id='"+id+"'")).getSQLState());
  assertEquals("42501",assertThrows(SQLException.class,()->sql("update telegram_operator_handover_dispatch set recorded_at=clock_timestamp() where request_id='"+id+"'")).getSQLState());
 }
}
