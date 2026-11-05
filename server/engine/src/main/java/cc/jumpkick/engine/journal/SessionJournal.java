// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.OwnerOnlyFiles;
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
 * The session records beside the run records: {@code <builds>/sessions/<id>/session.json} holds one
 * session and its episodes, rewritten whole on every change, and {@code actions.jsonl} beside it
 * appends one line per action the session took. An MCP session's {@code connection.json} holds the
 * connection behind it, so a successor engine answers the same {@code Mcp-Session-Id}. The shapes are the session log's; this class only
 * stores and reads them as {@link MiniJson} objects. Pruned with the runs, under the same {@code
 * [history]} budgets ({@link BuildJournal#prune}).
 */
public final class SessionJournal {

    /** The directory under the builds root that holds one directory per session. */
    public static final String DIR = "sessions";

    static final String SESSION_JSON = "session.json";

    static final String ACTIONS_JSONL = "actions.jsonl";

    static final String CONNECTION_JSON = "connection.json";

    /** A session id is one path segment of letters, digits, dot, dash and underscore. */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final Path root;

    public SessionJournal(Path root) {
        this.root = root.normalize();
    }

    /** The session records beside the runs of the journal under {@code buildsRoot}. */
    public static SessionJournal under(Path buildsRoot) {
        return new SessionJournal(buildsRoot.resolve(DIR));
    }

    /**
     * Every ended session under {@code buildsRoot} as a prune entry, made by {@code entry} from its
     * directory and when it last changed, its connection with it. A session still live or idle is
     * never prunable: the engine adopts every session a previous engine left open, and its idle
     * sweep ends the ones nobody returns to.
     */
    static <T> List<T> prunable(Path buildsRoot, long nowMillis, BiFunction<Path, Long, T> entry) {
        List<T> out = new ArrayList<>();
        for (Path dir : under(buildsRoot).dirs()) {
            if (ended(dir)) out.add(entry.apply(dir, millis(dir, nowMillis)));
        }
        return out;
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

    /** Session {@code id}'s connection, or empty when it has none or it does not parse. */
    public Optional<Map<String, Object>> connection(String id) {
        if (!validId(id)) return Optional.empty();
        return readJson(root.resolve(id).resolve(CONNECTION_JSON));
    }

    /** Append one action line to session {@code id}. */
    public void append(String id, Map<String, Object> action) throws IOException {
        Path dir = dirOf(id);
        OwnerOnlyFiles.createDirectories(dir);
        Files.writeString(
                dir.resolve(ACTIONS_JSONL),
                MiniJson.write(action) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE);
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
        if (!validId(id)) return List.of();
        Path file = root.resolve(id).resolve(ACTIONS_JSONL);
        if (!Files.isRegularFile(file)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                if (parse(line) instanceof Map<?, ?> m) out.add(cast(m));
            }
        } catch (IOException e) {
            Log.debug("session actions unreadable: " + file, e);
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
