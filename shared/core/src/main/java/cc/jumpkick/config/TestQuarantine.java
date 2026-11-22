// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.util.MinimalToml;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * The test classes a workspace holds in quarantine, checked in as {@value #FILE} under its root. A
 * failure in a quarantined class does not fail the run; the class still runs. jk adds a class that
 * fails and then passes on a re-run with nothing changed as often as the root's {@code [test]
 * quarantine} says ({@link Policy}); removing its entry releases it.
 *
 * <p>Each entry is a {@code [[class]]} table: {@code name} (the top-level class, fully qualified),
 * {@code module} (the module's directory relative to the root, {@code .} for the root; absent
 * matches every module), {@code reason}, {@code since} (an ISO date) and an optional {@code issue}.
 */
public record TestQuarantine(List<Entry> entries) {

    /** The file's path under the workspace root, as messages name it. */
    public static final String FILE = ".jk/test/quarantine.toml";

    /** The empty quarantine. */
    public static final TestQuarantine NONE = new TestQuarantine(List.of());

    private static final String HEADER = """
            # Test classes in quarantine: a failure in one does not fail the run, and each still runs.
            # JumpKick adds a class that fails and then passes on a re-run with nothing changed as often
            # as [test] quarantine in jk.toml says. Delete an entry to release its class.
            """;

    public TestQuarantine {
        entries = List.copyOf(entries);
    }

    /** One quarantined class. {@code module} is empty when the entry holds the class in every module. */
    public record Entry(
            String className,
            String module,
            String reason,
            String since,
            @Nullable String issue) {

        /** Whether this entry holds top-level class {@code className} of the module at {@code module}. */
        public boolean holds(String module, String className) {
            return this.className.equals(className) && (this.module.isEmpty() || this.module.equals(module));
        }
    }

    /** Whether {@code name} names {@code className}: the fully qualified name, or its simple name. */
    public static boolean names(String name, String className) {
        return className.equals(name) || className.endsWith("." + name);
    }

    /**
     * When jk quarantines a class on its own: one that passed on a re-run {@code flakes} times in its
     * last {@code runs} runs. {@code flakes} of 0 is never.
     */
    public record Policy(int flakes, int runs) {

        /** Two re-run passes in a class's last twenty runs. */
        public static final Policy DEFAULT = new Policy(2, 20);

        /** jk quarantines nothing on its own. */
        public static final Policy OFF = new Policy(0, DEFAULT.runs());

        public boolean off() {
            return flakes <= 0;
        }

        /** The root's {@code [test] quarantine}: {@code false}, or {@code { flakes = 2, runs = 20 }}. */
        public static Policy of(Path root) {
            TomlScan scan = TomlScan.scan(
                    ManifestPaths.manifestIn(root),
                    "test.quarantine",
                    "test.quarantine.flakes",
                    "test.quarantine.runs");
            if (!EnvValues.parseBool(scan.get("test.quarantine")).orElse(true)) return OFF;
            int flakes = scan.getInt("test.quarantine.flakes", DEFAULT.flakes());
            int runs = scan.getInt("test.quarantine.runs", DEFAULT.runs());
            return flakes < 1 || runs < flakes ? DEFAULT : new Policy(flakes, runs);
        }
    }

    /** The module key an entry names for {@code moduleDir} under {@code root}: forward slashes, {@code .} for the root. */
    public static String moduleKey(Path root, Path moduleDir) {
        Path rel = root.toAbsolutePath()
                .normalize()
                .relativize(moduleDir.toAbsolutePath().normalize());
        String key = rel.toString().replace('\\', '/');
        return key.isEmpty() ? "." : key;
    }

    /** The quarantine file of the workspace at {@code root}. */
    public static Path file(Path root) {
        return root.resolve(".jk").resolve("test").resolve("quarantine.toml");
    }

    /** The quarantine checked in at {@code root}; {@link #NONE} when there is no file. */
    public static TestQuarantine read(Path root) throws IOException {
        Path file = file(root);
        if (!Files.isRegularFile(file)) return NONE;
        return parse(Files.readString(file));
    }

