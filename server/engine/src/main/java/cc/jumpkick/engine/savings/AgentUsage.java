// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.savings;

import cc.jumpkick.config.ReadUsage;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.ProcessEnv;
import cc.jumpkick.jsonl.MiniJson;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Reads the tokens an agent used from its own session files, while {@link ReadUsage} is on:
 * Claude Code's under {@code ~/.claude/projects/<checkout>/} ({@code CLAUDE_CONFIG_DIR}), Codex's
 * under {@code ~/.codex/sessions/} ({@code CODEX_HOME}), Grok's {@code usage.json} under {@code
 * ~/.grok/sessions/} ({@code GROK_HOME}), Copilot CLI's {@code events.jsonl} under {@code
 * ~/.copilot/session-state/} ({@code COPILOT_HOME}), Muse's {@code session.jsonl} under {@code
 * ~/.local/share/muse/sessions/} ({@code XDG_DATA_HOME}) and Antigravity's conversation databases
 * under {@code ~/.gemini/antigravity-cli/conversations/}. Cursor's agent keeps no counts on disk.
 *
 * <p>Only the usage fields are read. Each count becomes a {@code long} inside the parser and a model
 * id is kept only when it has a model id's shape ({@link AgentTokens#model}); no other text of a
 * session file leaves this package, and a line it cannot read is logged by file and line number alone.
 * A file is parsed again only when its size or modification time changed.
 */
public final class AgentUsage {

    /** Reads nothing: an engine or a test with no agent's files to read. */
    public static final AgentUsage NONE = new AgentUsage(Map.of(), () -> false);

    /** An agent whose session files count its tokens, by the name the receipt gives it. */
    public enum Agent {
        CLAUDE_CODE("Claude Code", false),
        CODEX("Codex", false),
        GROK("Grok", false),
        COPILOT("Copilot", true),
        MUSE("Muse", false),
        ANTIGRAVITY("Antigravity", false);

        final String label;

        /** Its files count a whole session at its end, not each call as it happens. */
        final boolean wholeSession;

        Agent(String label, boolean wholeSession) {
            this.label = label;
            this.wholeSession = wholeSession;
        }
    }

    /** A count at one moment: {@code at} epoch millis. */
    record Entry(long at, String model, AgentTokens.Counts counts) {}

    /** One file's counts, as of its {@code size} and {@code modified}; {@code cwd} is the checkout it ran in. */
    record Parsed(long size, long modified, @Nullable String cwd, List<Entry> entries) {}

    /** How many parsed files are kept before the memo starts over. */
    private static final int KEEP = 4096;

    private final Map<Agent, Path> homes;
    private final BooleanSupplier on;
    private final Map<Path, Parsed> parsed = new ConcurrentHashMap<>();

    /** Reads each agent's files under its home in {@code homes} while {@code on}. */
    public AgentUsage(Map<Agent, Path> homes, BooleanSupplier on) {
        this.homes = Map.copyOf(homes);
        this.on = on;
    }

    /** This machine's agents' files, while {@code [agents] read-usage} is on. */
    public static AgentUsage machine() {
        Path home = Path.of(System.getProperty("user.home"));
        String data = ProcessEnv.get("XDG_DATA_HOME");
        Path share = data == null || data.isBlank() ? home.resolve(".local/share") : Path.of(data);
        Map<Agent, Path> homes = new EnumMap<>(Agent.class);
        homes.put(Agent.CLAUDE_CODE, env("CLAUDE_CONFIG_DIR", home.resolve(".claude")));
        homes.put(Agent.CODEX, env("CODEX_HOME", home.resolve(".codex")));
        homes.put(Agent.GROK, env("GROK_HOME", home.resolve(".grok")));
        homes.put(Agent.COPILOT, env("COPILOT_HOME", home.resolve(".copilot")));
        homes.put(Agent.MUSE, share.resolve("muse"));
        homes.put(Agent.ANTIGRAVITY, home.resolve(".gemini/antigravity-cli"));
        return new AgentUsage(homes, ReadUsage::on);
    }

