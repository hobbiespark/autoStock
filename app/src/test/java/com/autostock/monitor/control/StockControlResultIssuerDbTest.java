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
class StockControlResultIssuerDbTest extends PostgresDataJpaTest {
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
 final java.security.KeyPair resultPair=StockControlVerifierTest.keys();
 StockControlResultIssuer issuer(){return new StockControlResultIssuer(attempts,verifier,"autostock_test",resultPair.getPrivate(),proof.clock);}
 @Test void exportsActualDurableSucceededAndFailedResultsWithOnlyPublicKey()throws Exception{
  String directory=System.getenv("AUTOSTOCK_RESULT_PROOF_INTEROP_DIR");var json=new com.fasterxml.jackson.databind.ObjectMapper();var fingerprints=new HashMap<String,String>();java.nio.file.Path root=directory==null?null:java.nio.file.Path.of(directory);if(root!=null)java.nio.file.Files.createDirectories(root);
  for(var outcome:StockControlResultCipher.Outcome.values()){
   long id=outcome==StockControlResultCipher.Outcome.succeeded?50:51;var value=verified(id,1,"0427");store.receive(value);var claim=attempts.begin(value);var result=new StockControlResultCipher.Result(outcome,"synthetic-result","합성 결과 0427");var saved=attempts.complete(claim,value,result);byte[] envelope=issuer().issue(value);var outer=json.readTree(envelope);var statement=json.readTree(Base64.getDecoder().decode(outer.get("statement_base64").textValue()));assertEquals(saved.resultSha256(),statement.get("result_sha256").textValue());assertEquals(saved.requestFingerprint(),statement.get("request_sha256").textValue());assertEquals(saved.attemptId().toString(),statement.get("attempt_id").textValue());fingerprints.put(Long.toString(id),saved.requestFingerprint());if(root!=null)java.nio.file.Files.write(root.resolve(outcome.name()+".json"),envelope);
  }
  if(root!=null)java.nio.file.Files.write(root.resolve("public-metadata.json"),json.writeValueAsBytes(Map.of("public_key",Base64.getEncoder().encodeToString(resultPair.getPublic().getEncoded()),"now",proof.clock.now.toString(),"bot_ref",bot,"request_fingerprints",fingerprints)));
 }
 @Test void noResultRunningAndUnknownCannotBeIssued()throws Exception{
  var value=verified(52,1,"0427");store.receive(value);assertThrows(SecurityException.class,()->issuer().issue(value));var claim=attempts.begin(value);assertThrows(SecurityException.class,()->issuer().issue(value));attempts.markUnknown(claim);assertThrows(SecurityException.class,()->issuer().issue(value));
 }
 @Test void historicalResultBindsOriginalRequestAndDoesNotExtendFreshCallerExpiry()throws Exception{
  var value=verified(53,1,"0427");store.receive(value);var claim=attempts.begin(value);var saved=attempts.complete(claim,value,new StockControlResultCipher.Result(StockControlResultCipher.Outcome.succeeded,"synthetic","reply"));var newer=verified(53,2,"0427");store.receive(newer);proof.clock.now=StockControlVerifierTest.NOW.plusSeconds(20);var json=new com.fasterxml.jackson.databind.ObjectMapper();var outer=json.readTree(issuer().issue(newer));var statement=json.readTree(Base64.getDecoder().decode(outer.get("statement_base64").textValue()));assertEquals(saved.requestFingerprint(),statement.get("request_sha256").textValue());assertNotEquals(newer.request().fingerprint(),statement.get("request_sha256").textValue());assertEquals(StockControlVerifierTest.NOW.plusSeconds(30).toString(),outer.get("expires_at").textValue());proof.clock.now=StockControlVerifierTest.NOW.plusSeconds(30);assertThrows(SecurityException.class,()->issuer().issue(newer));
 }
}
