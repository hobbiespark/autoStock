package com.autostock.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.client.reactive.ClientHttpConnectorAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.http.client.reactive.ClientHttpConnectorBuilder;
import org.springframework.boot.http.client.reactive.ClientHttpConnectorSettings;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 외부 HTTP 호출 타임아웃(application.yml {@code spring.http.reactiveclient.*})이 정해진 값으로 걸리고,
 * 응답 없는 서버에서 {@code block()}이 무한 대기하지 않는지 검증한다.
 */
class HttpClientTimeoutTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(
                    ClientHttpConnectorAutoConfiguration.class, WebClientAutoConfiguration.class));

    @Test
    void application_yml의_연결_응답_타임아웃이_커넥터_설정에_반영된다() {
        runner.run(context -> {
            ClientHttpConnectorSettings settings = context.getBean(ClientHttpConnectorSettings.class);

            assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(settings.readTimeout()).isEqualTo(Duration.ofSeconds(20));
        });
    }

    @Test
    void 자동구성_WebClient는_응답없는_서버에서_읽기_타임아웃으로_실패한다() throws IOException {
        try (SilentServer server = new SilentServer()) {
            runner.withPropertyValues("spring.http.reactiveclient.read-timeout=300ms").run(context -> {
                WebClient webClient = context.getBean(WebClient.Builder.class).build();

                long started = System.nanoTime();
                assertThatThrownBy(() -> webClient.get().uri(server.url()).retrieve()
                        .bodyToMono(String.class).block())
                        .isInstanceOf(RuntimeException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
            });
        }
    }

    @Test
    void HttpClient_customizer를_붙인_커넥터에도_설정의_읽기_타임아웃이_적용된다() throws IOException {
        // DART 클라이언트의 TLS 전용 커넥터와 같은 조립 방식(customizer + 공통 settings).
        try (SilentServer server = new SilentServer()) {
            var connector = ClientHttpConnectorBuilder.reactor()
                    .withHttpClientCustomizer(client -> client.compress(false))
                    .build(ClientHttpConnectorSettings.defaults().withReadTimeout(Duration.ofMillis(300)));
            WebClient webClient = WebClient.builder().clientConnector(connector).build();

            long started = System.nanoTime();
            assertThatThrownBy(() -> webClient.get().uri(server.url()).retrieve()
                    .bodyToMono(String.class).block())
                    .isInstanceOf(RuntimeException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        }
    }

    /** 연결은 받아 주지만 응답을 한 바이트도 보내지 않는 로컬 서버. */
    private static final class SilentServer implements AutoCloseable {
        private final ServerSocket serverSocket = new ServerSocket(0);
        private final List<Socket> accepted = new CopyOnWriteArrayList<>();
        private final Thread acceptor = Thread.ofVirtual().start(() -> {
            try {
                while (!serverSocket.isClosed()) {
                    accepted.add(serverSocket.accept());
                }
            } catch (IOException ignored) {
                // close()로 종료
            }
        });

        SilentServer() throws IOException {
        }

        String url() {
            return "http://127.0.0.1:" + serverSocket.getLocalPort() + "/";
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
            for (Socket socket : accepted) {
                socket.close();
            }
            acceptor.interrupt();
        }
    }
}