    private static Path env(String name, Path otherwise) {
        String v = ProcessEnv.get(name);
        return v == null || v.isBlank() ? otherwise : Path.of(v);
    }

    /**
     * The agent a session's client ({@code clientInfo.name}) is, or null for one whose files jk does
     * not read. Compared lowercase with everything but letters and digits dropped.
     */
    static @Nullable Agent agentOf(@Nullable String client) {
        if (client == null) return null;
        String c = client.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (c.contains("claude")) return Agent.CLAUDE_CODE;
        if (c.contains("codex")) return Agent.CODEX;
        if (c.contains("grok")) return Agent.GROK;
        if (c.contains("copilot")) return Agent.COPILOT;
        if (c.startsWith("muse")) return Agent.MUSE;
        if (c.startsWith("antigravity") || c.startsWith("agy")) return Agent.ANTIGRAVITY;
        return null;
    }

    /**
     * The tokens the agent {@code client} names used working in {@code project} from {@code since}
     * to {@code until} (epoch millis, both included), or null when reading is off, the agent keeps
     * no counts jk reads, or it counted none in that span. An agent that counts a whole session at its
     * end has that count placed up to {@code next}, excluded: when the episode after this one starts,
     * {@link Long#MAX_VALUE} for the last.
     */
    public @Nullable AgentTokens read(@Nullable String client, String project, long since, long until, long next) {
        Agent agent = agentOf(client);
        if (agent == null || !on.getAsBoolean()) return null;
        Path home = homes.get(agent);
        if (home == null) return null;
        long last = agent.wholeSession ? Math.max(until, next - 1) : until;
        Map<String, AgentTokens.Counts> byModel = new TreeMap<>();
        for (Path file : sessionFiles(agent, home, project, since)) {
            Parsed p = parse(agent, file);
            if (p == null) continue;
            String cwd = p.cwd() == null && agent == Agent.MUSE ? parentCheckout(file) : p.cwd();
            if (agent != Agent.CLAUDE_CODE && !sameDir(cwd, project)) continue;
            for (Entry e : p.entries()) {
                if (e.at() >= since && e.at() <= last) byModel.merge(e.model(), e.counts(), AgentTokens.Counts::plus);
            }
        }
        return byModel.isEmpty() ? null : new AgentTokens(agent.label, byModel, agent.wholeSession);
    }

    /** A Muse subagent's checkout, which its file does not name: its parent session's, two directories up. */
    private @Nullable String parentCheckout(Path file) {
        Path dir = file.getParent();
        for (int up = 0; up < 4 && dir != null; up++) {
            Path above = dir.getParent();
            if (above == null || above.getParent() == null || !"subagent".equals(String.valueOf(above.getFileName()))) {
                return null;
            }
            Path parent = above.getParent().resolve("session.jsonl");
            Parsed p = Files.isRegularFile(parent) ? parse(Agent.MUSE, parent) : null;
            if (p == null) return null;
            if (p.cwd() != null) return p.cwd();
            dir = parent.getParent();
        }
        return null;
    }

    /** Claude Code's directory name for a checkout: every character but a letter or digit becomes {@code -}. */
    public static String claudeDir(String project) {
        return project.replaceAll("[^A-Za-z0-9]", "-");
    }

