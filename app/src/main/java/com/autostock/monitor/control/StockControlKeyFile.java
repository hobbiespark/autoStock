package com.autostock.monitor.control;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.security.interfaces.EdECPrivateKey;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/** Platform stock control entry keys (P2-2d), same file formats as the platform side (VALUNIVO 159 §4):
 * Ed25519 public X.509 base64, Ed25519 private PKCS8 base64, AES-256 base64. Explicit absolute path only, no generation,
 * fallback, env or argument values. Same path checks as {@link HandoverOperatorKeyFile}; errors never echo key or path. */
public final class StockControlKeyFile {
 static final int MAX_BYTES=512;
 private StockControlKeyFile(){}
 public static PublicKey ed25519Public(Path path){
  byte[] der=base64(path);
  try{var key=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));if(!(key instanceof EdECPublicKey))throw rejected();return key;}
  catch(GeneralSecurityException invalid){throw rejected();}finally{Arrays.fill(der,(byte)0);}
 }
 public static PrivateKey ed25519Private(Path path){
  byte[] der=base64(path);
  try{var key=KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));if(!(key instanceof EdECPrivateKey))throw rejected();return key;}
  catch(GeneralSecurityException invalid){throw rejected();}finally{Arrays.fill(der,(byte)0);}
 }
 public static byte[] aes256(Path path){
  byte[] key=base64(path);if(key.length!=32){Arrays.fill(key,(byte)0);throw rejected();}return key;
 }
 private static byte[] base64(Path path){
  try{
   if(path==null||!path.isAbsolute())throw rejected();
   Path normal=path.normalize();var attributes=Files.readAttributes(normal,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
   if(!attributes.isRegularFile()||attributes.isSymbolicLink()||attributes.isOther()||attributes.size()>MAX_BYTES)throw rejected();
   if(!normal.toRealPath().equals(normal))throw rejected();
   byte[] raw;try(InputStream in=Files.newInputStream(normal,LinkOption.NOFOLLOW_LINKS)){raw=in.readNBytes(MAX_BYTES+1);}
   try{
    int length=raw.length;if(length>MAX_BYTES)throw rejected();
    if(length>0&&raw[length-1]=='\n'){length--;if(length>0&&raw[length-1]=='\r')length--;}
    if(length==0)throw rejected();
    String text=new String(raw,0,length,StandardCharsets.US_ASCII);
    if(!text.matches("[A-Za-z0-9+/]+={0,2}"))throw rejected();
    byte[] decoded=Base64.getDecoder().decode(text);if(!Base64.getEncoder().encodeToString(decoded).equals(text))throw rejected();return decoded;
   }finally{Arrays.fill(raw,(byte)0);}
  }catch(IOException|SecurityException|IllegalArgumentException invalid){throw rejected();}
 }
 private static IllegalStateException rejected(){return new IllegalStateException("stock-control-key-file-rejected");}
}
