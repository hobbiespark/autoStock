package com.autostock.monitor;

import com.autostock.portfolio.PositionBook;
import com.autostock.risk.KillSwitch;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** P2-2d entry: default off (no bean, bind or key read); on needs every explicit setting and refuses startup otherwise. */
class PlatformStockControlConfigurationTest {
 static final String P=PlatformStockControlConfiguration.PREFIX;
 @TempDir Path dir;
 final DataSource source=mock(DataSource.class);
 ApplicationContextRunner base(){return new ApplicationContextRunner().withUserConfiguration(PlatformStockControlConfiguration.class).withBean(DataSource.class,()->source).withBean(Clock.class,Clock::systemUTC)
   .withBean(TelegramStockCommands.class,()->new TelegramStockCommands(mock(KillSwitch.class),new PositionBook(),Clock.systemUTC()));}
 static int freePort()throws Exception{try(var s=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){return s.getLocalPort();}}
 static boolean bindable(int port){try(var s=new ServerSocket(port,1,InetAddress.getByAddress(new byte[]{127,0,0,1}))){return true;}catch(Exception busy){return false;}}
 Path write(String name,byte[] der)throws Exception{Path file=dir.toRealPath().resolve(name);Files.writeString(file,Base64.getEncoder().encodeToString(der)+"\r\n",StandardCharsets.US_ASCII);return file;}
 String[] settings(int port)throws Exception{
  var platform=PlatformStockControlReplica.keys();var ack=PlatformStockControlReplica.keys();var app=PlatformStockControlReplica.keys();byte[] aes=new byte[32];new Random(1).nextBytes(aes);
  return new String[]{P+"enabled=true",P+"port="+port,P+"bot-ref="+"a".repeat(64),P+"space-id=sp_self",P+"actor-ref="+"b".repeat(64),
   P+"platform-request-key-id=platform_test",P+"platform-request-public-key-file="+write("request.pub",platform.getPublic().getEncoded()),
   P+"platform-ack-key-id=platform_ack_test",P+"platform-ack-public-key-file="+write("ack.pub",ack.getPublic().getEncoded()),
   P+"app-result-key-id=autostock_test",P+"app-result-private-key-file="+write("result.key",app.getPrivate().getEncoded()),
   P+"store-key-id=store_test",P+"store-key-file="+write("store.key",aes)};
 }
 void fails(ApplicationContextRunner runner,String code){runner.run(context->{assertNotNull(context.getStartupFailure());Throwable root=context.getStartupFailure();while(root.getCause()!=null)root=root.getCause();assertEquals(code,root.getMessage());});}

 @Test void disabledOrAbsentBindsNothingAndNeverReadsKeyFiles()throws Exception{
  int port=freePort();Path missing=dir.toRealPath().resolve("never-read.pub");
  for(String flag:new String[]{null,"false","yes"}){
   var runner=base().withPropertyValues(P+"port="+port,P+"platform-request-public-key-file="+missing);if(flag!=null)runner=runner.withPropertyValues(P+"enabled="+flag);
   runner.run(context->{assertNull(context.getStartupFailure());assertTrue(context.getBeansOfType(PlatformStockControlListener.class).isEmpty());assertTrue(bindable(port));});
  }
  assertFalse(Files.exists(missing));verifyNoInteractions(source);
 }
 @Test void everySettingIsRequiredAndPortsMustBeDedicated()throws Exception{
  String[] good=settings(freePort());
  for(int skip=2;skip<good.length;skip++){var partial=new ArrayList<>(Arrays.asList(good));partial.remove(skip);fails(base().withPropertyValues(partial.toArray(String[]::new)),skip==1?"invalid-stock-control-port":"stock-control-settings-required");}
  for(String port:new String[]{"0","80","8080","70000","abc"})fails(base().withPropertyValues(good).withPropertyValues(P+"port="+port),"invalid-stock-control-port");
  fails(base().withPropertyValues(good).withPropertyValues("server.port=18556",P+"port=18556"),"invalid-stock-control-port");
  fails(base().withPropertyValues(good).withPropertyValues(P+"port=18557",HandoverOperatorListenerConfiguration.PREFIX+"port=18557"),"invalid-stock-control-port");
  fails(base().withPropertyValues(good).withPropertyValues(P+"attempt-budget-seconds=31"),"invalid-stock-control-attempt-budget");
 }
 @Test void rejectedKeyFilesRefuseStartupWithoutEchoingContentOrBinding()throws Exception{
  int port=freePort();String[] good=settings(port);Path bad=dir.toRealPath().resolve("bad.pub");Files.writeString(bad,"not-base64-content!");
  for(String key:new String[]{"platform-request-public-key-file","platform-ack-public-key-file","app-result-private-key-file","store-key-file"}){
   for(String value:new String[]{bad.toString(),"relative.key",dir.toRealPath().resolve("absent.key").toString()})fails(base().withPropertyValues(good).withPropertyValues(P+key+"="+value),"stock-control-key-file-rejected");
  }
  // A public key where the private key is expected, and a 16-byte AES key.
  fails(base().withPropertyValues(good).withPropertyValues(P+"app-result-private-key-file="+write("swap.key",PlatformStockControlReplica.keys().getPublic().getEncoded())),"stock-control-key-file-rejected");
  fails(base().withPropertyValues(good).withPropertyValues(P+"store-key-file="+write("short.key",new byte[16])),"stock-control-key-file-rejected");
  base().withPropertyValues(good).withPropertyValues(P+"store-key-file="+bad).run(context->assertFalse(context.getStartupFailure().toString().contains("not-base64-content")));
  assertTrue(bindable(port));
 }
 @Test void enabledBindsLoopbackHealthAndReleasesOnClose()throws Exception{
  int port=freePort();var connection=mock(Connection.class);when(connection.isValid(anyInt())).thenReturn(true);when(source.getConnection()).thenReturn(connection);
  var platform=new PlatformStockControlReplica();
  base().withPropertyValues(settings(port)).run(context->{
   assertNull(context.getStartupFailure());assertEquals(port,context.getBean(PlatformStockControlListener.class).port());assertFalse(bindable(port));assertTrue(platform.available(port));
   when(connection.isValid(anyInt())).thenReturn(false);assertFalse(platform.available(port));
  });
  assertTrue(bindable(port));
 }
}
