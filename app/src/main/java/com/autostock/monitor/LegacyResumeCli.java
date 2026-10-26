package com.autostock.monitor;

import com.autostock.monitor.control.HandoverOperatorKeyFile;
import com.autostock.monitor.control.LegacyResumeGate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Local operator client of the legacy resume listener (P2-2d) — not a poller, no DB, no Bot API. Reads the platform's
 * release answer (VALUNIVO 161 §3: {@code lease_returned, platform_next_offset, fence, run{run_id,state,stop_id,stop_request_id}})
 * from a file, adds the operator's typed confirmation and signs ONE request with the local resume key read from a file path.
 * Missing evidence fields are sent as null so the app records the rejection. It never resends.
 *
 * <pre>
 * legacyResumeCli --args="--port 18083 --key-id ID --key-file C:\path\key --instance ID --evidence-file C:\path\release.json --confirm resume-legacy-polling:STOP_ID"
 * </pre>
 * Exit codes: 0 resumed, 2 answered with a rejection or error, 3 no answer (check the app ledger before any new request), 64 usage.
 */
public final class LegacyResumeCli {
    static final long VALIDITY_SECONDS = 20;
    static final int MAX_EVIDENCE = 65536;

    private LegacyResumeCli() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err, Clock.systemUTC()));
    }

    static int run(String[] args, PrintStream out, PrintStream err, Clock clock) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length && !options.containsKey(args[i])) {
                options.put(args[i], args[++i]);
            } else {
                return usage(err);
            }
        }
        if (!options.keySet().equals(java.util.Set.of("--port", "--key-id", "--key-file", "--instance", "--evidence-file", "--confirm"))
                || !options.get("--port").matches("[0-9]{4,5}")) {
            return usage(err);
        }
        LegacyResumeGate.Evidence evidence;
        try {
            evidence = evidence(Path.of(options.get("--evidence-file")));
        } catch (RuntimeException unreadable) {
            err.println("error=release-evidence-file-rejected");
            return 64;
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
        byte[] body;
        String signature;
        try {
            body = LegacyResumeGate.body(requestId, options.get("--instance"), evidence, options.get("--confirm"), issued, issued.plusSeconds(VALIDITY_SECONDS));
            signature = LegacyResumeGate.signature(options.get("--key-id"), key, body);
        } catch (IllegalArgumentException invalid) {
            return usage(err);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
        err.println("request_id=" + requestId + " stop_id=" + evidence.stopId());
        var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + options.get("--port") + LegacyResumeGate.PATH))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header(LegacyResumeGate.KEY_ID_HEADER, options.get("--key-id"))
                .header(LegacyResumeGate.SIGNATURE_HEADER, signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            out.println("status=" + response.statusCode());
            out.println(response.body());
            return response.statusCode() == 200 ? 0 : 2;
        } catch (IOException lost) {
            err.println("error=legacy-resume-response-unknown request_id=" + requestId);
            return 3;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            err.println("error=legacy-resume-response-unknown request_id=" + requestId);
            return 3;
        }
    }

    /** Platform release answer file. Absent fields stay null (the app rejects and records); a wrong type is unreadable. */
    static LegacyResumeGate.Evidence evidence(Path path) {
        try {
            if (!path.isAbsolute()) throw new IllegalArgumentException();
            var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.size() > MAX_EVIDENCE) throw new IllegalArgumentException();
            byte[] raw;
            try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                raw = in.readNBytes(MAX_EVIDENCE + 1);
            }
            if (raw.length > MAX_EVIDENCE) throw new IllegalArgumentException();
            JsonNode root = new ObjectMapper().readTree(raw);
            if (root == null || !root.isObject()) throw new IllegalArgumentException();
            JsonNode run = root.path("run");
            return new LegacyResumeGate.Evidence(uuid(run.path("stop_id")), uuid(run.path("run_id")), uuid(run.path("stop_request_id")),
                    text(run.path("state")), bool(root.path("lease_returned")), number(root.path("platform_next_offset")), number(root.path("fence")));
        } catch (IOException invalid) {
            throw new IllegalArgumentException("release-evidence-file-rejected");
        }
    }

    private static String text(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return null;
        if (!node.isTextual()) throw new IllegalArgumentException();
        return node.textValue();
    }

    private static UUID uuid(JsonNode node) {
        String text = text(node);
        return text == null ? null : UUID.fromString(text);
    }

    private static Boolean bool(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return null;
        if (!node.isBoolean()) throw new IllegalArgumentException();
        return node.booleanValue();
    }

    private static Long number(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return null;
        if (!node.isIntegralNumber() || !node.canConvertToLong()) throw new IllegalArgumentException();
        return node.longValue();
    }

    private static int usage(PrintStream err) {
        err.println("usage: --port N --key-id ID --key-file PATH --instance ID --evidence-file PATH --confirm resume-legacy-polling:STOP_ID");
        return 64;
    }
}
