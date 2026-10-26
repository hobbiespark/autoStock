package com.autostock.monitor.control;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Sample keys only. Rejections must stop startup and never echo key material or the path. */
class HandoverOperatorKeyFileTest {
 static final String SAMPLE="000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
 @TempDir Path dir;
 Path write(String name,byte[] content)throws Exception{Path file=dir.toRealPath().resolve(name);Files.write(file,content);return file;}
 void rejected(Path path){var failure=assertThrows(IllegalStateException.class,()->HandoverOperatorKeyFile.read(path));assertEquals("operator-key-file-rejected",failure.getMessage());assertNull(failure.getCause());}
 @Test void readsExactlyThirtyTwoBytesWithOptionalLineEnding()throws Exception{
  byte[] expected=HexFormat.of().parseHex(SAMPLE);
  for(String content:new String[]{SAMPLE,SAMPLE+"\n",SAMPLE+"\r\n"})assertArrayEquals(expected,HandoverOperatorKeyFile.read(write("key"+content.length(),content.getBytes(StandardCharsets.US_ASCII))));
 }
 @Test void rejectsRelativeMissingDirectoryAndNullPaths()throws Exception{
  rejected(null);rejected(Path.of("relative-operator-key"));rejected(dir.toRealPath().resolve("missing"));rejected(dir.toRealPath());
  Files.createDirectory(dir.toRealPath().resolve("sub"));rejected(dir.toRealPath().resolve("sub").resolve("..").resolve("missing"));
 }
 @Test void rejectsOversizeAndMalformedContent()throws Exception{
  rejected(write("big",new byte[HandoverOperatorKeyFile.MAX_BYTES+1]));
  for(String bad:new String[]{"",SAMPLE.substring(1),SAMPLE+"00",SAMPLE.toUpperCase(),SAMPLE+" ",SAMPLE+"\n\n"," "+SAMPLE,"﻿"+SAMPLE,SAMPLE.replace('a','g')})rejected(write("bad"+Integer.toHexString(bad.hashCode()),bad.getBytes(StandardCharsets.UTF_8)));
 }
 @Test void rejectsSymlinkedFileAndSymlinkedParentDirectory()throws Exception{
  Path real=write("real",SAMPLE.getBytes(StandardCharsets.US_ASCII));Path link=dir.toRealPath().resolve("link"),parent=dir.toRealPath().resolve("linked-dir");
  boolean created;try{Files.createSymbolicLink(link,real);Files.createSymbolicLink(parent,dir.toRealPath());created=true;}catch(Exception unsupported){created=false;}
  assumeTrue(created,"symbolic links unavailable for this account");
  rejected(link);rejected(parent.resolve("real"));assertEquals(32,HandoverOperatorKeyFile.read(real).length);
 }
 @Test void rejectsKeyBehindWindowsJunctionReparsePoint()throws Exception{
  assumeTrue(System.getProperty("os.name","").startsWith("Windows"),"Windows junction only");
  Path target=dir.toRealPath().resolve("target");Files.createDirectory(target);Path real=target.resolve("key");Files.writeString(real,SAMPLE);Path junction=dir.toRealPath().resolve("junction");
  var process=new ProcessBuilder("cmd","/c","mklink","/J",junction.toString(),target.toString()).redirectErrorStream(true).start();process.getInputStream().readAllBytes();
  assumeTrue(process.waitFor()==0&&Files.exists(junction.resolve("key")),"junction unavailable");
  rejected(junction.resolve("key"));assertEquals(32,HandoverOperatorKeyFile.read(real).length);
 }
 @Test void rejectionNeverContainsKeyOrPath()throws Exception{
  Path file=write("leak",(SAMPLE.toUpperCase()).getBytes(StandardCharsets.US_ASCII));var failure=assertThrows(IllegalStateException.class,()->HandoverOperatorKeyFile.read(file));
  assertFalse(failure.toString().contains(SAMPLE.toUpperCase())||failure.toString().contains(file.toString())||failure.toString().contains("leak"));
 }
}
