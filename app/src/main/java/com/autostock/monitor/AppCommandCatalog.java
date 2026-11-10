package com.autostock.monitor;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * VALUNIVO 3-4 C3(D338, 기획 12 5·6장): autoStock 할 일의 <b>원천 하나</b>. {@code contracts/autostock-manifest.json}(설명서 1.1)만 읽고,
 * 플랫폼 설명서 입구({@code GET /api/app/v1/manifest})·플랫폼 할 일 입구·봇 직접 명령({@code /autostock_[명령]})·봇 help·
 * 봇 명령 메뉴·화면 명령 목록이 모두 이 목록에서 나온다. 명령 목록을 다른 곳에 다시 적지 않는다(전에는 5곳에 따로 있었다).
 * 실행 의미는 {@link AppCommandRunner}가 가지고, 원천과 실행의 명령 집합이 같은지는 시험이 단정한다.
 */
@Component
public class AppCommandCatalog {
    public static final String RESOURCE = "/contracts/autostock-manifest.json";
    /** 앱 직접 텔레그램 이름 앞부분(기획 12 5.2, {@code /[앱ID]_[명령]}). */
    public static final String DIRECT_PREFIX = "/autostock_";
    public static final String HELP = DIRECT_PREFIX + "help";
    /** 손에 익은 옛 이름. 없애지 않고 별칭으로 둔다(D338 ②, D300 ⑤). */
    public static final Map<String, String> ALIASES = Map.of("/stop", "emergency_stop", "/resume", "trading_resume", "/status", "status_view", "/ipo", "ipo_view");
    /** 텔레그램 명령 이름 규칙(봇 메뉴): 영문 소문자·숫자·밑줄 1~32자. */
    static final java.util.regex.Pattern TELEGRAM_NAME = java.util.regex.Pattern.compile("[a-z0-9_]{1,32}");

    public record Arg(String name, String type, boolean required, String description, String example) {}

    public record Command(String id, String name, String description, String risk, List<String> examples, List<Arg> args,
                          List<String> entries, int version) {
        public Command {
            examples = List.copyOf(examples);
            args = List.copyOf(args);
            entries = List.copyOf(entries);
        }

        public boolean query() { return "query".equals(risk); }

        /** {@code /autostock_status}처럼 끝 {@code _view}를 뺀 앱 직접 이름. */
        public String directName() {
            String shortId = id.endsWith("_view") ? id.substring(0, id.length() - "_view".length()) : id;
            return DIRECT_PREFIX + shortId;
        }
    }

    /** 앱 직접 명령 하나: 고른 할 일과 이름 뒤 나머지 글(인수). */
    public record Direct(Command command, String rest) {}

    private final JsonNode manifest;
    private final Map<String, Command> byId;

    public AppCommandCatalog() {
        this(readResource());
    }

    AppCommandCatalog(JsonNode manifest) {
        this.manifest = Objects.requireNonNull(manifest);
        var out = new LinkedHashMap<String, Command>();
        for (JsonNode node : manifest.path("commands")) {
            var command = command(node);
            if (out.putIfAbsent(command.id(), command) != null) throw new IllegalStateException("duplicate command " + command.id());
            if (!TELEGRAM_NAME.matcher(command.directName().substring(1)).matches())
                throw new IllegalStateException("telegram command name out of rules: " + command.id());
        }
        if (out.isEmpty()) throw new IllegalStateException("no commands in " + RESOURCE);
        this.byId = java.util.Collections.unmodifiableMap(out);
    }

