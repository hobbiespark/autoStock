package com.autostock.monitor;

import com.autostock.monitor.control.HandoverOperatorGate;
import com.autostock.monitor.control.HandoverOperatorKeyFile;
import com.autostock.monitor.control.HandoverOperatorSigner;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client of the loopback operator listener only — not a poller, no DB, no Bot API. Signs one fresh stop or observe
 * request with the key read from a file path and prints the listener's answer. It never resends a stop: a lost
 * response is resolved with {@code observe --target <request_id>}. The key value is never accepted as an argument.
 *
 * <pre>
 * operatorHandoverCli --args="--port 18081 --key-id ID --key-file C:\path\key --instance ID stop"
 * operatorHandoverCli --args="--port 18081 --key-id ID --key-file C:\path\key --instance ID observe --target UUID"
 * </pre>
 * Exit codes: 0 answered 2xx, 2 answered with an error, 3 no answer (outcome unknown), 64 usage.
 */
public final class HandoverOperatorCli {
    static final long VALIDITY_SECONDS = 20;

    private HandoverOperatorCli() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err, Clock.systemUTC()));
    }

    static int run(String[] args, PrintStream out, PrintStream err, Clock clock) {
        Map<String, String> options = new HashMap<>();
        String command = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length && !options.containsKey(args[i])) {
                options.put(args[i], args[++i]);
            } else if (command == null && (args[i].equals("stop") || args[i].equals("observe"))) {
                command = args[i];
            } else {
                return usage(err);
            }
        }
        if (command == null || !options.keySet().stream().allMatch(key -> key.matches("--(port|key-id|key-file|instance|target)"))
                || !options.containsKey("--port") || !options.containsKey("--key-id") || !options.containsKey("--key-file")
                || !options.containsKey("--instance") || options.containsKey("--target") != command.equals("observe")
                || !options.get("--port").matches("[0-9]{4,5}")) {
            return usage(err);
        }
        var action = HandoverOperatorGate.Action.valueOf(command);
        UUID target;
        try {
            target = action == HandoverOperatorGate.Action.observe ? UUID.fromString(options.get("--target")) : null;
        } catch (IllegalArgumentException invalid) {
            return usage(err);
        }
        byte[] key;
        try {
            key = HandoverOperatorKeyFile.read(Path.of(options.get("--key-file")));
        } catch (RuntimeException rejected) {
            err.println("error=operator-key-file-rejected");
            return 64;
        }
        UUID requestId = UUID.randomUUID();
        Instant issued = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        String path = HandoverOperatorSigner.path(action);
        byte[] body;
        String signature;
        try {
            body = HandoverOperatorSigner.body(requestId, action, options.get("--instance"), target, issued,
                    issued.plusSeconds(VALIDITY_SECONDS));
            signature = HandoverOperatorSigner.signature(options.get("--key-id"), key, path, body);
        } catch (IllegalArgumentException invalid) {
            return usage(err);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
        // Printed before sending so a lost response still leaves the ID to observe.
        err.println("request_id=" + requestId + " action=" + action.name());
        var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + options.get("--port") + path))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header(HandoverOperatorSigner.KEY_ID_HEADER, options.get("--key-id"))
                .header(HandoverOperatorSigner.SIGNATURE_HEADER, signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            out.println("status=" + response.statusCode());
            out.println(response.body());
            return response.statusCode() / 100 == 2 ? 0 : 2;
        } catch (IOException lost) {
            err.println("error=operator-response-unknown-use-observe target=" + requestId);
            return 3;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            err.println("error=operator-response-unknown-use-observe target=" + requestId);
            return 3;
        }
    }

    private static int usage(PrintStream err) {
        err.println("usage: --port N --key-id ID --key-file PATH --instance ID (stop | observe --target REQUEST_ID)");
        return 64;
    }
}
