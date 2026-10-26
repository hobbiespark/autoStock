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
class StockControlReceiptStoreDbTest extends PostgresDataJpaTest {
 @Autowired DataSource source;
 final StockControlVerifierTest proof=new StockControlVerifierTest();
 final String bot=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
 final byte[] encryption=new byte[32];StockControlVerifier verifier;StockControlReceiptStore store;
 @BeforeEach void setup(){Arrays.fill(encryption,(byte)7);verifier=new StockControlVerifier("platform_test",proof.pair.getPublic(),bot,"sp_self","b".repeat(64),proof.clock);store=new StockControlReceiptStore(source,verifier,"test_key",encryption);}
 StockControlVerifier.Verified verified(long id,long fence,String code)throws Exception{
  var request=new StockControlRequest("1.0","autostock",bot,"sp_self","b".repeat(64),Long.toString(id),Long.toString(fence),StockControlRequest.Command.resume_confirm,code);
  return verifier.verify(proof.proof(StockControlWire.encode(request),StockControlVerifierTest.NOW,StockControlVerifierTest.NOW.plusSeconds(30),proof.pair.getPrivate(),"valunivo-telegram-stock-control-proof-v1"));
 }
 String scalar(String sql)throws Exception{try(var c=source.getConnection();var q=c.createStatement();var rows=q.executeQuery(sql)){rows.next();return rows.getString(1);}}
 void sql(String sql)throws Exception{try(var c=source.getConnection();var q=c.createStatement()){q.execute(sql);}}
 @Test void encryptedOriginalReceiptSurvivesReopenAndNewFenceReplay()throws Exception{
  var original=verified(7,1,"0427");var first=store.receive(original);assertFalse(first.duplicate());assertEquals("received",first.state());String cipher=scalar("select proof_enc from platform_stock_control_receipt where bot_ref='"+bot+"'");
  var reopened=new StockControlReceiptStore(source,verifier,"test_key",encryption);var replay=reopened.receive(verified(7,2,"0427"));assertTrue(replay.duplicate());assertEquals(first.requestFingerprint(),replay.requestFingerprint());assertEquals("1",replay.receiverFence());assertEquals(cipher,scalar("select proof_enc from platform_stock_control_receipt where bot_ref='"+bot+"'"));assertFalse(first.toString().contains("0427"));
  byte[] packed=Base64.getDecoder().decode(cipher);var aes=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");aes.init(javax.crypto.Cipher.DECRYPT_MODE,new javax.crypto.spec.SecretKeySpec(encryption,"AES"),new javax.crypto.spec.GCMParameterSpec(128,Arrays.copyOf(packed,12)));aes.updateAAD(("autostock-control-receipt-v1:"+bot+":7:test_key").getBytes(java.nio.charset.StandardCharsets.UTF_8));assertArrayEquals(original.proofBytes(),aes.doFinal(Arrays.copyOfRange(packed,12,packed.length)));assertTrue(PostgresTestDatabase.kind().contains("timescale"));
 }
 @Test void concurrentReplayCreatesOneReceipt()throws Exception{
  var value=verified(8,1,"0427");var start=new CountDownLatch(1);
  try(var pool=Executors.newFixedThreadPool(2)){Callable<StockControlReceiptStore.Receipt> call=()->{start.await();return store.receive(value);};var a=pool.submit(call);var b=pool.submit(call);start.countDown();assertNotEquals(a.get(10,TimeUnit.SECONDS).duplicate(),b.get(10,TimeUnit.SECONDS).duplicate());}
  assertEquals("1",scalar("select count(*) from platform_stock_control_receipt where bot_ref='"+bot+"'"));
 }
 @Test void changedCodeConflictsAndLowerFenceCannotAdmitNewUpdate()throws Exception{
  store.receive(verified(10,3,"0427"));assertThrows(SecurityException.class,()->store.receive(verified(10,4,"0428")));assertThrows(SecurityException.class,()->store.receive(verified(11,2,"0427")));assertEquals("3",scalar("select highest_fence from platform_stock_control_receiver where bot_ref='"+bot+"'"));assertTrue(store.receive(verified(10,1,"0427")).duplicate());assertEquals("1",scalar("select count(*) from platform_stock_control_receipt where bot_ref='"+bot+"'"));
 }
 @Test void receiptFailureRollsBackFenceAndAllowsExplicitReentry()throws Exception{
  var value=verified(12,9,"0427");sql("create function test_control_receipt_fail() returns trigger language plpgsql as $$ begin raise exception 'injected receipt failure'; end $$; create trigger test_control_receipt_fail before insert on platform_stock_control_receipt for each row execute function test_control_receipt_fail()");
  try{assertThrows(IllegalStateException.class,()->store.receive(value));}finally{sql("drop trigger test_control_receipt_fail on platform_stock_control_receipt; drop function test_control_receipt_fail()");}
  assertEquals("0",scalar("select count(*) from platform_stock_control_receiver where bot_ref='"+bot+"'"));assertEquals("0",scalar("select count(*) from platform_stock_control_receipt where bot_ref='"+bot+"'"));assertFalse(store.receive(value).duplicate());
 }
 @Test void expiredOrForeignVerifiedValuesCannotWrite()throws Exception{
  var value=verified(13,1,"0427");proof.clock.now=StockControlVerifierTest.NOW.plusSeconds(30);assertThrows(SecurityException.class,()->store.receive(value));assertEquals("0",scalar("select count(*) from platform_stock_control_receipt where bot_ref='"+bot+"'"));proof.clock.now=StockControlVerifierTest.NOW;
  var other=new StockControlVerifier("platform_test",proof.pair.getPublic(),bot,"sp_self","b".repeat(64),proof.clock);var foreign=other.verify(value.proofBytes());assertThrows(SecurityException.class,()->store.receive(foreign));
 }
 @Test void databaseForbidsChangingOrDeletingOriginalReceipt()throws Exception{
  store.receive(verified(14,1,"0427"));assertEquals("42501",assertThrows(SQLException.class,()->sql("update platform_stock_control_receipt set proof_enc='changed' where bot_ref='"+bot+"'")).getSQLState());assertEquals("42501",assertThrows(SQLException.class,()->sql("delete from platform_stock_control_receipt where bot_ref='"+bot+"'")).getSQLState());assertEquals("1",scalar("select count(*) from platform_stock_control_receipt where bot_ref='"+bot+"'"));
 }
}
