package com.autostock.monitor.control;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StockControlProofInteropTest {
 @Test void verifiesActualPlatformIssuerProofsAndRejectsTampering()throws Exception{
  String directory=System.getenv("AUTOSTOCK_CONTROL_PROOF_INTEROP_DIR");org.junit.jupiter.api.Assumptions.assumeTrue(directory!=null,"explicit synthetic public fixture required");Path root=Path.of(directory);var json=new ObjectMapper();var metadata=json.readTree(Files.readAllBytes(root.resolve("public-metadata.json")));var key=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(metadata.get("public_key").textValue())));
  var clock=Clock.fixed(Instant.parse(metadata.get("now").textValue()),ZoneOffset.UTC);var verifier=new StockControlVerifier("platform_test",key,metadata.get("bot_ref").textValue(),"sp_self","b".repeat(64),clock);
  for(var command:StockControlRequest.Command.values()){
   byte[] proof=Files.readAllBytes(root.resolve(command.name()+".json"));var verified=verifier.verify(proof);verifier.requireVerified(verified);assertEquals(command,verified.request().command());assertEquals(metadata.get("fingerprints").get(command.name()).textValue(),verified.request().fingerprint());assertEquals(command==StockControlRequest.Command.resume_confirm?"0427":null,verified.request().confirmationCode());
   var tampered=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(proof);var request=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(Base64.getDecoder().decode(tampered.get("request_base64").textValue()));request.put("receiver_fence","999");tampered.put("request_base64",Base64.getEncoder().encodeToString(json.writeValueAsBytes(request)));assertThrows(SecurityException.class,()->verifier.verify(json.writeValueAsBytes(tampered)));
  }
 }
}
