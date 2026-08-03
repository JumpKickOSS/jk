// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.discovery.ProbeSupport;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
 * <p>Best-effort: IO failures are swallowed. A missed touch only weakens MRU / usage signal.
 */
public final class JdkAccessLedger {

    /** Default file name inside the jdks directory. */
    public static final String FILE_NAME = ".jk-access.log";

    private static final char SEP = '|';

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
            Map<String, Entry> rows = load();
            Entry prev = rows.get(homeKey);
            int count = prev == null ? 1 : prev.accessCount() + 1;
            rows.put(
                    homeKey,
                    new Entry(System.currentTimeMillis(), count, ver, ven, Path.of(homeKey)));
            writeAll(rows);
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
        ProbeSupport.discoverJdk(jdk.home(), "jk")
                .ifPresentOrElse(this::touch, () -> touch(jdk.home(), "", ""));
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

    private static String normalizeHome(Path javaHome) {
        if (javaHome == null) return "";
        return javaHome.toAbsolutePath().normalize().toString();
    }

    /** Drop {@code |} so a field cannot shift columns; other chars pass through. */
    private static String sanitizeField(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.indexOf(SEP) < 0 ? s : s.replace(SEP, '/');
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
