package com.autostock.monitor.control;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HexFormat;

/** Reads the operator HMAC key from an explicit absolute path only. No generation, fallback, env or argument values.
 * Rejections never echo the key or the path; a rejected file stops startup instead of being replaced. */
public final class HandoverOperatorKeyFile {
 static final int MAX_BYTES=128;
 private HandoverOperatorKeyFile(){}
 public static byte[] read(Path path){
  try {
   if(path==null||!path.isAbsolute())throw rejected();
   Path normal=path.normalize();var attributes=Files.readAttributes(normal,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
   if(!attributes.isRegularFile()||attributes.isSymbolicLink()||attributes.isOther()||attributes.size()>MAX_BYTES)throw rejected();
   // A link anywhere in the path (symlink, junction or other reparse point) changes the real path.
   if(!normal.toRealPath().equals(normal))throw rejected();
   byte[] raw;try(InputStream in=Files.newInputStream(normal,LinkOption.NOFOLLOW_LINKS)){raw=in.readNBytes(MAX_BYTES+1);}
   try {
    int length=raw.length;if(length>MAX_BYTES)throw rejected();
    if(length>0&&raw[length-1]=='\n'){length--;if(length>0&&raw[length-1]=='\r')length--;}
    if(length!=64)throw rejected();
    for(int i=0;i<length;i++){byte b=raw[i];if(!((b>='0'&&b<='9')||(b>='a'&&b<='f')))throw rejected();}
    return HexFormat.of().parseHex(new String(raw,0,length,java.nio.charset.StandardCharsets.US_ASCII));
   }finally{Arrays.fill(raw,(byte)0);}
  }catch(IOException|SecurityException|IllegalArgumentException invalid){throw rejected();}
 }
 private static IllegalStateException rejected(){return new IllegalStateException("operator-key-file-rejected");}
}
