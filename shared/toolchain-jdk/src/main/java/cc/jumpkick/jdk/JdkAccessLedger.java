// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.discovery.ProbeSupport;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * One-line-per-JDK access ledger at {@code $JK_JDKS_DIR/.jk-access.log} (default {@code
 * ~/.jdks/.jk-access.log}). Pipe-separated fields:
 *
 * <pre>
 * timestampInMillis|accessCount|version|vendor|javaHome
 * </pre>
 *
 * <p>Each {@link #touch} reads the file, upserts the row for that {@code javaHome} (bumps {@code
 * accessCount}, refreshes timestamp / version / vendor), and rewrites the whole file. Line count
 * equals the number of distinct JDKs ever touched — it does not grow a history.
 *
 * <p>The load/upsert/rewrite cycle runs under an exclusive lock on a sibling {@code .lock} file
 * (CLI and engine both touch concurrently; an unlocked rewrite would drop the loser's row), plus a
 * per-file JVM mutex ({@link FileChannel#lock()} throws {@code OverlappingFileLockException} on
 * same-JVM overlap). The lock file is deliberately never deleted — removing a held lock file would
 * let a third process lock a fresh inode and race the current holder.
 *
 * <p>Rows key by the {@code toRealPath()} of {@code javaHome} (symlinked pointers and real homes
 * share one row); the vendor column always carries {@link JdkVendor#displayName()} form. A
 * pre-6878cdb2 {@code .access.log} TSV journal found next to the ledger is folded in (rows whose
 * identifier still names an installed directory) and removed on first touch.
 *
 * <p>Best-effort: IO failures are swallowed. A missed touch only weakens MRU / usage signal.
 */
public final class JdkAccessLedger {

    /** Default file name inside the jdks directory. */
    public static final String FILE_NAME = ".jk-access.log";

    /** Pre-rewrite journal file name (TSV, identifier-keyed); folded + removed on first touch. */
    static final String OLD_FILE_NAME = ".access.log";

    private static final char SEP = '|';

    private static final Pattern VERSION_SUFFIX = Pattern.compile(".*?-(\\d[\\d.]*)$");

    /** One mutex per ledger path — file locks are JVM-wide, so threads must serialize first. */
    private static final ConcurrentHashMap<String, Object> JVM_LOCKS = new ConcurrentHashMap<>();

    private final Path file;

    /**
     * Default-path constructor — writes under {@link JkDirs#jdksDir()}. Callers that need a custom
     * path (tests) use {@link #JdkAccessLedger(Path)}.
     */
    public static JdkAccessLedger atDefaultPath() {
        return new JdkAccessLedger(JkDirs.jdks().resolve(FILE_NAME));
    }

    public JdkAccessLedger(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /** Path to the ledger file (tests / diagnostics). */
    public Path file() {
        return file;
    }

    /**
     * Record that {@code javaHome} was accessed. Upserts by absolute normalized home path. Empty /
     * null home is ignored.
     */
    public void touch(Path javaHome, String version, String vendor) {
        if (javaHome == null) return;
        String homeKey = normalizeHome(javaHome);
        if (homeKey.isEmpty()) return;
        String ver = version == null ? "" : version;
        String ven = vendor == null ? "" : vendor;
        try {
            withExclusiveLock(() -> {
                Map<String, Entry> rows = load();
                foldOldJournal(rows);
                Entry prev = rows.get(homeKey);
                int count = prev == null ? 1 : prev.accessCount() + 1;
                rows.put(homeKey, new Entry(System.currentTimeMillis(), count, ver, ven, Path.of(homeKey)));
                writeAll(rows);
            });
        } catch (IOException ignored) {
            // Best-effort.
        }
    }

    /** Touch from a discovery hit (home / version / vendor already known). */
    public void touch(JdkHit hit) {
        if (hit == null) return;
        String vendor = hit.vendor() == null ? "" : hit.vendor().displayName();
        touch(hit.home(), hit.version(), vendor);
    }

    /**
     * Touch an installed JDK. Prefer {@link #touch(Path, String, String)} or {@link #touch(JdkHit)}
     * when version/vendor are already in hand; this path re-reads {@code release} via {@link
     * ProbeSupport#discoverJdk}.
     */
    public void touch(InstalledJdk jdk) {
        if (jdk == null || jdk.home() == null) return;
        ProbeSupport.discoverJdk(jdk.home(), "jk").ifPresentOrElse(this::touch, () -> touch(jdk.home(), "", ""));
    }

    /** All rows keyed by absolute javaHome string (iteration order = file order). */
    public Map<String, Entry> byJavaHome() throws IOException {
        return load();
    }

    /** Rows ordered most-recently-used first (highest timestamp first). */
    public List<Entry> mostRecentFirst() throws IOException {
        return load().values().stream()
                .sorted(Comparator.comparingLong(Entry::timestampMillis).reversed())
                .toList();
    }

    /**
     * Run {@code body} holding both the per-file JVM mutex and an exclusive {@link FileLock} on the
     * sibling {@code <ledger>.lock} file. The lock file is created if absent and intentionally left
     * on disk afterwards: deleting a lock file another process still holds would let a third
     * process lock a brand-new inode and bypass the mutual exclusion.
     */
    private void withExclusiveLock(IoRunnable body) throws IOException {
        Path lockFile = file.resolveSibling(file.getFileName() + ".lock");
        Object jvmLock =
                JVM_LOCKS.computeIfAbsent(lockFile.toAbsolutePath().normalize().toString(), k -> new Object());
        synchronized (jvmLock) {
            if (lockFile.getParent() != null) Files.createDirectories(lockFile.getParent());
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock lock = channel.lock()) {
                body.run();
            }
        }
    }

    /**
     * Fold the pre-rewrite {@code .access.log} TSV journal ({@code millis\tevent\tidentifier}) into
     * {@code rows}, then delete it. Best-effort: only identifiers that still name a directory next
     * to the ledger (the jdks dir) become rows — version parsed from the identifier's trailing
     * {@code -<digits>} suffix when present, vendor left blank (the next real touch refreshes
     * both). Rows already present win. Called under {@link #withExclusiveLock}.
     */
    private void foldOldJournal(Map<String, Entry> rows) {
        Path old = file.resolveSibling(OLD_FILE_NAME);
        if (!Files.isRegularFile(old)) return;
        try {
            Map<String, long[]> byIdentifier = new LinkedHashMap<>(); // id -> {latestMillis, count}
            for (String line : Files.readString(old, StandardCharsets.UTF_8).split("\n")) {
                String[] parts = line.split("\t", 3);
                if (parts.length != 3 || parts[2].isBlank()) continue;
                long millis;
                try {
                    millis = Long.parseLong(parts[0].trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                byIdentifier.merge(
                        parts[2].trim(), new long[] {millis, 1}, (a, b) -> new long[] {Math.max(a[0], b[0]), a[1] + 1});
            }
            for (Map.Entry<String, long[]> e : byIdentifier.entrySet()) {
                Path home = file.resolveSibling(e.getKey());
                if (!Files.isDirectory(home)) continue;
                String homeKey = normalizeHome(home);
                if (homeKey.isEmpty() || rows.containsKey(homeKey)) continue;
                var m = VERSION_SUFFIX.matcher(e.getKey());
                String version = m.matches() ? m.group(1) : "";
                rows.put(homeKey, new Entry(e.getValue()[0], (int) e.getValue()[1], version, "", Path.of(homeKey)));
            }
            Files.deleteIfExists(old);
        } catch (IOException ignored) {
            // Best-effort; the orphan is retried on the next touch.
        }
    }

    private Map<String, Entry> load() throws IOException {
        Map<String, Entry> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return out;
        String body = Files.readString(file, StandardCharsets.UTF_8);
        for (String line : body.split("\n")) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            Entry e = parseLine(line);
            if (e != null) out.put(normalizeHome(e.javaHome()), e);
        }
        return out;
    }

    private void writeAll(Map<String, Entry> rows) throws IOException {
        // Stable order by javaHome for readable diffs.
        List<Entry> ordered = new ArrayList<>(rows.values());
        ordered.sort(Comparator.comparing(e -> normalizeHome(e.javaHome())));
        StringBuilder sb = new StringBuilder();
        for (Entry e : ordered) {
            sb.append(e.timestampMillis())
                    .append(SEP)
                    .append(e.accessCount())
                    .append(SEP)
                    .append(sanitizeField(e.version()))
                    .append(SEP)
                    .append(sanitizeField(e.vendor()))
                    .append(SEP)
                    .append(normalizeHome(e.javaHome()))
                    .append('\n');
        }
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        AtomicWrites.replace(file, sb.toString());
    }

    /**
     * Parse {@code millis|count|version|vendor|javaHome}. Uses a split limit of 5 so {@code
     * javaHome} may contain {@code |} (unlikely) without losing the tail.
     */
    static Entry parseLine(String line) {
        if (line == null || line.isEmpty()) return null;
        String[] parts = line.split("\\|", 5);
        if (parts.length < 5) return null;
        long millis;
        int count;
        try {
            millis = Long.parseLong(parts[0].trim());
            count = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        String version = parts[2];
        String vendor = parts[3];
        String home = parts[4].trim();
        if (home.isEmpty()) return null;
        return new Entry(millis, count, version, vendor, Path.of(home));
    }

    /**
     * Canonical row key: {@code toRealPath()} when the home resolves (collapses jk's own
     * {@code <vendor>-<major>} symlink pointers onto the real install), else the absolute
     * normalized path. Every key — touch, load, write — goes through here, so a symlinked home and
     * its target share one row.
     */
    private static String normalizeHome(Path javaHome) {
        if (javaHome == null) return "";
        try {
            return javaHome.toRealPath().toString();
        } catch (IOException e) {
            return javaHome.toAbsolutePath().normalize().toString();
        }
    }

    /** Drop {@code |} so a field cannot shift columns; other chars pass through. */
    private static String sanitizeField(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.indexOf(SEP) < 0 ? s : s.replace(SEP, '/');
    }

    /** {@link Runnable} that may throw {@link IOException}; used by {@link #withExclusiveLock}. */
    @FunctionalInterface
    private interface IoRunnable {
        void run() throws IOException;
    }

    /**
     * One ledger row. {@code accessCount} is total touches for this {@code javaHome}; {@code
     * timestampMillis} is the latest touch.
     */
    public record Entry(long timestampMillis, int accessCount, String version, String vendor, Path javaHome) {
        public Entry {
            version = version == null ? "" : version;
            vendor = vendor == null ? "" : vendor;
            Objects.requireNonNull(javaHome, "javaHome");
        }
    }
}