    private static JsonNode readResource() {
        try (InputStream input = AppCommandCatalog.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("missing " + RESOURCE);
            return JsonMapper.builder().build().readTree(input.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException("unreadable " + RESOURCE, e);
        }
    }

    private static Command command(JsonNode node) {
        var examples = new ArrayList<String>();
        node.path("examples").forEach(e -> examples.add(e.asText()));
        var args = new ArrayList<Arg>();
        node.path("args").forEach(a -> args.add(new Arg(a.path("name").asText(), a.path("type").asText(), a.path("required").asBoolean(false),
                a.path("description").asText(""), a.path("example").asText(""))));
        var entries = new ArrayList<String>();
        node.path("entries").forEach(e -> entries.add(e.asText()));
        if (entries.isEmpty()) entries.add("platform");
        int version = node.path("version").isIntegralNumber() ? node.path("version").intValue() : 1;
        return new Command(node.path("id").asText(), node.path("name").asText(), node.path("description").asText(""),
                node.path("risk").asText(), examples, args, entries, version);
    }

    /** 플랫폼에 내는 설명서 원문(검사는 플랫폼 검사기가 한다). */
    public JsonNode manifest() { return manifest; }

    public List<Command> commands() { return List.copyOf(byId.values()); }

    public Set<String> ids() { return byId.keySet(); }

    public Optional<Command> find(String id) { return Optional.ofNullable(id == null ? null : byId.get(id)); }

    /** 그 경로({@code platform}·{@code app_bot}·{@code app_fe})로 받는 명령. */
    public List<Command> on(String entry) {
        return byId.values().stream().filter(c -> c.entries().contains(entry)).toList();
    }

    /**
     * 봇 글이 앱 직접 명령이면 그 할 일과 나머지 글. {@code /autostock_quote 005930}처럼 이름 뒤에 인수가 온다.
     * 텔레그램이 붙이는 {@code @봇이름}은 뗀다. 봇으로 받지 않는 명령·모르는 이름은 빈 값.
     */
    public Optional<Direct> direct(String text) {
        if (text == null) return Optional.empty();
        String stripped = text.strip();
        if (!stripped.startsWith(DIRECT_PREFIX)) return Optional.empty();
        int space = stripped.indexOf(' ');
        String name = space < 0 ? stripped : stripped.substring(0, space);
        String rest = space < 0 ? "" : stripped.substring(space + 1).strip();
        int at = name.indexOf('@');
        if (at > 0) name = name.substring(0, at);
        String target = name;
        return on("app_bot").stream().filter(c -> c.directName().equals(target)).findFirst().map(c -> new Direct(c, rest));
    }

    /** {@code /autostock_help} 본문. 원천에서 만든다(명령·설명·인수·예상 평문, 옛 별칭). */
    public String help() {
        var out = new StringBuilder("[autoStock] 앱 직접 명령 ").append(on("app_bot").size()).append("개 — 니보에서는 /nivo_autostock_help\n");
        for (var c : on("app_bot")) {
            out.append('\n').append(c.directName()).append(' ').append(c.name()).append(c.query() ? "" : " · 변경");
            for (var arg : c.args())
                out.append(" [").append(arg.name()).append(arg.required() ? "" : "(선택)").append(arg.example().isEmpty() ? "" : " 예 " + arg.example()).append(']');
            if (!c.examples().isEmpty()) out.append("\n  예: \"").append(c.examples().getFirst()).append('"');
        }
        out.append("\n\n옛 이름도 그대로 받습니다: /stop /resume /status /ipo");
        out.append("\n긴급 정지 풀기는 확인 코드가 필요합니다: ").append(DIRECT_PREFIX).append("trading_resume → 60초 안에 ")
                .append(DIRECT_PREFIX).append("trading_resume 1234");
        return out.toString();
    }

    /** 봇 명령 메뉴(setMyCommands)용 [이름, 설명]. 이름에는 / 가 없다. help가 맨 앞이다. */
    public List<Map.Entry<String, String>> botMenu() {
        var out = new ArrayList<Map.Entry<String, String>>();
        out.add(Map.entry(HELP.substring(1), "autoStock 명령 목록"));
        for (var c : on("app_bot")) out.add(Map.entry(c.directName().substring(1), c.name()));
        return List.copyOf(out);
    }
}
