package com.autostock.monitor.control;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** App-owned result confidentiality only; does not attest that a business effect happened. */
public final class StockControlResultCipher {
 public enum Outcome { succeeded, failed }
 public record Result(Outcome outcome,String code,String reply){
  public Result{if(outcome==null||code==null||!code.matches("[a-z][a-z0-9-]{0,63}")||reply==null||reply.isBlank()||reply.length()>3000)throw new IllegalArgumentException("invalid-stock-control-result");}
  @Override public String toString(){return "StockControlResult[outcome="+outcome+"]";}
 }
 record Sealed(String keyId,String encrypted,String sha256){}
 private final String keyId;private final byte[] key;private static final ObjectMapper JSON=new ObjectMapper();
 public StockControlResultCipher(String keyId,byte[] key){if(keyId==null||!keyId.matches("[A-Za-z0-9_-]{1,80}")||key==null||key.length!=32)throw new IllegalArgumentException("invalid-stock-result-key");this.keyId=keyId;this.key=key.clone();}
 static byte[] encode(Result result){if(result==null)throw new IllegalArgumentException("invalid-stock-control-result");try{var root=JSON.createObjectNode();root.put("outcome",result.outcome().name());root.put("code",result.code());root.put("reply",result.reply());return JSON.writeValueAsBytes(root);}catch(Exception e){throw new IllegalStateException("stock-result-encoding",e);}}
 static String digest(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 Sealed seal(Result result,String context){try{byte[] raw=encode(result),nonce=new byte[12];new SecureRandom().nextBytes(nonce);var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(aad(context));byte[] value=cipher.doFinal(raw),packed=new byte[12+value.length];System.arraycopy(nonce,0,packed,0,12);System.arraycopy(value,0,packed,12,value.length);return new Sealed(keyId,Base64.getEncoder().encodeToString(packed),digest(raw));}catch(GeneralSecurityException e){throw new IllegalStateException("stock-result-encryption",e);}}
 Result open(String storedKeyId,String encrypted,String expectedHash,String context){try{
  if(!keyId.equals(storedKeyId)||encrypted==null||encrypted.length()>20000)throw new SecurityException();byte[] packed=Base64.getDecoder().decode(encrypted);if(packed.length<28||!Base64.getEncoder().encodeToString(packed).equals(encrypted))throw new SecurityException();var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Arrays.copyOf(packed,12)));cipher.updateAAD(aad(context));byte[] raw=cipher.doFinal(Arrays.copyOfRange(packed,12,packed.length));if(!digest(raw).equals(expectedHash))throw new SecurityException();var root=JSON.readTree(raw);if(root.size()!=3||!root.path("outcome").isTextual()||!root.path("code").isTextual()||!root.path("reply").isTextual())throw new SecurityException();return new Result(Outcome.valueOf(root.get("outcome").textValue()),root.get("code").textValue(),root.get("reply").textValue());
 }catch(Exception invalid){throw new SecurityException("stock-result-content-denied");}}
 private byte[] aad(String context){return ("autostock-control-result-v1:"+keyId+":"+context).getBytes(StandardCharsets.UTF_8);}
}
