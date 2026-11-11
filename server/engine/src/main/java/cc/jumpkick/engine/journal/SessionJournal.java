// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.OwnerOnlyFiles;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The session records beside the run records, one directory per session under {@code
 * <builds>/sessions/<id>/}: {@code session.json} is the session's small head (its record and the
 * inference state its next fact needs), replaced on every change; {@code episodes.jsonl} appends one
 * line per change to an episode, each carrying the whole episode, so the last line for an episode
 * id is the episode; {@code actions.jsonl} appends one line per action the session took. The
 * session log compacts both logs to the episodes it keeps ({@link #rewriteEpisodes}, {@link
 * #rewriteActions}), so no write grows with a session's age. An open MCP session's {@code connection.json} holds
 * the connection behind it, so a successor engine answers the same {@code Mcp-Session-Id}; it is
 * removed when the session ends. The shapes are the session log's; this class only
 * stores and reads them as {@link MiniJson} objects. Pruned with the runs, under the same {@code
 * [history]} budgets ({@link BuildJournal#prune}).
 *
 * <p>Beside the session directories, {@code <builds>/decisions/<id>.json} holds one decision of the
 * engine's queue each, written under the same one-writer rule. The queue bounds how many it keeps,
 * and a settled one is pruned with the sessions, under the same budgets.
 */
public final class SessionJournal {

    /** The directory under the builds root that holds one directory per session. */
    public static final String DIR = "sessions";

    static final String SESSION_JSON = "session.json";

    static final String ACTIONS_JSONL = "actions.jsonl";

    static final String EPISODES_JSONL = "episodes.jsonl";

    static final String CONNECTION_JSON = "connection.json";

    /** The directory beside {@link #DIR} that holds one {@code <id>.json} per decision. */
    public static final String DECISIONS_DIR = "decisions";

    /** A session id is one path segment of letters, digits, dot, dash and underscore. */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final Path root;
    private final Path decisions;

    public SessionJournal(Path root) {
        this.root = root.normalize();
        this.decisions = this.root.resolveSibling(DECISIONS_DIR);
    }

    /** The session records beside the runs of the journal under {@code buildsRoot}. */
    public static SessionJournal under(Path buildsRoot) {
        return new SessionJournal(buildsRoot.resolve(DIR));
    }

    /**
     * Every ended session under {@code buildsRoot} as a prune entry, made by {@code entry} from its
     * directory and when it last changed, its connection with it. A session still live or idle is
     * never prunable: the engine adopts every session a previous engine left open, and its sweep
     * ends the ones with no activity for the session expiry.
     */
    static <T> List<T> prunable(Path buildsRoot, long nowMillis, BiFunction<Path, Long, T> entry) {
        List<T> out = new ArrayList<>();
        for (Path dir : under(buildsRoot).dirs()) {
            if (ended(dir)) out.add(entry.apply(dir, millis(dir, nowMillis)));
        }
        return out;
    }

    /**
     * Every settled decision under {@code buildsRoot} as a prune entry, made by {@code entry} from
     * its file, when it last changed and its size. An open decision is prunable only once its deadline has
     * passed, which means the engine that held it stopped before it could settle it expired; until
     * then a successor may still adopt and answer it. A record that does not parse is prunable.
     */
    static <T> List<T> prunableDecisions(Path buildsRoot, long nowMillis, PruneEntry<T> entry) {
        Path dir = under(buildsRoot).decisions;
        List<T> out = new ArrayList<>();
        try {
            PathUtil.forEachChild(dir, (p, attrs) -> {
                if (attrs.isRegularFile() && p.getFileName().toString().endsWith(".json") && settled(p, nowMillis)) {
                    out.add(entry.of(p, attrs.lastModifiedTime().toMillis(), attrs.size()));
                }
                return true;
            });
        } catch (IOException e) {
            Log.debug("decisions unreadable: " + dir, e);
        }
        return out;
    }

    /** The id of the decision whose record is {@code file}. */
    static @Nullable String decisionId(Path file) {
        String name = file.getFileName().toString();
        String id = name.endsWith(".json") ? name.substring(0, name.length() - ".json".length()) : name;
        return validId(id) ? id : null;
    }

    /** Makes a prune entry from a path, when it last changed, and its size in bytes. */
    @FunctionalInterface
    interface PruneEntry<T> {
        T of(Path path, long millis, long size);
    }

    /** False only for a decision still open before its deadline. */
    private static boolean settled(Path file, long nowMillis) {
        return readJson(file)
                .map(doc -> !"open".equals(doc.get("state"))
                        || !(doc.get("deadline") instanceof Number deadline)
                        || nowMillis >= deadline.longValue())
                .orElse(true);
    }

    public Path root() {
        return root;
    }

    /** True when {@code id} can name a session directory. */
    public static boolean validId(@Nullable String id) {
        return id != null && ID.matcher(id).matches();
    }

    /** Replace session {@code id}'s {@code session.json} with {@code doc}. */
    public void write(String id, Map<String, Object> doc) throws IOException {
        Path dir = dirOf(id);
        OwnerOnlyFiles.createDirectories(dir);
        AtomicWrites.replace(dir.resolve(SESSION_JSON), MiniJson.write(doc) + "\n");
    }

    /** Replace session {@code id}'s {@code connection.json} with {@code doc}. */
    public void writeConnection(String id, Map<String, Object> doc) throws IOException {
        Path dir = dirOf(id);
        OwnerOnlyFiles.createDirectories(dir);
        AtomicWrites.replace(dir.resolve(CONNECTION_JSON), MiniJson.write(doc) + "\n");
    }

    /** Remove session {@code id}'s {@code connection.json}, when it has one. */
    public void deleteConnection(String id) throws IOException {
        Files.deleteIfExists(dirOf(id).resolve(CONNECTION_JSON));
    }

    /** Session {@code id}'s connection, or empty when it has none or it does not parse. */
    public Optional<Map<String, Object>> connection(String id) {
        if (!validId(id)) return Optional.empty();
        return readJson(root.resolve(id).resolve(CONNECTION_JSON));
    }

    /** Replace decision {@code id}'s record with {@code doc}. */
    public void writeDecision(String id, Map<String, Object> doc) throws IOException {
        if (!validId(id)) throw new IllegalArgumentException("invalid decision id: " + id);
        OwnerOnlyFiles.createDirectories(decisions);
        AtomicWrites.replace(decisions.resolve(id + ".json"), MiniJson.write(doc) + "\n");
    }

    /** Remove decision {@code id}'s record, when there is one. */
    public void deleteDecision(String id) throws IOException {
        if (validId(id)) Files.deleteIfExists(decisions.resolve(id + ".json"));
    }

    /** Every stored decision record that parses, in no particular order. */
    public List<Map<String, Object>> decisions() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            PathUtil.forEachChild(decisions, (p, attrs) -> {
                if (attrs.isRegularFile() && p.getFileName().toString().endsWith(".json"))
                    readJson(p).ifPresent(out::add);
                return true;
            });
        } catch (IOException e) {
            Log.debug("decisions unreadable: " + decisions, e);
        }
        return out;
    }

    /** Append one action line to session {@code id}. */
    public void append(String id, Map<String, Object> action) throws IOException {
        appendLine(id, ACTIONS_JSONL, action);
    }

    /** Append one episode line to session {@code id}: it supersedes every earlier line for the same episode. */
    public void appendEpisode(String id, Map<String, Object> line) throws IOException {
        appendLine(id, EPISODES_JSONL, line);
    }

    /** Replace session {@code id}'s episode lines with {@code lines}, oldest first. */
    public void rewriteEpisodes(String id, List<Map<String, Object>> lines) throws IOException {
        rewrite(id, EPISODES_JSONL, lines);
    }

    /** Replace session {@code id}'s action lines with {@code lines}, oldest first. */
    public void rewriteActions(String id, List<Map<String, Object>> lines) throws IOException {
        rewrite(id, ACTIONS_JSONL, lines);
    }

    /**
     * Every episode line of session {@code id}, oldest first, superseded ones included; a line that
     * does not parse is skipped.
     */
    public List<Map<String, Object>> episodeLines(String id) {
        return lines(id, EPISODES_JSONL);
    }

    private void appendLine(String id, String file, Map<String, Object> line) throws IOException {
        Path dir = dirOf(id);
        OwnerOnlyFiles.createDirectories(dir);
        Files.writeString(
                dir.resolve(file),
                MiniJson.write(line) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE);
    }

    private void rewrite(String id, String file, List<Map<String, Object>> lines) throws IOException {
        Path dir = dirOf(id);
        OwnerOnlyFiles.createDirectories(dir);
        StringBuilder out = new StringBuilder();
        for (Map<String, Object> line : lines) out.append(MiniJson.write(line)).append('\n');
        AtomicWrites.replace(dir.resolve(file), out.toString());
    }

    /** Session {@code id}'s document, or empty when it has none or it does not parse. */
    public Optional<Map<String, Object>> read(String id) {
        if (!validId(id)) return Optional.empty();
        return readDoc(root.resolve(id));
    }

    /** Every stored session document, in no particular order. */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Path dir : dirs()) readDoc(dir).ifPresent(out::add);
        return out;
    }

    /** Every action line of session {@code id}, oldest first; a line that does not parse is skipped. */
    public List<Map<String, Object>> actions(String id) {
        return lines(id, ACTIONS_JSONL);
    }

    private List<Map<String, Object>> lines(String id, String name) {
        if (!validId(id)) return List.of();
        Path file = root.resolve(id).resolve(name);
        if (!Files.isRegularFile(file)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                if (parse(line) instanceof Map<?, ?> m) out.add(cast(m));
            }
        } catch (IOException e) {
            Log.debug("session lines unreadable: " + file, e);
        }
        return out;
    }

    /** Every session directory. */
    List<Path> dirs() {
        List<Path> out = new ArrayList<>();
        try {
            PathUtil.forEachChild(root, (p, attrs) -> {
                if (attrs.isDirectory() && validId(p.getFileName().toString())) out.add(p);
                return true;
            });
        } catch (IOException e) {
            Log.debug("sessions unreadable: " + root, e);
        }
        return out;
    }

    /** True when the session in {@code dir} has ended, so a prune may take it. */
    static boolean ended(Path dir) {
        return readDoc(dir)
                .map(doc -> MiniJson.get(doc, "session") instanceof Map<?, ?> s && "ended".equals(s.get("state")))
                .orElse(true);
    }

    /** When the session in {@code dir} last changed: its document's modification time. */
    static long millis(Path dir, long fallback) {
        try {
            Path doc = dir.resolve(SESSION_JSON);
            return Files.exists(doc) ? Files.getLastModifiedTime(doc).toMillis() : fallback;
        } catch (IOException e) {
            return fallback;
        }
    }

    private Path dirOf(String id) {
        if (!validId(id)) throw new IllegalArgumentException("invalid session id: " + id);
        return root.resolve(id);
    }

    private static Optional<Map<String, Object>> readDoc(Path dir) {
        return readJson(dir.resolve(SESSION_JSON));
    }

    private static Optional<Map<String, Object>> readJson(Path file) {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof Map<?, ?> m
                    ? Optional.of(cast(m))
                    : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static @Nullable Object parse(String json) {
        try {
            return MiniJson.parse(json);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }
}
