package com.autostock.monitor.control;
import com.autostock.support.PostgresDataJpaTest;
import com.autostock.support.PostgresTestDatabase;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class HandoverOperatorRequestStoreDbTest extends PostgresDataJpaTest {
 @Autowired DataSource source;
 final HandoverOperatorGateTest fixture=new HandoverOperatorGateTest();
 HandoverOperatorRequestStore store(){return new HandoverOperatorRequestStore(source,fixture.gate);}
 HandoverOperatorGate.Verified verified(ObjectNode body)throws Exception{return fixture.verify(body);}
 ObjectNode observe(UUID target){var body=fixture.body("observe");body.put("target_request_id",target.toString());body.put("issued_at",fixture.clock.now.toString());body.put("expires_at",fixture.clock.now.plusSeconds(30).toString());return body;}
 long count(UUID id)throws Exception{try(var c=source.getConnection();var q=c.prepareStatement("select count(*) from telegram_operator_handover_request where request_id=?")){q.setObject(1,id);try(var rows=q.executeQuery()){rows.next();return rows.getLong(1);}}}
 void sql(String sql)throws Exception{try(var c=source.getConnection();var q=c.createStatement()){q.execute(sql);}}
 @Test void duplicateAndReopenPreserveOriginalReceiptWithoutExecution()throws Exception{
  var store=store();var value=verified(fixture.body("stop"));var first=store.receive(value);var duplicate=store.receive(value);assertFalse(first.duplicate());assertTrue(duplicate.duplicate());assertEquals(first.receivedAt(),duplicate.receivedAt());assertEquals("received",first.state());assertEquals(first.receivedAt(),store().receive(value).receivedAt());assertEquals(1,count(first.requestId()));assertTrue(PostgresTestDatabase.kind().contains("timescale"));
 }
 @Test void concurrentDuplicateHasOneCreatedReceipt()throws Exception{
  var store=store();var value=verified(fixture.body("stop"));var start=new CountDownLatch(1);
  try(var pool=Executors.newFixedThreadPool(2)){Callable<HandoverOperatorRequestStore.Receipt> task=()->{start.await();return store.receive(value);};var a=pool.submit(task);var b=pool.submit(task);start.countDown();var first=a.get(10,TimeUnit.SECONDS);var second=b.get(10,TimeUnit.SECONDS);assertNotEquals(first.duplicate(),second.duplicate());assertEquals(first.receivedAt(),second.receivedAt());}
  assertEquals(1,count(value.request().requestId()));
 }
 @Test void sameIdWithChangedAuthenticatedBodyConflicts()throws Exception{
  var body=fixture.body("stop");var first=store().receive(verified(body));body.put("expires_at",HandoverOperatorGateTest.NOW.plusSeconds(29).toString());assertThrows(SecurityException.class,()->store().receive(verified(body)));var restored=store().observe(verified(observe(first.requestId()))).orElseThrow();assertEquals(first.fingerprint(),restored.fingerprint());assertEquals(first.expiresAt(),restored.expiresAt());
 }
 @Test void expiredStopNeedsFreshReadOnlyObserveAndMissingTargetIsEmpty()throws Exception{
  var value=verified(fixture.body("stop"));var saved=store().receive(value);fixture.clock.now=HandoverOperatorGateTest.NOW.plusSeconds(40);assertThrows(SecurityException.class,()->store().receive(value));var read=verified(observe(saved.requestId()));var restored=store().observe(read).orElseThrow();assertEquals(saved.receivedAt(),restored.receivedAt());assertEquals(saved.expiresAt(),restored.expiresAt());assertEquals("received",restored.state());assertTrue(store().observe(verified(observe(UUID.randomUUID()))).isEmpty());assertThrows(SecurityException.class,()->store().receive(read));assertEquals(1,count(saved.requestId()));
 }
 @Test void commitFailureDoesNotReturnSuccessfulReceiptOrLeaveRow()throws Exception{
  var value=verified(fixture.body("stop"));sql("create function test_operator_request_fail() returns trigger language plpgsql as $$ begin raise exception 'injected commit failure'; end $$; create constraint trigger test_operator_request_fail after insert on telegram_operator_handover_request deferrable initially deferred for each row execute function test_operator_request_fail()");
  try{assertThrows(IllegalStateException.class,()->store().receive(value));}finally{sql("drop trigger test_operator_request_fail on telegram_operator_handover_request; drop function test_operator_request_fail()");}
  assertEquals(0,count(value.request().requestId()));assertFalse(store().receive(value).duplicate());
 }
 @Test void foreignGateAndWrongActionCannotAdmitOrRead()throws Exception{
  var body=fixture.body("stop");var other=new HandoverOperatorGateTest();assertThrows(SecurityException.class,()->store().receive(other.verify(body)));var stop=verified(body);assertThrows(SecurityException.class,()->store().observe(stop));assertEquals(0,count(stop.request().requestId()));
 }
 @Test void databaseKeepsReceiptImmutable()throws Exception{
  var first=store().receive(verified(fixture.body("stop")));assertEquals("42501",assertThrows(SQLException.class,()->sql("update telegram_operator_handover_request set instance_id='changed' where request_id='"+first.requestId()+"'")).getSQLState());assertEquals("42501",assertThrows(SQLException.class,()->sql("delete from telegram_operator_handover_request where request_id='"+first.requestId()+"'")).getSQLState());assertEquals(1,count(first.requestId()));
 }
}
