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

/** P2-2d local resume: default off; on needs handover beans, a dedicated port and a key that is not the platform listener's. */
class LegacyResumeConfigurationTest {
 static final String SAMPLE="1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020100",P=LegacyResumeConfiguration.PREFIX,O=HandoverOperatorListenerConfiguration.PREFIX;
 @TempDir Path dir;
 final DataSource source=mock(DataSource.class);
 ApplicationContextRunner base(){return new ApplicationContextRunner().withUserConfiguration(LegacyResumeConfiguration.class).withBean(DataSource.class,()->source).withBean(Clock.class,Clock::systemUTC);}
 // The registered classes keep their own conditions, so the existing handover/poller flags must be on for them to exist.
 ApplicationContextRunner full(){return base().withPropertyValues("autostock.telegram.handover.enabled=true","monitor.telegram.enabled=true").withBean(TelegramReceiverStopStore.class,()->mock(TelegramReceiverStopStore.class)).withBean(TelegramCommandPoller.class,()->mock(TelegramCommandPoller.class));}
 Path key(String name)throws Exception{Path file=dir.toRealPath().resolve(name);Files.writeString(file,SAMPLE+"\n",StandardCharsets.US_ASCII);return file;}
 String[] settings(int port,Path key){return new String[]{P+"enabled=true",P+"port="+port,P+"key-id=local_resume",P+"instance-id=local_resume",P+"key-file="+key};}
 void fails(ApplicationContextRunner runner,String code){runner.run(context->{assertNotNull(context.getStartupFailure());Throwable root=context.getStartupFailure();while(root.getCause()!=null)root=root.getCause();assertEquals(code,root.getMessage());assertFalse(context.getStartupFailure().toString().contains(SAMPLE));});}

 @Test void disabledBindsNothingAndNeverReadsTheKey()throws Exception{
  int port=HandoverOperatorListenerConfigurationTest.freePort();Path missing=dir.toRealPath().resolve("never.key");
  for(String flag:new String[]{null,"false"}){var runner=full().withPropertyValues(P+"port="+port,P+"key-file="+missing);if(flag!=null)runner=runner.withPropertyValues(P+"enabled="+flag);
   runner.run(context->{assertNull(context.getStartupFailure());assertTrue(context.getBeansOfType(LegacyResumeListener.class).isEmpty());assertTrue(HandoverOperatorListenerConfigurationTest.bindable(port));});}
  assertFalse(Files.exists(missing));
 }
 @Test void refusesWithoutHandoverBeansSharedKeyOrSharedPort()throws Exception{
  int port=HandoverOperatorListenerConfigurationTest.freePort();Path key=key("resume.key");
  fails(base().withPropertyValues(settings(port,key)),"local-resume-requires-telegram-handover");
  fails(full().withPropertyValues(settings(port,key)).withPropertyValues(O+"key-id=local_resume"),"local-resume-key-must-differ-from-operator-listener");
  fails(full().withPropertyValues(settings(port,key)).withPropertyValues(O+"key-id=operator",O+"key-file="+key),"local-resume-key-must-differ-from-operator-listener");
  fails(full().withPropertyValues(settings(port,key)).withPropertyValues(O+"port="+port),"invalid-local-resume-port");
  fails(full().withPropertyValues(settings(port,key)).withPropertyValues(PlatformStockControlConfiguration.PREFIX+"port="+port),"invalid-local-resume-port");
  fails(full().withPropertyValues(settings(port,key)).withPropertyValues("server.port="+port),"invalid-local-resume-port");
  fails(full().withPropertyValues(P+"enabled=true",P+"port="+port,P+"key-id=local_resume",P+"key-file="+key),"local-resume-settings-required");
  Path bad=dir.toRealPath().resolve("bad.key");Files.writeString(bad,SAMPLE.toUpperCase());fails(full().withPropertyValues(settings(port,bad)),"operator-key-file-rejected");
  assertTrue(HandoverOperatorListenerConfigurationTest.bindable(port));
 }
 @Test void enabledBindsLoopbackAndReleasesOnClose()throws Exception{
  int port=HandoverOperatorListenerConfigurationTest.freePort();
  full().withPropertyValues(settings(port,key("resume.key"))).withPropertyValues(O+"key-id=operator",O+"key-file="+key("operator.key")).run(context->{
   assertNull(context.getStartupFailure());assertEquals(port,context.getBean(LegacyResumeListener.class).port());assertFalse(HandoverOperatorListenerConfigurationTest.bindable(port));
  });
  assertTrue(HandoverOperatorListenerConfigurationTest.bindable(port));
 }
}
