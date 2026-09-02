// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.util.MinimalToml;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * Line scanner for a handful of flat TOML scalars on hot, engine-free paths — not a full parser.
 * Tracks {@code [section]} headers; exotic TOML for a wanted key reads as absent, never a wrong
 * value. Stops early once every requested key is found.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class TomlScan {

    private final Map<String, String> values;
    private final Map<String, List<String>> arrays;
    private final Set<String> sections;

    private static final Pattern QUOTED = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    /**
     * The lines of each scanned file, stamped on {@code (size, mtime)}.
     *
     * <p>Memoized here rather than at each caller because there are twenty-eight of them and only one
     * had a memo. Seven ask the <em>same</em> file the <em>same</em> question ({@code
     * workspace.modules}); {@code JkM2Config.resolve} is reached on per-artifact paths, so a
     * 500-artifact sync re-read {@code ~/.jk/config.toml} over a thousand times. Caching the
     * lines rather than the scan result means every key-set shares one read — the scan itself is a
     * line walk with an early exit, and never was the cost.
     *
     * <p>A file touched within {@link #SETTLE_MS} bypasses the memo entirely. Size+mtime cannot see a
     * same-length edit inside one coarse mtime tick, and a config file being edited is exactly the
     * case where that matters; the extra read costs one file for a couple of seconds. Same rule, same
     * constant, as {@code JkBuildParser}'s manifest stamp.
     */
    private static final StampedMemo<Path, StampedMemo.FileStamp, List<String>> LINES = StampedMemo.create();

    /** Distrust {@code (size, mtime)} for a file modified within this window. */
    private static final long SETTLE_MS = 2_000;

    /**
     * Scan {@code file} for {@code keys}, each spelled {@code "section.key"} (or just
     * {@code "key"} for top-level). Missing file → an empty result (every lookup absent).
     * {@code [[array-of-tables]]} bodies are skipped, not read as scalars — and scanning
     * continues past them, since TOML imposes no section ordering.
     */
    public static TomlScan scan(Path file, String... keys) {
        return scan(file, false, keys);
    }

    /**
     * As {@link #scan}, but stops at the first {@code [[array-of-tables]]} header. Only for files
     * whose wanted scalars all precede the array tables <em>by construction</em> — jk-lock.toml,
     * whose writer emits the toolchain tables and top-level scalars before {@code [[artifact]]} —
     * so a missing optional key does not read thousands of artifact lines on a hot path.
     * User-authored TOML carries no such ordering; use {@link #scan}.
     */
    public static TomlScan scanScalarHead(Path file, String... keys) {
        return scan(file, true, keys);
    }

    /**
     * {@code file}'s lines, from the memo when its stamp still matches. Empty for an absent or
     * unreadable file — every lookup then reads as absent, exactly as the tolerant full readers do.
     */
    private static List<String> lines(Path file) {
        SCANS.incrementAndGet();
        Path key = file.toAbsolutePath().normalize();
        StampedMemo.FileStamp stamp = StampedMemo.FileStamp.of(key);
        if (stamp == null) return List.of();
        if (System.currentTimeMillis() - stamp.modified().toMillis() < SETTLE_MS) return read(key);
        List<String> hit = LINES.get(key, stamp, () -> read(key));
        return hit == null ? List.of() : hit;
    }

    private static final AtomicLong SCANS = new AtomicLong();

    private static final AtomicLong READS = new AtomicLong();

    /** Test seam: scans requested since the last {@link #clearCache()}. */
    public static long scans() {
        return SCANS.get();
    }

    /**
     * Test seam: scans that had to read the file.
     *
     * <p>The ratio is the property: a memo that returns the right values while re-reading the file
     * every time passes every correctness test there is.
     */
    public static long reads() {
        return READS.get();
    }

    private static List<String> read(Path file) {
        READS.incrementAndGet();
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return List.of();
        }
    }

    /** Drop {@code file}'s cached lines, for a writer that has just rewritten it. */
    public static void forget(Path file) {
        LINES.forget(file.toAbsolutePath().normalize());
    }

    /** Test seam: drop every cached file. */
    public static void clearCache() {
        LINES.clear();
        SCANS.set(0);
        READS.set(0);
    }

    private static TomlScan scan(Path file, boolean stopAtArrayTable, String... keys) {
        Map<String, String> values = new HashMap<>();
        Map<String, List<String>> arrays = new HashMap<>();
        Set<String> sections = new HashSet<>();
        Set<String> wanted = Set.of(keys);
        List<String> body = lines(file);
        if (body.isEmpty()) return new TomlScan(values, arrays, sections);
        {
            String section = "";
            boolean inArrayTable = false;
            String arrayKey = null; // a wanted key whose `[ … ]` array spans lines
            for (String raw : body) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (arrayKey != null) {
                    // Inside a multi-line string array: collect quoted elements until the
                    // `]` outside quotes that closes it (IPv6-style values carry `]` inside).
                    if (collectArrayLine(line, arrays.get(arrayKey))) arrayKey = null;
                    continue;
                }
                if (line.startsWith("[")) {
                    int close = line.indexOf(']');
                    if (close > 1) {
                        boolean arrayTable = line.startsWith("[[");
                        section = line.substring(arrayTable ? 2 : 1, close)
                                .replace("]", "")
                                .strip();
                        sections.add(section);
                        if (arrayTable && stopAtArrayTable) break;
                        inArrayTable = arrayTable;
                    }
                    continue;
                }
                // Keys inside an [[…]] body are per-element values, not the flat scalars this
                // scanner serves — a same-named key there must not satisfy a wanted lookup.
                if (inArrayTable) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).strip();
                String qualified = section.isEmpty() ? key : section + "." + key;
                if (!wanted.contains(qualified)) continue;
                String rest = line.substring(eq + 1).strip();
                if (rest.startsWith("[")) {
                    if (arrays.containsKey(qualified)) continue;
                    List<String> elements = new ArrayList<>();
                    arrays.put(qualified, elements);
                    if (!collectArrayLine(rest.substring(1), elements)) arrayKey = qualified;
                    continue;
                }
                if (values.containsKey(qualified)) continue;
                values.put(qualified, scalar(rest));
                if (values.size() == wanted.size()) break; // all found — stop reading
            }
        }
        return new TomlScan(values, arrays, sections);
    }

    /** Collect quoted elements from one array line; true when the closing {@code ]} was seen. */
    private static boolean collectArrayLine(String line, List<String> elements) {
        int close = line.indexOf(']', line.lastIndexOf('"') + 1);
        String body = close >= 0 ? line.substring(0, close) : line;
        Matcher m = QUOTED.matcher(body);
        while (m.find()) {
            elements.add(m.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
        }
        return close >= 0;
    }

    /** The scanned value for {@code "section.key"} / {@code "key"}, or {@code null} when absent. */
    public String get(String qualifiedKey) {
        return values.get(qualifiedKey);
    }

    /** True when the scalar or array key was present (including an explicit empty array). */
    public boolean hasKey(String qualifiedKey) {
        return values.containsKey(qualifiedKey) || arrays.containsKey(qualifiedKey);
    }

    /** As {@link #get}, parsed as an int; {@code fallback} when absent or non-numeric. */
    public int getInt(String qualifiedKey, int fallback) {
        String v = values.get(qualifiedKey);
        if (v == null) return fallback;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * The scanned string-array value for {@code "section.key"}, or an empty list when absent or
     * not an array. Single-line and one-element-per-line forms both read; non-string elements and
     * exotic TOML degrade to absent elements, never wrong values.
     */
    public List<String> stringArray(String qualifiedKey) {
        List<String> v = arrays.get(qualifiedKey);
        return v == null ? List.of() : List.copyOf(v);
    }

    /** True when a {@code [section]} (or {@code [[section]]}) header was seen at all. */
    public boolean hasSection(String section) {
        return sections.contains(section);
    }

    /**
     * Strip quotes from a scalar and decode basic-string escapes (a Windows path written by
     * {@link cc.jumpkick.util.MinimalToml#quote} reads back with single backslashes); drop a
     * trailing same-line comment on unquoted values.
     */
    static String scalar(String v) {
        return MinimalToml.unquote(v);
    }
}