    private static boolean sameDir(@Nullable String cwd, String project) {
        if (cwd == null) return false;
        try {
            return Path.of(cwd).normalize().equals(Path.of(project).normalize());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** {@code agent}'s files under {@code home} that may hold counts for {@code project}, written at or after {@code since}. */
    private static List<Path> sessionFiles(Agent agent, Path home, String project, long since) {
        return switch (agent) {
            case CLAUDE_CODE ->
                find(home.resolve("projects").resolve(claudeDir(project)), 5, since, f -> f.endsWith(".jsonl"));
            case CODEX -> find(home.resolve("sessions"), 5, since, f -> f.endsWith(".jsonl"));
            case GROK -> find(home.resolve("sessions"), 3, since, f -> f.equals("usage.json"));
            case COPILOT -> find(home.resolve("session-state"), 2, since, f -> f.equals("events.jsonl"));
            case MUSE -> find(home.resolve("sessions"), 8, since, f -> f.equals("session.jsonl"));
            case ANTIGRAVITY -> find(home.resolve("conversations"), 1, since, f -> f.endsWith(".db"));
        };
    }

    /** The regular files under {@code root}, at most {@code depth} down, whose name {@code wanted}, written at or after {@code since}. */
    private static List<Path> find(Path root, int depth, long since, Predicate<String> wanted) {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> files = Files.find(
                root,
                depth,
                (f, a) -> a.isRegularFile() && wanted.test(f.getFileName().toString()))) {
            List<Path> out = new ArrayList<>();
            for (Path f : files.toList()) {
                if (stamp(f)[1] >= since) out.add(f);
            }
            return out;
        } catch (IOException | RuntimeException e) {
            Log.debug("agent usage: " + root + ": not listed");
            return List.of();
        }
    }

    /** A file's size and modification time; a database's count its log's too. */
    private static long[] stamp(Path file) throws IOException {
        if (file.getFileName().toString().endsWith(".db")) return SqliteFile.stamp(file);
        BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
        return new long[] {a.size(), a.lastModifiedTime().toMillis()};
    }

    private @Nullable Parsed parse(Agent agent, Path file) {
        try {
            long[] stamp = stamp(file);
            long size = stamp[0];
            long modified = stamp[1];
            Parsed was = parsed.get(file);
            if (was != null && was.size() == size && was.modified() == modified) return was;
            Parsed now =
                    switch (agent) {
                        case CLAUDE_CODE -> claude(file, size, modified);
                        case CODEX -> codex(file, size, modified);
                        case GROK -> GrokUsage.parse(file, size, modified);
                        case COPILOT -> CopilotUsage.parse(file, size, modified);
                        case MUSE -> MuseUsage.parse(file, size, modified);
                        case ANTIGRAVITY -> AntigravityUsage.parse(file, size, modified);
                    };
            if (parsed.size() >= KEEP) parsed.clear();
            parsed.put(file, now);
            return now;
        } catch (IOException | RuntimeException e) {
            Log.debug("agent usage: " + file + ": not read");
            return null;
        }
    }

    /**
     * Claude Code's file: an assistant line's {@code message.usage}, at its {@code timestamp}, for
     * its {@code message.model}. A message written over several lines counts once, as its last line
     * says.
     */
    static Parsed claude(Path file, long size, long modified) throws IOException {
        Map<String, Entry> byMessage = new LinkedHashMap<>();
        int n = 0;
        try (BufferedReader in = reader(file)) {
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                n++;
                if (!line.contains("\"usage\"")) continue;
                Object root = json(file, n, line);
                if (root == null || !"assistant".equals(MiniJson.str(root, "type"))) continue;
                Object message = MiniJson.get(root, "message");
                Object usage = MiniJson.get(message, "usage");
                if (!(usage instanceof Map<?, ?>)) continue;
                long at = millis(MiniJson.get(root, "timestamp"));
                if (at < 0) {
                    skipped(file, n);
                    continue;
                }
                long input = count(usage, "input_tokens");
                long write = count(usage, "cache_creation_input_tokens");
                long read = count(usage, "cache_read_input_tokens");
                long output = count(usage, "output_tokens");
                if ((input | write | read | output) < 0) {
                    skipped(file, n);
                    continue;
                }
                AgentTokens.Counts counts = new AgentTokens.Counts(input, write, read, output);
                String id = MiniJson.str(message, "id");
                String key =
                        id == null ? "line " + n : id + '\0' + Objects.toString(MiniJson.str(root, "requestId"), "");
                byMessage.put(key, new Entry(at, AgentTokens.model(MiniJson.get(message, "model")), counts));
            }
        }
        return new Parsed(size, modified, null, List.copyOf(byMessage.values()));
    }

