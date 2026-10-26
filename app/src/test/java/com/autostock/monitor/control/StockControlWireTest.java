package com.autostock.monitor.control;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StockControlWireTest {
 StockControlRequest value(StockControlRequest.Command command,String code,String update,String fence){return new StockControlRequest("1.0","autostock","a".repeat(64),"sp_self","b".repeat(64),update,fence,command,code);}
 @Test void actualCodecRoundTripsAllCommandsWithoutLoggingCode()throws Exception{
  for(var command:StockControlRequest.Command.values()){
   var request=value(command,command==StockControlRequest.Command.resume_confirm?"0427":null,"9007199254740993","9223372036854775807");var bytes=StockControlWire.encode(request);assertEquals(request,StockControlWire.decode(bytes));assertEquals(64,request.fingerprint().length());assertFalse(request.toString().contains("0427"));
   String fixture=System.getenv("VALUNIVO_STOCK_CONTROL_FIXTURE_DIR");if(fixture!=null){Path dir=Path.of(fixture);Files.createDirectories(dir);Files.write(dir.resolve(command.name()+".json"),bytes);}
  }
 }
 @Test void confirmationIsRequiredOnlyForConfirm(){
  assertThrows(IllegalArgumentException.class,()->value(StockControlRequest.Command.resume_confirm,null,"1","1"));assertThrows(IllegalArgumentException.class,()->value(StockControlRequest.Command.resume_confirm,"12345","1","1"));for(var command:StockControlRequest.Command.values())if(command!=StockControlRequest.Command.resume_confirm)assertThrows(IllegalArgumentException.class,()->value(command,"0427","1","1"));
 }
 @Test void decimalIdentifiersAreLosslessAndBounded(){
  for(String id:new String[]{"0","01","-1","1.0","9223372036854775807","9223372036854775808"})assertThrows(IllegalArgumentException.class,()->value(StockControlRequest.Command.stop,null,id,"1"));for(String fence:new String[]{"0","01","9223372036854775808"})assertThrows(IllegalArgumentException.class,()->value(StockControlRequest.Command.stop,null,"1",fence));assertEquals("9223372036854775806",value(StockControlRequest.Command.stop,null,"9223372036854775806","1").sourceUpdateId());
 }
 @Test void fingerprintBindsUpdateFenceCommandAndConfirmation(){
  var base=value(StockControlRequest.Command.resume_confirm,"0427","1","1");assertNotEquals(base.fingerprint(),value(StockControlRequest.Command.resume_confirm,"0428","1","1").fingerprint());assertNotEquals(base.fingerprint(),value(StockControlRequest.Command.resume_confirm,"0427","2","1").fingerprint());assertNotEquals(base.fingerprint(),value(StockControlRequest.Command.resume_confirm,"0427","1","2").fingerprint());assertNotEquals(base.fingerprint(),value(StockControlRequest.Command.stop,null,"1","1").fingerprint());
 }
 @Test void wireRejectsAmbiguityUnknownFieldsTypesAndOversize(){
  String valid=new String(StockControlWire.encode(value(StockControlRequest.Command.stop,null,"1","1")),StandardCharsets.UTF_8);
  for(String bad:new String[]{valid+"{}",valid.replace("\"source_update_id\":\"1\"","\"source_update_id\":1"),valid.replace("\"app_id\":\"autostock\"","\"app_id\":\"writing\""),valid.replace("{","{\"extra\":true,"),valid.replace("{","{\"command\":\"status\","),valid.replace("\"confirmation_code\":null","\"confirmation_code\":\"0427\"")})assertThrows(IllegalArgumentException.class,()->StockControlWire.decode(bad.getBytes(StandardCharsets.UTF_8)));
  assertThrows(IllegalArgumentException.class,()->StockControlWire.decode(new byte[4097]));assertThrows(IllegalArgumentException.class,()->StockControlWire.decode(new byte[]{(byte)0xc3,0x28}));
 }
 @Test void consumesActualPlatformFixturesWithoutPlatformCode()throws Exception{
  String fixture=System.getenv("AUTOSTOCK_CONTROL_INTEROP_DIR");org.junit.jupiter.api.Assumptions.assumeTrue(fixture!=null,"explicit synthetic fixture directory required");
  Path input=Path.of(fixture);int count=0;
  try(var paths=Files.list(input)){
   for(Path path:paths.filter(p->p.getFileName().toString().endsWith(".json")).toList()){
    var request=StockControlWire.decode(Files.readAllBytes(path));assertEquals("autostock",request.appId());assertEquals("9007199254740993",request.sourceUpdateId());
    assertEquals(request,StockControlWire.decode(StockControlWire.encode(request)));assertFalse(request.toString().contains("0427"));count++;
   }
  }
  assertEquals(4,count);
 }
}