    /** {@code text} as a quarantine file; a malformed one is an {@link IllegalArgumentException} naming the fault. */
    static TestQuarantine parse(String text) {
        TomlParseResult toml = Toml.parse(text);
        if (toml.hasErrors())
            throw new IllegalArgumentException(
                    FILE + ": " + toml.errors().getFirst().toString());
        TomlArray classes = toml.getArrayOrEmpty("class");
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < classes.size(); i++) {
            if (!(classes.get(i) instanceof TomlTable t)) {
                throw new IllegalArgumentException(FILE + ": each [[class]] must be a table");
            }
            String name = t.getString("name");
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException(FILE + ": a [[class]] has no name");
            }
            out.add(new Entry(
                    name.trim(),
                    text(t, "module"),
                    text(t, "reason"),
                    t.isLocalDate("since") ? String.valueOf(t.getLocalDate("since")) : text(t, "since"),
                    t.getString("issue")));
        }
        return new TestQuarantine(out);
    }

    private static String text(TomlTable t, String key) {
        String v = t.getString(key);
        return v == null ? "" : v.trim();
    }

    /** The entry that holds top-level class {@code className} of {@code module}, or {@code null}. */
    public @Nullable Entry find(String module, String className) {
        for (Entry e : entries) if (e.holds(module, className)) return e;
        return null;
    }

    /** The top-level class names this quarantine holds, each once, sorted. */
    public List<String> classNames() {
        return entries.stream().map(Entry::className).distinct().sorted().toList();
    }

    /** This quarantine with {@code entry} added, the entries sorted by module then class. */
    public TestQuarantine with(Entry entry) {
        List<Entry> out = new ArrayList<>(entries);
        out.add(entry);
        out.sort(Comparator.comparing(Entry::module).thenComparing(Entry::className));
        return new TestQuarantine(out);
    }

    /** The file's text: the header, then one {@code [[class]]} table per entry. */
    public String render() {
        StringBuilder sb = new StringBuilder(HEADER);
        for (Entry e : entries) {
            sb.append("\n[[class]]\n");
            sb.append("name = ").append(MinimalToml.quote(e.className())).append('\n');
            if (!e.module().isEmpty())
                sb.append("module = ").append(MinimalToml.quote(e.module())).append('\n');
            if (!e.reason().isEmpty())
                sb.append("reason = ").append(MinimalToml.quote(e.reason())).append('\n');
            if (!e.since().isEmpty())
                sb.append("since = ").append(date(e.since())).append('\n');
            if (e.issue() != null && !e.issue().isBlank())
                sb.append("issue = ").append(MinimalToml.quote(e.issue())).append('\n');
        }
        return sb.toString();
    }

    /** {@code since} as a TOML date when it is one, else as a string. */
    private static String date(String since) {
        return since.matches("\\d{4}-\\d{2}-\\d{2}") ? since : MinimalToml.quote(since);
    }

    /** Write this quarantine to {@code root}'s {@value #FILE}, replacing it whole; no entries deletes it. */
    public void write(Path root) throws IOException {
        Path file = file(root);
        if (entries.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        Files.createDirectories(file.getParent());
        AtomicWrites.replace(file, render());
    }

    /** Held while this process reads and rewrites a quarantine file. */
    private static final Object EDITS = new Object();

    /** Read {@code root}'s quarantine, apply {@code edit} and write the result when it changed. */
    public static TestQuarantine edit(Path root, UnaryOperator<TestQuarantine> edit) throws IOException {
        synchronized (EDITS) {
            TestQuarantine before = read(root);
            TestQuarantine after = edit.apply(before);
            if (!after.equals(before)) after.write(root);
            return after;
        }
    }

    /**
     * Release the class {@code name} names ({@link #names}) from {@code root}'s quarantine, in every
     * module, and answer the entries released; none when it holds no such class. A simple name that
     * names two classes releases nothing and is an {@link IllegalArgumentException} naming both.
     * Its histories under {@code stateDir} are kept and marked released ({@link TestHistory#release}).
     */
    public static List<Entry> release(Path stateDir, Path root, String name) throws IOException {
        List<Entry> released = new ArrayList<>();
        edit(root, q -> {
            List<Entry> named =
                    q.entries.stream().filter(e -> names(name, e.className())).toList();
            List<String> classes =
                    named.stream().map(Entry::className).distinct().toList();
            if (classes.size() > 1)
                throw new IllegalArgumentException(name + " names several classes: " + String.join(", ", classes));
            released.addAll(named);
            List<Entry> kept = new ArrayList<>(q.entries);
            kept.removeAll(named);
            return new TestQuarantine(kept);
        });
        if (!released.isEmpty()) TestHistory.release(stateDir, root, released);
        return released;
    }
}
