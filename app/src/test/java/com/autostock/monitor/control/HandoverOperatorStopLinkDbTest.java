package com.autostock.monitor.control;

import com.autostock.monitor.TelegramReceiverStopStore;
import com.autostock.support.PostgresDataJpaTest;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class HandoverOperatorStopLinkDbTest extends PostgresDataJpaTest {
 @Autowired DataSource source;
 final HandoverOperatorGateTest f=new HandoverOperatorGateTest();
 TelegramReceiverStopStore stops;
 @BeforeEach void setup(){stops=new TelegramReceiverStopStore(source);stops.active().ifPresent(s->stops.withdraw(s.stopId(),"test-cleanup"));}
 HandoverOperatorGate.Verified request(boolean dispatch)throws Exception{var value=f.verify(f.body("stop"));new HandoverOperatorRequestStore(source,f.gate).receive(value);if(dispatch){var attempts=new HandoverOperatorAttemptStore(source,f.gate,f.clock);attempts.dispatch(attempts.begin(value),value);}return value;}
 UUID attempt(UUID request)throws Exception{try(var c=source.getConnection();var q=c.prepareStatement("select attempt_id from telegram_operator_handover_dispatch where request_id=?")){q.setObject(1,request);try(var rows=q.executeQuery()){assertTrue(rows.next());return rows.getObject(1,UUID.class);}}}
 void sql(String sql)throws Exception{try(var c=source.getConnection();var q=c.createStatement()){q.execute(sql);}}
 TelegramReceiverStopStore.Stop record(HandoverOperatorGate.Verified value)throws Exception{return stops.recordStoppedForOperator(43,true,value.request().instanceId(),value.request().requestId(),attempt(value.request().requestId()));}
 @Test void stopAndRequestLinkPreserveOriginalEvidenceAcrossReopenAndWithdrawal()throws Exception{
  var value=request(true);var stop=record(value);var id=value.request().requestId();assertEquals(new TelegramReceiverStopStore.OperatorStop(stop,true),new TelegramReceiverStopStore(source).operatorStop(id).orElseThrow());
  stops.withdraw(stop.stopId(),f.INSTANCE);assertEquals(new TelegramReceiverStopStore.OperatorStop(stop,false),stops.operatorStop(id).orElseThrow());assertEquals(stop.recordedAt(),stops.operatorStop(id).orElseThrow().stop().recordedAt());
  assertThrows(IllegalStateException.class,()->record(value));assertTrue(stops.active().isEmpty(),"duplicate link must roll back a new stop too");
 }
 @Test void anotherRequestsActiveStopIsNeverAdopted()throws Exception{
  var first=request(true);var original=record(first);var second=request(true);assertThrows(IllegalStateException.class,()->record(second));assertTrue(stops.operatorStop(second.request().requestId()).isEmpty());assertEquals(original,stops.active().orElseThrow());
 }
 @Test void linkCommitFailureRollsBackStopAndCorrelationTogether()throws Exception{
  var value=request(true);sql("create function test_operator_link_fail() returns trigger language plpgsql as $$ begin raise exception 'injected commit failure'; end $$; create constraint trigger test_operator_link_fail after insert on telegram_operator_handover_stop deferrable initially deferred for each row execute function test_operator_link_fail()");
  try{assertThrows(IllegalStateException.class,()->record(value));}finally{sql("drop trigger test_operator_link_fail on telegram_operator_handover_stop; drop function test_operator_link_fail()");}
  assertTrue(stops.active().isEmpty());assertTrue(stops.operatorStop(value.request().requestId()).isEmpty());
 }
 @Test void missingDispatchWrongAttemptOrInstanceCannotCreateStop()throws Exception{
  var missing=request(false);assertThrows(SecurityException.class,()->stops.recordStoppedForOperator(1,false,f.INSTANCE,missing.request().requestId(),UUID.randomUUID()));var valid=request(true);var id=valid.request().requestId();
  assertThrows(SecurityException.class,()->stops.recordStoppedForOperator(1,false,"other-instance",id,attempt(id)));assertThrows(SecurityException.class,()->stops.recordStoppedForOperator(1,false,f.INSTANCE,id,UUID.randomUUID()));assertTrue(stops.active().isEmpty());
 }
 @Test void databaseRejectsMismatchAndImmutableLinkMutation()throws Exception{
  var value=request(true);var id=value.request().requestId();var stop=stops.recordStopped(1,false,"other-instance");
  assertEquals("23514",assertThrows(SQLException.class,()->sql("insert into telegram_operator_handover_stop(request_id,attempt_id,stop_id,stop_kind) values ('"+id+"','"+attempt(id)+"','"+stop.stopId()+"','stopped')")).getSQLState());stops.withdraw(stop.stopId(),"other-instance");record(value);
  assertEquals("42501",assertThrows(SQLException.class,()->sql("delete from telegram_operator_handover_stop where request_id='"+id+"'")).getSQLState());assertEquals("42501",assertThrows(SQLException.class,()->sql("update telegram_operator_handover_stop set stop_kind='stopped' where request_id='"+id+"'")).getSQLState());
 }
}
