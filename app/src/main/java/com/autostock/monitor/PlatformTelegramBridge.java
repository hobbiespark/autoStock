package com.autostock.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/** One-way extension of the existing poller. Never polls Telegram or controls trading. */
@Component
@ConditionalOnProperty(name="valunivo.bridge.enabled",havingValue="true")
public class PlatformTelegramBridge {
    private final TelegramProperties telegram;
    private final URI base;
    private final Path spool;
    private final String token;
    private final byte[] key;
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final WebClient bot;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"valunivo-telegram-bridge");t.setDaemon(true);return t;});
    private volatile boolean failedEnqueue;
    public PlatformTelegramBridge(TelegramProperties telegram,WebClient.Builder builder,
            @Value("${valunivo.bridge.url:http://127.0.0.1:18080/api/channels/telegram}") String url,
            @Value("${valunivo.bridge.token-path}") String tokenPath,
            @Value("${valunivo.bridge.spool-path}") String spoolPath) throws Exception {
        base=URI.create(url);
        if(!"http".equals(base.getScheme())||!"127.0.0.1".equals(base.getHost())||base.getPort()<1
                ||!"/api/channels/telegram".equals(base.getPath())||base.getRawQuery()!=null||base.getUserInfo()!=null||base.getFragment()!=null)
            throw new IllegalArgumentException("Configured local channel endpoint required");
        if(telegram.chatId()==null||!telegram.chatId().matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException("Private Telegram chat required");
        this.telegram=telegram;token=Files.readString(Path.of(tokenPath)).strip();
        if(!token.matches("vnv_[A-Za-z0-9_-]{32,128}"))throw new IllegalArgumentException("Channel token required");
        key=MessageDigest.getInstance("SHA-256").digest(("valunivo-spool-v1:"+token).getBytes(StandardCharsets.UTF_8));
        spool=Path.of(spoolPath);Files.createDirectories(spool);
        bot=builder.clone().baseUrl("https://api.telegram.org").codecs(c->c.defaultCodecs().maxInMemorySize(65536)).build();
    }
    static boolean platformText(String text) {
        if(text==null||text.isBlank())return false;
        String value=text.strip();return !value.startsWith("/")||value.equals("/nivo")||value.startsWith("/nivo ")||value.startsWith("/nivo_");
    }
    @SuppressWarnings("unchecked")
    boolean accept(Map<String,Object> update) {
        if(!(update.get("update_id") instanceof Number id)||id.longValue()<1||!(update.get("message") instanceof Map<?,?> message))return false;
        String text=message.get("text") instanceof String s?s:null;
        if(!platformText(text))return false;
        if(!(message.get("chat") instanceof Map<?,?> chat)||!(message.get("from") instanceof Map<?,?> from)
                ||!"private".equals(chat.get("type"))||!telegram.chatId().equals(String.valueOf(chat.get("id")))
                ||!telegram.chatId().equals(String.valueOf(from.get("id")))||Boolean.TRUE.equals(from.get("is_bot")))return false;
        if(text.length()>2000){failedEnqueue=true;return true;}
        if(!(message.get("date") instanceof Number date))return false;
        Path temp=null;
        try {
            Path target=spool.resolve(id.longValue()+".gcm");if(Files.exists(target))return true;
            var envelope=Map.of("updateId",id.longValue(),"sender",Long.parseLong(telegram.chatId()),"chat",Long.parseLong(telegram.chatId()),"chatType","private","date",date.longValue(),"text",text);
            temp=Files.createTempFile(spool,"in-",".tmp");Files.writeString(temp,encrypt(json.writeValueAsString(envelope)));
            Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);return true;
        }catch(Exception ignored){failedEnqueue=true;return true;}
        finally{if(temp!=null)try{Files.deleteIfExists(temp);}catch(Exception ignored){}}
    }
    @PostConstruct void start(){worker.scheduleWithFixedDelay(this::tick,1,2,TimeUnit.SECONDS);}
    @PreDestroy void stop(){worker.shutdownNow();}
    void tick() {
        try {
            if(failedEnqueue){send("VALUNIVO 접수에 실패했습니다. 2000자 이하 새 요청으로 다시 시도해 주세요. 실행하지 않았습니다.");failedEnqueue=false;}
            try(var files=Files.list(spool)) {
                for(Path file:files.filter(p->p.getFileName().toString().matches("[0-9]+\\.gcm")).sorted().limit(5).toList()) {
                    call("/updates","POST",decrypt(Files.readString(file)));Files.delete(file);
                }
            }
            var replies=json.readTree(call("/replies","GET",null));
            if(!replies.isArray()||replies.size()>5)throw new IllegalStateException("Invalid reply batch");
            for(var reply:replies) {
                String id=reply.path("updateId").asText();String text=reply.path("text").asText();
                if(!id.matches("(?:[1-9][0-9]{0,18}|r_[0-9A-Z]{26})")||text.isBlank()||text.length()>3000)throw new IllegalStateException("Invalid reply");
                send("[VALUNIVO] "+text);call("/replies/"+id+"/ack","POST","{}");
                Thread.sleep(1100);
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();}
        catch(Exception ignored){/* Retry durable files/replies next tick. Never log token URLs, prompts or replies. */}
    }
    private String call(String path,String method,String body)throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base.toString()+path)).timeout(Duration.ofSeconds(3))
            .header("Authorization","Bearer "+token).header("Content-Type","application/json");
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        var response=http.send(request.build(),HttpResponse.BodyHandlers.ofInputStream());
        try(var stream=response.body()) {
            CompletableFuture.delayedExecutor(3,TimeUnit.SECONDS).execute(()->{try{stream.close();}catch(Exception ignored){}});
            byte[] bytes=stream.readNBytes(65537);
            if(response.statusCode()<200||response.statusCode()>=300||bytes.length>65536)throw new IllegalStateException("Channel unavailable");
            return new String(bytes,StandardCharsets.UTF_8);
        }
    }
    private void send(String text) {
        var response=bot.post().uri("/bot{token}/sendMessage",telegram.botToken())
            .bodyValue(Map.of("chat_id",telegram.chatId(),"text",text)).retrieve().bodyToMono(Map.class).block(Duration.ofSeconds(8));
        if(response==null||!Boolean.TRUE.equals(response.get("ok")))throw new IllegalStateException("Reply not acknowledged");
    }
    private String encrypt(String text)throws Exception {
        byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);
        var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
        byte[] encrypted=cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)),data=Arrays.copyOf(nonce,12+encrypted.length);System.arraycopy(encrypted,0,data,12,encrypted.length);
        return Base64.getEncoder().encodeToString(data);
    }
    private String decrypt(String text)throws Exception {
        byte[] data=Base64.getDecoder().decode(text);var cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,data,0,12));
        return new String(cipher.doFinal(data,12,data.length-12),StandardCharsets.UTF_8);
    }
}
