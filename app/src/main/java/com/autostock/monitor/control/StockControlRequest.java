package com.autostock.monitor.control;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Data contract only: neither authentication nor authority to control trading. */
public record StockControlRequest(String contractVersion,String appId,String botRef,String spaceId,String actorRef,
        String sourceUpdateId,String receiverFence,Command command,String confirmationCode) {
    public enum Command { stop, resume_request, resume_confirm, status }
    public StockControlRequest {
        if(!"1.0".equals(contractVersion)||!"autostock".equals(appId)||!matches(botRef,"[a-f0-9]{64}")
                ||!matches(spaceId,"[A-Za-z0-9_-]{1,80}")||!matches(actorRef,"[a-f0-9]{64}")||command==null
                ||!decimal(sourceUpdateId,false)||!decimal(receiverFence,true)
                ||(command==Command.resume_confirm?!matches(confirmationCode,"[0-9]{4}"):confirmationCode!=null))
            throw new IllegalArgumentException("invalid-stock-control-request");
    }
    private static boolean decimal(String value,boolean allowMaximum){
        if(!matches(value,"[1-9][0-9]{0,18}"))return false;
        try{long number=Long.parseLong(value);return allowMaximum||number<Long.MAX_VALUE;}catch(NumberFormatException bad){return false;}
    }
    private static boolean matches(String value,String pattern){return value!=null&&value.matches(pattern);}
    public String fingerprint(){
        try{
            var bytes=new ByteArrayOutputStream();var framed=new DataOutputStream(bytes);
            for(String field:List.of("valunivo-telegram-stock-control-v1",contractVersion,appId,botRef,spaceId,actorRef,sourceUpdateId,receiverFence,command.name(),confirmationCode==null?"":confirmationCode)){
                byte[] utf8=field.getBytes(StandardCharsets.UTF_8);framed.writeInt(utf8.length);framed.write(utf8);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        }catch(IOException|NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    @Override public String toString(){return "StockControlRequest[command="+command+"]";}
}
