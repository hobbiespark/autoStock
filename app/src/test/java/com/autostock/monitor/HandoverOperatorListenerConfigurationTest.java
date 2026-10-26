package com.autostock.monitor;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Default off: no bean, no bind, no key read. On: explicit settings only and any rejection stops startup. */
class HandoverOperatorListenerConfigurationTest {
 static final String SAMPLE="000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",P=HandoverOperatorListenerConfiguration.PREFIX;
 @TempDir Path dir;
 final DataSource source=mock(DataSource.class);
 ApplicationContextRunner base(){return new ApplicationContextRunner().withUserConfiguration(HandoverOperatorListenerConfiguration.class).withBean(DataSource.class,()->source).withBean(Clock.class,Clock::systemUTC);}
 // The registered classes keep their own conditions, so the existing handover/poller flags must be on for them to exist.
 ApplicationContextRunner full(){return base().withPropertyValues("autostock.telegram.handover.enabled=true","monitor.telegram.enabled=true").withBean(TelegramReceiverStopStore.class,()->mock(TelegramReceiverStopStore.class)).withBean(TelegramCommandPoller.class,()->mock(TelegramCommandPoller.class));}
 static int freePort()throws Exception{try(var s=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){return s.getLocalPort();}}
 static boolean bindable(int port){try(var s=new ServerSocket(port,1,InetAddress.getByAddress(new byte[]{127,0,0,1}))){return true;}catch(Exception busy){return false;}}
 Path key()throws Exception{Path file=dir.toRealPath().resolve("operator.key");Files.writeString(file,SAMPLE+"\n",StandardCharsets.US_ASCII);return file;}
 String[] settings(int port,Path key){return new String[]{P+"enabled=true",P+"port="+port,P+"key-id=operator_sample",P+"instance-id=instance_sample",P+"key-file="+key};}
 void fails(ApplicationContextRunner runner,String code){runner.run(context->{assertNotNull(context.getStartupFailure());Throwable root=context.getStartupFailure();while(root.getCause()!=null)root=root.getCause();assertEquals(code,root.getMessage());assertFalse(context.getStartupFailure().toString().contains(SAMPLE));});}

 @Test void disabledOrAbsentBindsNothingAndNeverReadsTheKeyFile()throws Exception{
  int port=freePort();Path missing=dir.toRealPath().resolve("never-read.key");
  for(String flag:new String[]{null,"false","FALSE-ish"}){
   var runner=full().withPropertyValues(P+"port="+port,P+"key-id=operator_sample",P+"instance-id=instance_sample",P+"key-file="+missing);if(flag!=null)runner=runner.withPropertyValues(P+"enabled="+flag);
   runner.run(context->{assertNull(context.getStartupFailure());assertTrue(context.getBeansOfType(HandoverOperatorListener.class).isEmpty());assertTrue(bindable(port));});
  }
  assertFalse(Files.exists(missing));
 }
 @Test void enabledWithoutHandoverStopStoreOrPollerRefusesStartup()throws Exception{
  int port=freePort();Path key=key();
  fails(base().withPropertyValues(settings(port,key)),"operator-listener-requires-telegram-handover");
  fails(base().withBean(TelegramReceiverStopStore.class,()->mock(TelegramReceiverStopStore.class)).withPropertyValues(settings(port,key)),"operator-listener-requires-telegram-handover");
  assertTrue(bindable(port));
 }
 @Test void enabledRequiresExplicitDedicatedPortAndSettings()throws Exception{
  Path key=key();
  for(String port:new String[]{"0","80","8080","70000","","abc"})fails(full().withPropertyValues(settings(1,key)).withPropertyValues(P+"port="+port),"invalid-operator-listener-port");
  fails(full().withPropertyValues(settings(freePort(),key)).withPropertyValues("server.port=18555",P+"port=18555"),"invalid-operator-listener-port");
  fails(full().withPropertyValues(P+"enabled=true",P+"key-id=operator_sample",P+"instance-id=instance_sample",P+"key-file="+key),"invalid-operator-listener-port");
  fails(full().withPropertyValues(P+"enabled=true",P+"port="+freePort(),P+"instance-id=instance_sample",P+"key-file="+key),"operator-listener-settings-required");
  fails(full().withPropertyValues(P+"enabled=true",P+"port="+freePort(),P+"key-id=operator_sample",P+"instance-id=instance_sample"),"operator-listener-settings-required");
 }
 @Test void rejectedKeyFileRefusesStartupWithoutBinding()throws Exception{
  int port=freePort();Path bad=dir.toRealPath().resolve("bad.key");Files.writeString(bad,SAMPLE.toUpperCase());
  fails(full().withPropertyValues(settings(port,bad)),"operator-key-file-rejected");
  fails(full().withPropertyValues(settings(port,Path.of("relative.key"))),"operator-key-file-rejected");
  assertTrue(bindable(port));
 }
 @Test void enabledBindsLoopbackOnlyAndReleasesOnClose()throws Exception{
  int port=freePort();Path key=key();
  full().withPropertyValues(settings(port,key)).run(context->{
   assertNull(context.getStartupFailure());var listener=context.getBean(HandoverOperatorListener.class);assertEquals(port,listener.port());assertFalse(bindable(port));
   try(var socket=new Socket(InetAddress.getByAddress(new byte[]{127,0,0,1}),port)){assertTrue(socket.isConnected());}
  });
  assertTrue(bindable(port));
 }
}
