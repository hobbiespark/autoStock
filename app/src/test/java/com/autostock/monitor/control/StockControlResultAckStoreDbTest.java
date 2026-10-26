package com.autostock.monitor.control;
import com.autostock.support.PostgresDataJpaTest;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class StockControlResultAckStoreDbTest extends PostgresDataJpaTest {
 @Autowired DataSource source;
 final StockControlVerifierTest proof=new StockControlVerifierTest();final StockControlResultAckVerifierTest ackSigner=new StockControlResultAckVerifierTest();
 final String bot=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
 final byte[] encryption=new byte[32];StockControlVerifier verifier;StockControlReceiptStore store;StockControlAttemptStore attempts;StockControlResultAckVerifier acks;StockControlResultAckStore ackStore;
 @BeforeEach void setup(){Arrays.fill(encryption,(byte)7);ackSigner.clock.now=StockControlVerifierTest.NOW;verifier=new StockControlVerifier("platform_test",proof.pair.getPublic(),bot,"sp_self","b".repeat(64),proof.clock);store=new StockControlReceiptStore(source,verifier,"test_key",encryption);attempts=new StockControlAttemptStore(source,verifier,proof.clock,java.time.Duration.ofSeconds(5),new StockControlResultCipher("result_key",encryption));acks=ackSigner.verifier(bot);ackStore=new StockControlResultAckStore(source,acks);}
 StockControlVerifier.Verified request(long id)throws Exception{var r=new StockControlRequest("1.0","autostock",bot,"sp_self","b".repeat(64),Long.toString(id),"1",StockControlRequest.Command.resume_confirm,"0427");return verifier.verify(proof.proof(StockControlWire.encode(r),StockControlVerifierTest.NOW,StockControlVerifierTest.NOW.plusSeconds(30),proof.pair.getPrivate(),"valunivo-telegram-stock-control-proof-v1"));}
 StockControlAttemptStore.ResultSnapshot completed(long id,StockControlResultCipher.Outcome outcome)throws Exception{var value=request(id);store.receive(value);return attempts.complete(attempts.begin(value),value,new StockControlResultCipher.Result(outcome,"synthetic-result","합성 결과 0427"));}
 com.fasterxml.jackson.databind.node.ObjectNode statement(long id,StockControlAttemptStore.ResultSnapshot r){return ackSigner.statement(bot,Long.toString(id),r.attemptId(),r.requestFingerprint(),r.result().outcome().name(),r.resultSha256());}
 StockControlResultAckVerifier.Verified ack(com.fasterxml.jackson.databind.node.ObjectNode s)throws Exception{return acks.verify(ackSigner.sign(s));}
 String scalar(String sql)throws Exception{try(var c=source.getConnection();var q=c.createStatement();var rows=q.executeQuery(sql)){rows.next();return rows.getString(1);}}
 void sql(String sql)throws Exception{try(var c=source.getConnection();var q=c.createStatement()){q.execute(sql);}}
 String count()throws Exception{return scalar("select count(*) from platform_stock_control_result_ack where bot_ref='"+bot+"'");}
 @Test void terminalResultsRecordAckOnceAndNewIdenticalAckReturnsFirst()throws Exception{
  for(var outcome:StockControlResultCipher.Outcome.values()){long id=outcome==StockControlResultCipher.Outcome.succeeded?60:61;var r=completed(id,outcome);var saved=ackStore.record(ack(statement(id,r)));assertEquals(r.attemptId(),saved.attemptId());assertEquals(outcome.name(),saved.outcome());ackSigner.clock.now=StockControlVerifierTest.NOW.plusSeconds(10);var again=ackStore.record(acks.verify(ackSigner.sign(statement(id,r),StockControlVerifierTest.NOW.plusSeconds(10),StockControlVerifierTest.NOW.plusSeconds(40),ackSigner.pair.getPrivate(),StockControlResultAckVerifierTest.PURPOSE)));assertEquals(saved,again);ackSigner.clock.now=StockControlVerifierTest.NOW;}
  assertEquals("2",count());assertFalse(scalar("select string_agg(ack_envelope,'') from platform_stock_control_result_ack where bot_ref='"+bot+"'").contains("0427"));
 }
 @Test void ackWithoutTerminalResultOrWithMismatchedBindingIsDenied()throws Exception{
  var pending=request(62);store.receive(pending);assertThrows(SecurityException.class,()->ackStore.record(ack(ackSigner.statement(bot,"62",UUID.randomUUID(),pending.request().fingerprint(),"succeeded","f".repeat(64)))));var claim=attempts.begin(pending);assertThrows(SecurityException.class,()->ackStore.record(ack(ackSigner.statement(bot,"62",UUID.randomUUID(),pending.request().fingerprint(),"succeeded","f".repeat(64)))));attempts.markUnknown(claim);assertThrows(SecurityException.class,()->ackStore.record(ack(ackSigner.statement(bot,"62",UUID.randomUUID(),pending.request().fingerprint(),"succeeded","f".repeat(64)))));
  var r=completed(63,StockControlResultCipher.Outcome.succeeded);for(int mode=0;mode<4;mode++){var s=statement(63,r);switch(mode){case 0->s.put("attempt_id",UUID.randomUUID().toString());case 1->s.put("request_sha256","0".repeat(64));case 2->s.put("result_sha256","0".repeat(64));case 3->s.put("outcome","failed");}var value=ack(s);assertThrows(SecurityException.class,()->ackStore.record(value));}
  assertEquals("0",count());
 }
 @Test void differentPlatformReceiptForSameResultConflictsWithoutReplacingFirst()throws Exception{
  var r=completed(64,StockControlResultCipher.Outcome.failed);var first=ackStore.record(ack(statement(64,r)));var s=statement(64,r);s.put("result_proof_sha256","c".repeat(64));assertThrows(IllegalStateException.class,()->ackStore.record(ack(s)));var t=statement(64,r);t.put("platform_recorded_at",StockControlVerifierTest.NOW.minusSeconds(2).toString());assertThrows(IllegalStateException.class,()->ackStore.record(ack(t)));assertEquals(first,ackStore.record(ack(statement(64,r))));assertEquals("1",count());
 }
 @Test void concurrentDuplicateCreatesOneRowAndExpiredAckIsDenied()throws Exception{
  var r=completed(65,StockControlResultCipher.Outcome.succeeded);var value=ack(statement(65,r));var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);try{Callable<StockControlResultAckStore.Recorded> task=()->{start.await();return ackStore.record(value);};var a=pool.submit(task);var b=pool.submit(task);start.countDown();assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));}finally{pool.shutdownNow();}assertEquals("1",count());
  var r2=completed(66,StockControlResultCipher.Outcome.succeeded);var late=ack(statement(66,r2));ackSigner.clock.now=StockControlVerifierTest.NOW.plusSeconds(30);assertThrows(SecurityException.class,()->ackStore.record(late));assertThrows(SecurityException.class,()->ackStore.record(null));assertEquals("1",count());
 }
 @Test void databaseRejectsChangedDeletedAndUnboundAckRows()throws Exception{
  var r=completed(67,StockControlResultCipher.Outcome.succeeded);ackStore.record(ack(statement(67,r)));
  assertEquals("42501",assertThrows(SQLException.class,()->sql("update platform_stock_control_result_ack set outcome='failed' where bot_ref='"+bot+"'")).getSQLState());assertEquals("42501",assertThrows(SQLException.class,()->sql("delete from platform_stock_control_result_ack where bot_ref='"+bot+"'")).getSQLState());
  var r2=completed(68,StockControlResultCipher.Outcome.succeeded);assertEquals("42501",assertThrows(SQLException.class,()->sql("insert into platform_stock_control_result_ack(bot_ref,source_update_id,attempt_id,request_sha256,outcome,result_sha256,result_proof_sha256,platform_key_id,platform_recorded_at,ack_sha256,ack_envelope) values ('"+bot+"',68,'"+r2.attemptId()+"','"+r2.requestFingerprint()+"','succeeded','"+"0".repeat(64)+"','"+"d".repeat(64)+"','k',now(),'"+"a".repeat(64)+"','{}')")).getSQLState());assertEquals("1",count());
 }
}