    /**
     * Codex's file: its checkout from {@code session_meta}, the model from each {@code turn_context},
     * and each {@code token_count} event's running {@code total_token_usage}, taken as the growth
     * since the event before so a repeated event counts nothing.
     */
    static Parsed codex(Path file, long size, long modified) throws IOException {
        List<Entry> entries = new ArrayList<>();
        String cwd = null;
        String model = AgentTokens.UNKNOWN;
        long input = 0;
        long cached = 0;
        long output = 0;
        int n = 0;
        try (BufferedReader in = reader(file)) {
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                n++;
                boolean meta = line.contains("\"session_meta\"");
                boolean turn = line.contains("\"turn_context\"");
                boolean tokens = line.contains("\"token_count\"");
                if (!meta && !turn && !tokens) continue;
                Object root = json(file, n, line);
                if (root == null) continue;
                String type = MiniJson.str(root, "type");
                Object payload = MiniJson.get(root, "payload");
                if ("session_meta".equals(type)) {
                    if (cwd == null) cwd = MiniJson.str(payload, "cwd");
                } else if ("turn_context".equals(type)) {
                    model = AgentTokens.model(MiniJson.get(payload, "model"));
                } else if ("event_msg".equals(type) && "token_count".equals(MiniJson.str(payload, "type"))) {
                    Object total = MiniJson.get(MiniJson.get(payload, "info"), "total_token_usage");
                    if (!(total instanceof Map<?, ?>)) continue;
                    long at = millis(MiniJson.get(root, "timestamp"));
                    if (at < 0) {
                        skipped(file, n);
                        continue;
                    }
                    long in2 = count(total, "input_tokens");
                    long cached2 = count(total, "cached_input_tokens");
                    long out2 = count(total, "output_tokens");
                    if ((in2 | cached2 | out2) < 0) {
                        skipped(file, n);
                        continue;
                    }
                    long dCached = Math.max(0, cached2 - cached);
                    long dInput = Math.max(0, (in2 - input) - dCached);
                    long dOutput = Math.max(0, out2 - output);
                    input = Math.max(input, in2);
                    cached = Math.max(cached, cached2);
                    output = Math.max(output, out2);
                    if (dInput + dCached + dOutput > 0) {
                        entries.add(new Entry(at, model, new AgentTokens.Counts(dInput, 0, dCached, dOutput)));
                    }
                }
            }
        }
        return new Parsed(size, modified, cwd, List.copyOf(entries));
    }

    static BufferedReader reader(Path file) throws IOException {
        // A malformed byte reads as U+FFFD rather than failing the file.
        return new BufferedReader(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8));
    }

    /** Line {@code n} parsed, or null (logged by position only) when it is not JSON. */
    static @Nullable Object json(Path file, int n, String line) {
        try {
            return MiniJson.parseRelaxed(line);
        } catch (RuntimeException e) {
            skipped(file, n);
            return null;
        }
    }

    /** A usage field as a whole number of tokens: 0 when absent, -1 when it is anything else. */
    static long count(@Nullable Object usage, String field) {
        Object v = MiniJson.get(usage, field);
        if (v == null) return 0;
        return v instanceof Long l && l >= 0 ? l : -1;
    }

    /** An ISO-8601 instant as epoch millis, or -1. */
    static long millis(@Nullable Object raw) {
        if (!(raw instanceof String s)) return -1;
        try {
            return Instant.parse(s).toEpochMilli();
        } catch (DateTimeParseException e) {
            return -1;
        }
    }

    static void skipped(Path file, int n) {
        Log.debug("agent usage: " + file + ":" + n + ": not a usage record, skipped");
    }
}
