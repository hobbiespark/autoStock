package com.autostock.monitor;

import com.autostock.monitor.control.*;
import com.autostock.portfolio.PositionBook;
import com.autostock.risk.KillSwitch;
import com.autostock.risk.RiskStateStore;
import com.autostock.support.PostgresDataJpaTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.nio.file.*;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Opt-in cross-repository check (P2-2d): a /stop proof issued by the actual VALUNIVO StockControlProofIssuer
 * (exported by its StockControlProofIssuerIntegrationTest into VALUNIVO_STOCK_PROOF_INTEROP_DIR) is sent over a real loopback
 * socket to this entry; the entry's 137 result proof and public metadata are exported for the platform's actual
 * StockControlResultVerifier. Synthetic public fixtures only; skipped unless both directories are given.
 */
@EnabledIfEnvironmentVariable(named="AUTOSTOCK_PLATFORM_STOCK_PROOF_DIR",matches=".+")
@EnabledIfEnvironmentVariable(named="AUTOSTOCK_ENTRY_RESULT_EXPORT_DIR",matches=".+")
class PlatformStockControlInteropDbTest extends PostgresDataJpaTest {
 @Autowired DataSource source;
 static class Time extends Clock{Instant now;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}}

 @Test void platformIssuedStopRunsOnceThroughTheEntryAndItsResultIsExported()throws Exception{
  var json=new ObjectMapper();Path in=Path.of(System.getenv("AUTOSTOCK_PLATFORM_STOCK_PROOF_DIR")),out=Path.of(System.getenv("AUTOSTOCK_ENTRY_RESULT_EXPORT_DIR"));
  var metadata=json.readTree(Files.readAllBytes(in.resolve("public-metadata.json")));var clock=new Time();clock.now=Instant.parse(metadata.get("now").textValue()).plusSeconds(1);
  var platformKey=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(metadata.get("public_key").textValue())));
  String bot=metadata.get("bot_ref").textValue();var resultPair=PlatformStockControlReplica.keys();var ackPair=PlatformStockControlReplica.keys();byte[] storage=new byte[32];Arrays.fill(storage,(byte)5);
  var verifier=new StockControlVerifier("platform_test",platformKey,bot,"sp_self","b".repeat(64),clock);
  var ackVerifier=new StockControlResultAckVerifier("platform_ack_test",ackPair.getPublic(),bot,"sp_self","b".repeat(64),clock);
  var attempts=new StockControlAttemptStore(source,verifier,clock,Duration.ofSeconds(10),new StockControlResultCipher("store_test",storage));
  var killSwitch=new KillSwitch(mock(ApplicationEventPublisher.class),mock(RiskStateStore.class),clock);
  var entry=new PlatformStockControlEntry(verifier,new StockControlReceiptStore(source,verifier,"store_test",storage),attempts,
    new StockControlResultIssuer(attempts,verifier,"autostock_test",resultPair.getPrivate(),clock),ackVerifier,new StockControlResultAckStore(source,ackVerifier),new TelegramStockCommands(killSwitch,new PositionBook(),clock),source);
  try(var listener=new PlatformStockControlListener(entry,0)){
   listener.start();var platform=new PlatformStockControlReplica();
   var response=platform.post(listener.port(),"requests",Files.readAllBytes(in.resolve("stop.json")));assertEquals(200,response.status(),response.text());assertTrue(killSwitch.isEngaged());
   assertEquals(200,platform.post(listener.port(),"requests",Files.readAllBytes(in.resolve("stop.json"))).status());
   Files.createDirectories(out);Files.write(out.resolve("stop-result.json"),response.body());
   Files.write(out.resolve("public-metadata.json"),json.writeValueAsBytes(Map.of("public_key",Base64.getEncoder().encodeToString(resultPair.getPublic().getEncoded()),"now",clock.now.toString(),
     "bot_ref",bot,"source_update_id","7","request_fingerprint",metadata.get("fingerprints").get("stop").textValue())));
  }
 }
}
