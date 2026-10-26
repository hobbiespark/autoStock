package com.autostock.monitor.control;
import com.autostock.support.PostgresDataJpaTest;
import com.autostock.support.PostgresTestDatabase;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class StockControlResultStoreDbTest extends PostgresDataJpaTest {
 @Autowired DataSource source;
 final StockControlVerifierTest proof=new StockControlVerifierTest();
 final String bot=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
 final byte[] encryption=new byte[32];StockControlVerifier verifier;StockControlReceiptStore store;StockControlAttemptStore attempts;
 @BeforeEach void setup(){Arrays.fill(encryption,(byte)7);verifier=new StockControlVerifier("platform_test",proof.pair.getPublic(),bot,"sp_self","b".repeat(64),proof.clock);store=new StockControlReceiptStore(source,verifier,"test_key",encryption);attempts=new StockControlAttemptStore(source,verifier,proof.clock,java.time.Duration.ofSeconds(5),new StockControlResultCipher("result_key",encryption));}
 StockControlVerifier.Verified verified(long id,long fence,String code)throws Exception{
  var request=new StockControlRequest("1.0","autostock",bot,"sp_self","b".repeat(64),Long.toString(id),Long.toString(fence),StockControlRequest.Command.resume_confirm,code);
  return verifier.verify(proof.proof(StockControlWire.encode(request),StockControlVerifierTest.NOW,StockControlVerifierTest.NOW.plusSeconds(30),proof.pair.getPrivate(),"valunivo-telegram-stock-control-proof-v1"));
 }
 String scalar(String sql)throws Exception{try(var c=source.getConnection();var q=c.createStatement();var rows=q.executeQuery(sql)){rows.next();return rows.getString(1);}}
 void sql(String sql)throws Exception{try(var c=source.getConnection();var q=c.createStatement()){q.execute(sql);}}
 StockControlResultCipher.Result success(){return new StockControlResultCipher.Result(StockControlResultCipher.Outcome.succeeded,"synthetic-applied","합성 결과 0427");}
 @Test void resultAndTerminalCommitTogetherAndReopenReadsOriginalWithoutToken()throws Exception{
  var value=verified(30,1,"0427");store.receive(value);var claim=attempts.begin(value);var result=attempts.complete(claim,value,success());assertEquals(success(),result.result());assertEquals("succeeded",attempts.recover(value).state());assertFalse(result.toString().contains("0427"));
  var reopened=new StockControlAttemptStore(source,verifier,proof.clock,java.time.Duration.ofSeconds(5),new StockControlResultCipher("result_key",encryption));assertEquals(result,reopened.result(value));attempts.markUnknown(claim);assertEquals("succeeded",attempts.recover(value).state());assertThrows(IllegalStateException.class,()->reopened.begin(value));assertNotEquals(success().reply(),scalar("select result_enc from platform_stock_control_result where bot_ref='"+bot+"'"));
 }
 @Test void identicalCompletionIsStorageIdempotentAndChangedResultConflicts()throws Exception{
  var value=verified(31,1,"0427");store.receive(value);var claim=attempts.begin(value);var first=attempts.complete(claim,value,success());proof.clock.now=StockControlVerifierTest.NOW.plusSeconds(6);assertEquals(first,attempts.complete(claim,value,success()));assertThrows(IllegalStateException.class,()->attempts.complete(claim,value,new StockControlResultCipher.Result(StockControlResultCipher.Outcome.succeeded,"synthetic-applied","changed")));assertEquals("1",scalar("select count(*) from platform_stock_control_result where bot_ref='"+bot+"'"));
 }
 @Test void failedOutcomeIsPersistedWithoutTreatingItAsUnknown()throws Exception{
  var value=verified(32,1,"0427");store.receive(value);var claim=attempts.begin(value);var failed=new StockControlResultCipher.Result(StockControlResultCipher.Outcome.failed,"synthetic-rejected","합성 거절");assertEquals(failed,attempts.complete(claim,value,failed).result());assertEquals("failed",attempts.recover(value).state());assertThrows(IllegalStateException.class,()->attempts.begin(value));
 }
 @Test void unknownExpiredAndHigherFenceCannotAcceptLateOutcome()throws Exception{
  var value=verified(33,1,"0427");store.receive(value);var claim=attempts.begin(value);attempts.markUnknown(claim);assertThrows(SecurityException.class,()->attempts.complete(claim,value,success()));assertNull(attempts.result(value));
  var next=verified(34,1,"0427");store.receive(next);var expiring=attempts.begin(next);proof.clock.now=StockControlVerifierTest.NOW.plusSeconds(5);assertThrows(SecurityException.class,()->attempts.complete(expiring,next,success()));assertNull(attempts.result(next));assertEquals("unknown",attempts.recover(next).state());proof.clock.now=StockControlVerifierTest.NOW;
  var old=verified(35,1,"0427");store.receive(old);var stale=attempts.begin(old);store.receive(verified(36,2,"0427"));assertThrows(SecurityException.class,()->attempts.complete(stale,old,success()));assertNull(attempts.result(old));
 }
 @Test void terminalWriteFailureRollsBackEncryptedResultAndAllowsResultOnlyRetry()throws Exception{
  var value=verified(37,1,"0427");store.receive(value);var claim=attempts.begin(value);
  sql("create function test_control_terminal_fail() returns trigger language plpgsql as $$ begin if new.state='succeeded' then raise exception 'injected terminal failure'; end if; return new; end $$; create trigger test_control_terminal_fail before update on platform_stock_control_attempt for each row execute function test_control_terminal_fail()");
  try{assertThrows(IllegalStateException.class,()->attempts.complete(claim,value,success()));}finally{sql("drop trigger test_control_terminal_fail on platform_stock_control_attempt; drop function test_control_terminal_fail()");}
  assertNull(attempts.result(value));assertEquals("running",attempts.recover(value).state());attempts.requireCurrent(claim,value);assertEquals(success(),attempts.complete(claim,value,success()).result());
 }
 @Test void dbRejectsTerminalWithoutResultAndPreservesFinalResult()throws Exception{
  var value=verified(38,1,"0427");store.receive(value);var claim=attempts.begin(value);assertEquals("42501",assertThrows(SQLException.class,()->sql("update platform_stock_control_attempt set state='succeeded' where bot_ref='"+bot+"'")).getSQLState());attempts.complete(claim,value,success());assertEquals("42501",assertThrows(SQLException.class,()->sql("update platform_stock_control_result set result_sha256='"+"a".repeat(64)+"' where bot_ref='"+bot+"'")).getSQLState());assertEquals("42501",assertThrows(SQLException.class,()->sql("delete from platform_stock_control_result where bot_ref='"+bot+"'")).getSQLState());
 }
 @Test void freshNewFenceCanReadHistoricalResultButCannotExecuteAgain()throws Exception{
  var value=verified(39,1,"0427");store.receive(value);var claim=attempts.begin(value);var old=attempts.complete(claim,value,success());var newer=verified(39,2,"0427");store.receive(newer);assertEquals(old,attempts.result(newer));assertThrows(IllegalStateException.class,()->attempts.begin(newer));
 }
 @Test void databaseRejectsOrphanResultAtCommit()throws Exception{
  var value=verified(40,1,"0427");store.receive(value);var first=attempts.begin(value);attempts.complete(first,value,success());var other=verified(41,1,"0427");store.receive(other);attempts.begin(other);
  String insert="insert into platform_stock_control_result(bot_ref,source_update_id,attempt_id,request_sha256,outcome,result_sha256,key_id,result_enc) select a.bot_ref,a.source_update_id,a.attempt_id,a.request_sha256,'succeeded',r.result_sha256,r.key_id,r.result_enc from platform_stock_control_attempt a cross join platform_stock_control_result r where a.bot_ref='"+bot+"' and a.source_update_id=41 and r.bot_ref='"+bot+"' and r.source_update_id=40";
  assertEquals("42501",assertThrows(SQLException.class,()->sql(insert)).getSQLState());assertNull(attempts.result(other));assertEquals("running",attempts.recover(other).state());
 }
}
