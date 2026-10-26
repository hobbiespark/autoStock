package com.autostock.monitor.control;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Strict unsigned wire data. A future authenticated app-owned transport must verify it separately. */
public final class StockControlWire {
    private static final Set<String> FIELDS=Set.of("contract_version","app_id","bot_ref","space_id","actor_ref","source_update_id","receiver_fence","command","confirmation_code");
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(2).maxStringLength(256).maxNumberLength(20).build()).enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private StockControlWire(){}
    public static StockControlRequest decode(byte[] bytes){
        try{
            if(bytes==null||bytes.length==0||bytes.length>4096)throw invalid();
            String utf8=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode root=JSON.readTree(utf8);if(root==null||!root.isObject())throw invalid();var names=new HashSet<String>();root.fieldNames().forEachRemaining(names::add);if(!names.equals(FIELDS))throw invalid();
            JsonNode code=root.get("confirmation_code");if(!code.isNull()&&!code.isTextual())throw invalid();
            return new StockControlRequest(text(root,"contract_version"),text(root,"app_id"),text(root,"bot_ref"),text(root,"space_id"),text(root,"actor_ref"),text(root,"source_update_id"),text(root,"receiver_fence"),StockControlRequest.Command.valueOf(text(root,"command")),code.isNull()?null:code.textValue());
        }catch(Exception denied){throw invalid();}
    }
    public static byte[] encode(StockControlRequest value){
        if(value==null)throw invalid();var root=JSON.createObjectNode();root.put("contract_version",value.contractVersion());root.put("app_id",value.appId());root.put("bot_ref",value.botRef());root.put("space_id",value.spaceId());root.put("actor_ref",value.actorRef());root.put("source_update_id",value.sourceUpdateId());root.put("receiver_fence",value.receiverFence());root.put("command",value.command().name());if(value.confirmationCode()==null)root.putNull("confirmation_code");else root.put("confirmation_code",value.confirmationCode());
        try{return JSON.writeValueAsBytes(root);}catch(Exception denied){throw invalid();}
    }
    private static String text(JsonNode root,String field){var value=root.get(field);if(!value.isTextual())throw invalid();return value.textValue();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("invalid-stock-control-envelope");}
}
