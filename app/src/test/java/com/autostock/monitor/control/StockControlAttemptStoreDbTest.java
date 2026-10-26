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
class StockControlAttemptStoreDbTest extends PostgresDataJpaTest {
 @Autowired DataSource source;
 final StockControlVerifierTest proof=new StockControlVerifierTest();
 final String bot=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
 final byte[] encryption=new byte[32];StockControlVerifier verifier;StockControlReceiptStore store;StockControlAttemptStore attempts;
 @BeforeEach void setup(){Arrays.fill(encryption,(byte)7);verifier=new StockControlVerifier("platform_test",proof.pair.getPublic(),bot,"sp_self","b".repeat(64),proof.clock);store=new StockControlReceiptStore(source,verifier,"test_key",encryption);attempts=new StockControlAttemptStore(source,verifier,proof.clock,java.time.Duration.ofSeconds(5));}
 StockControlVerifier.Verified verified(long id,long fence,String code)throws Exception{
  var request=new StockControlRequest("1.0","autostock",bot,"sp_self","b".repeat(64),Long.toString(id),Long.toString(fence),StockControlRequest.Command.resume_confirm,code);
  return verifier.verify(proof.proof(StockControlWire.encode(request),StockControlVerifierTest.NOW,StockControlVerifierTest.NOW.plusSeconds(30),proof.pair.getPrivate(),"valunivo-telegram-stock-control-proof-v1"));
 }
 String scalar(String sql)throws Exception{try(var c=source.getConnection();var q=c.createStatement();var rows=q.executeQuery(sql)){rows.next();return rows.getString(1);}}
 void sql(String sql)throws Exception{try(var c=source.getConnection();var q=c.createStatement()){q.execute(sql);}}
 @Test void onlyFirstBeginReturnsPrivateClaimAndReopenReturnsMetadataOnly()throws Exception{
  var value=verified(20,1,"0427");assertThrows(SecurityException.class,()->attempts.begin(value));store.receive(value);var claim=attempts.begin(value);attempts.requireCurrent(claim,value);assertFalse(claim.toString().contains("0427"));
  var reopened=new StockControlAttemptStore(source,verifier,proof.clock,java.time.Duration.ofSeconds(5));var state=reopened.recover(value);assertEquals("running",state.state());assertEquals(claim.executeBefore(),state.executeBefore());assertThrows(IllegalStateException.class,()->reopened.begin(value));assertThrows(SecurityException.class,()->reopened.requireCurrent(claim,value));
  for(var constructor:StockControlAttemptStore.Claim.class.getDeclaredConstructors())assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
 }
 @Test void concurrentBeginHasOneWinnerAndOneAttempt()throws Exception{
  var value=verified(21,1,"0427");store.receive(value);var start=new CountDownLatch(1);
  try(var pool=Executors.newFixedThreadPool(2)){Callable<Boolean> call=()->{start.await();try{attempts.begin(value);return true;}catch(IllegalStateException duplicate){assertEquals("stock-control-already-attempted",duplicate.getMessage());return false;}};var a=pool.submit(call);var b=pool.submit(call);start.countDown();assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));}
  assertEquals("1",scalar("select count(*) from platform_stock_control_attempt where bot_ref='"+bot+"'"));
 }
 @Test void outcomeLostRemainsUnknownAcrossNewFenceAndNoRetry()throws Exception{
  var value=verified(22,1,"0427");store.receive(value);var claim=attempts.begin(value);attempts.markUnknown(claim);attempts.markUnknown(claim);assertThrows(SecurityException.class,()->attempts.requireCurrent(claim,value));
  var fresh=verified(22,2,"0427");assertTrue(store.receive(fresh).duplicate());assertEquals("unknown",attempts.recover(fresh).state());assertThrows(IllegalStateException.class,()->attempts.begin(fresh));
 }
 @Test void expiredAttemptRecoversUnknownWithoutExtendingDeadline()throws Exception{
  var value=verified(23,1,"0427");store.receive(value);var claim=attempts.begin(value);proof.clock.now=StockControlVerifierTest.NOW.plusSeconds(5);assertThrows(SecurityException.class,()->attempts.requireCurrent(claim,value));var state=attempts.recover(value);assertEquals("unknown",state.state());assertEquals(claim.executeBefore(),state.executeBefore());assertThrows(IllegalStateException.class,()->attempts.begin(value));
 }
 @Test void higherObservedFenceAndChangedRequestBlockOldExecutionContext()throws Exception{
  var value=verified(24,1,"0427");store.receive(value);var claim=attempts.begin(value);store.receive(verified(25,2,"0427"));assertThrows(SecurityException.class,()->attempts.requireCurrent(claim,value));assertThrows(SecurityException.class,()->attempts.requireCurrent(claim,verified(24,2,"0428")));attempts.markUnknown(claim);assertEquals("unknown",attempts.recover(value).state());
 }
 @Test void databasePreservesAttemptIdentityAndStoresOnlyTokenHash()throws Exception{
  var value=verified(26,1,"0427");store.receive(value);attempts.begin(value);assertEquals("64",scalar("select length(token_sha256) from platform_stock_control_attempt where bot_ref='"+bot+"'"));assertEquals("42501",assertThrows(SQLException.class,()->sql("update platform_stock_control_attempt set receiver_fence=9 where bot_ref='"+bot+"'")).getSQLState());assertEquals("42501",assertThrows(SQLException.class,()->sql("delete from platform_stock_control_attempt where bot_ref='"+bot+"'")).getSQLState());
 }
 @Test void subMicrosecondDeadlineNeverRoundsPastOriginalBudget()throws Exception{
  proof.clock.now=StockControlVerifierTest.NOW.plusNanos(501);var value=verified(27,1,"0427");store.receive(value);var claim=attempts.begin(value);assertEquals(StockControlVerifierTest.NOW.plusSeconds(5),claim.executeBefore());assertEquals(claim.executeBefore(),attempts.recover(value).executeBefore());proof.clock.now=claim.executeBefore().minusNanos(1);attempts.requireCurrent(claim,value);proof.clock.now=claim.executeBefore();assertThrows(SecurityException.class,()->attempts.requireCurrent(claim,value));
 }
}
