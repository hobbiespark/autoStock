package com.autostock.monitor;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.*;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformTelegramBridgeTest {
    @TempDir Path temporary;
    Map<String,Object> update(String chat,String sender,String type,String text) {
        return Map.of("update_id",12345L,"message",Map.of("chat",Map.of("id",chat,"type",type),"from",Map.of("id",sender,"is_bot",false),"date",java.time.Instant.now().getEpochSecond(),"text",text));
    }
    @Test void enqueueIsPrivateEncryptedAndDoesNotBlockOnNetwork() throws Exception {
        Path token=temporary.resolve("token");Files.writeString(token,"vnv_"+"a".repeat(64));
        var bridge=new PlatformTelegramBridge(new TelegramProperties(true,"test-bot","123"),WebClient.builder(),"http://127.0.0.1:1/api/channels/telegram",token.toString(),temporary.resolve("spool").toString());
        try {
            assertFalse(bridge.accept(update("999","999","private","플랫폼 상태")));
            assertFalse(bridge.accept(update("123","123","group","플랫폼 상태")));
            assertFalse(bridge.accept(update("123","999","private","플랫폼 상태")));
            for(String command:List.of("/stop","/resume","/resume 1234","/status"))assertFalse(bridge.accept(update("123","123","private",command)));
            assertTrue(bridge.accept(update("123","123","private","플랫폼 상태")));
            assertTrue(bridge.accept(update("123","123","private","플랫폼 상태")));
            try(var files=Files.list(temporary.resolve("spool"))){assertEquals(1,files.count());}
            assertFalse(Files.readString(temporary.resolve("spool/12345.gcm")).contains("플랫폼"));
            bridge.tick();assertTrue(Files.exists(temporary.resolve("spool/12345.gcm")));
        }finally{bridge.stop();}
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void forwardsOnceAndAcknowledgesOnlySuccessfulBotReply(boolean opaqueId) throws Exception {
        String replyId=opaqueId?"r_01M5PZ5TFEN5Y4Q4FKRKYMW6KA":"12345";
        String replyIdJson=opaqueId?"\""+replyId+"\"":replyId;
        var posts=new AtomicInteger();var acks=new AtomicInteger();var sent=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/channels/telegram",e->{
            assertEquals("Bearer vnv_"+"a".repeat(64),e.getRequestHeaders().getFirst("Authorization"));
            String path=e.getRequestURI().getPath();String body="{}";
            if(path.endsWith("/updates")){posts.incrementAndGet();String input=new String(e.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);assertTrue(input.contains("/nivo_help"));}
            if(path.endsWith("/replies"))body=acks.get()==0?"[{\"updateId\":"+replyIdJson+",\"text\":\"연결 확인\"}]":"[]";
            if(path.endsWith("/ack")){assertTrue(path.endsWith("/"+replyId+"/ack"));acks.incrementAndGet();}
            byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();
        });server.start();
        Path token=temporary.resolve("token");Files.writeString(token,"vnv_"+"a".repeat(64));
        var bot=WebClient.builder().exchangeFunction(req->{int count=sent.incrementAndGet();return Mono.just(ClientResponse.create(count==1?HttpStatus.SERVICE_UNAVAILABLE:HttpStatus.OK).header("Content-Type","application/json").body("{\"ok\":true}").build());});
        var bridge=new PlatformTelegramBridge(new TelegramProperties(true,"test-bot","123"),bot,"http://127.0.0.1:"+server.getAddress().getPort()+"/api/channels/telegram",token.toString(),temporary.resolve("spool").toString());
        try {bridge.accept(update("123","123","private","/nivo_help"));assertEquals(0,posts.get());bridge.tick();assertEquals(0,acks.get());bridge.tick();bridge.tick();assertEquals(1,posts.get());assertEquals(1,acks.get());assertEquals(2,sent.get());}
        finally{bridge.stop();server.stop(0);}
    }
    @Test void stockCommandsNeverUseBridge() {
        var bridge=mock(PlatformTelegramBridge.class);
        var kill=mock(com.autostock.risk.KillSwitch.class);
        var poller=new TelegramCommandPoller(WebClient.builder(),new TelegramProperties(true,"test","123"),kill,mock(com.autostock.portfolio.PositionBook.class),mock(Notifier.class),java.time.Clock.systemUTC());
        poller.platformBridge(bridge);
        poller.handleUpdate(update("123","123","private","/stop"));verify(kill).engage(anyString());verifyNoInteractions(bridge);
        var natural=update("123","123","private","플랫폼 상태");poller.handleUpdate(natural);verify(bridge).accept(natural);
    }
}
