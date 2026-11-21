// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.Settled;
import cc.jumpkick.util.MinimalToml;
import java.io.BufferedReader;
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
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Line scanner for a handful of flat TOML scalars on hot, engine-free paths — not a full parser.
 * Tracks {@code [section]} headers (quoted parts unquoted), reads an inline table's keys as {@code
 * key.sub}, and steps over a value that runs over lines (a multi-line string or array) whole, so
 * none of its lines reads as a key or a header. Exotic TOML for a wanted key (a multi-line string,
 * a nested array) reads as absent, never a wrong value. Stops early once every requested key is
 * found.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class TomlScan {

    private final Map<String, String> values;
    private final Map<String, List<String>> arrays;
    private final Set<String> sections;

    /**
     * Files either memo holds before it starts over: a bound above any one workspace's manifests,
     * so a scan never evicts what its own build reads, and {@link #dropMemos} for the idle engine.
     */
    private static final int FILE_MEMO_ENTRIES = 4_096;

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
     * <p>A file that is not yet {@link Settled} bypasses the memo entirely. Size+mtime cannot see a
     * same-length edit inside one coarse mtime tick, and a config file being edited is exactly the
     * case where that matters; the extra read costs one file for a couple of seconds. Same rule, same
     * constant, as {@code JkBuildParser}'s manifest stamp.
     */
    private static final StampedMemo<Path, StampedMemo.FileStamp, List<String>> LINES =
            StampedMemo.bounded(FILE_MEMO_ENTRIES);

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
     * As {@link #scan}, but reads and remembers only the file's head: the lines up to the first
     * {@code [[array-of-tables]]} header. Only for files whose wanted scalars all precede the array
     * tables <em>by construction</em> — jk-lock.toml, whose writer emits the toolchain tables and
     * top-level scalars before {@code [[artifact]]} — so a head scalar of a megabyte lock costs a
     * few lines read and a few lines kept, not the whole file memoized for a value in its first ten.
     * User-authored TOML carries no such ordering; use {@link #scan}.
     */
    public static TomlScan scanScalarHead(Path file, String... keys) {
        return scan(file, true, keys);
    }

    /** The head of each file scanned for its head scalars, stamped like {@link #LINES}; see {@link #scanScalarHead}. */
    private static final StampedMemo<Path, StampedMemo.FileStamp, List<String>> HEADS =
            StampedMemo.bounded(FILE_MEMO_ENTRIES);

    /**
     * {@code file}'s lines, from the memo when its stamp still matches. Empty for an absent or
     * unreadable file — every lookup then reads as absent, exactly as the tolerant full readers do.
     */
    private static List<String> lines(Path file, boolean headOnly) {
        SCANS.incrementAndGet();
        Path key = file.toAbsolutePath().normalize();
        StampedMemo.FileStamp stamp = StampedMemo.FileStamp.of(key);
        if (stamp == null) return List.of();
        List<String> hit =
                headOnly ? HEADS.get(key, stamp, () -> readHead(key)) : LINES.get(key, stamp, () -> read(key));
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

    /** Test seam: lines read from disk since the last {@link #clearCache()}; a head read stops at the first array table. */
    public static long linesRead() {
        return LINES_READ.get();
    }

    private static final AtomicLong LINES_READ = new AtomicLong();

    private static List<String> read(Path file) {
        READS.incrementAndGet();
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            LINES_READ.addAndGet(lines.size());
            return lines;
        } catch (IOException unreadable) {
            return List.of();
        }
    }

    /** The lines up to and including the first {@code [[array-of-tables]]} header; the whole file when it has none. */
    private static List<String> readHead(Path file) {
        READS.incrementAndGet();
        List<String> head = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                head.add(line);
                LINES_READ.incrementAndGet();
                if (line.strip().startsWith("[[")) break;
            }
        } catch (IOException unreadable) {
            return List.of();
        }
        return head;
    }

    /** Drop {@code file}'s cached lines, for a writer that has just rewritten it. */
    public static void forget(Path file) {
        Path key = file.toAbsolutePath().normalize();
        LINES.forget(key);
        HEADS.forget(key);
    }

    /**
     * Drop every memoized file — whole and head — and return how many entries went; the counters
     * stay. The engine calls this once it has been idle a while.
     */
    public static int dropMemos() {
        return LINES.clear() + HEADS.clear();
    }

    /** As {@link #dropMemos}, keeping every file under {@code root}: the idle trim's variant for the workspace built last. */
    public static int dropMemosOutside(Path root) {
        Path keep = root.toAbsolutePath().normalize();
        return LINES.retain(key -> key.startsWith(keep)) + HEADS.retain(key -> key.startsWith(keep));
    }

    /** Test seam: drop every cached file. */
    public static void clearCache() {
        LINES.clear();
        HEADS.clear();
        SCANS.set(0);
        READS.set(0);
        LINES_READ.set(0);
    }

    private static TomlScan scan(Path file, boolean stopAtArrayTable, String... keys) {
        Map<String, String> values = new HashMap<>();
        Map<String, List<String>> arrays = new HashMap<>();
        Set<String> sections = new HashSet<>();
        Set<String> wanted = Set.of(keys);
        List<String> body = lines(file, stopAtArrayTable);
        // Null under a header this scanner cannot spell: its keys are read past, never as another table's.
        @Nullable String section = "";
        boolean inArrayTable = false;
        for (int n = 0; n < body.size(); n++) {
            String line = body.get(n).strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[")) {
                boolean arrayTable = line.startsWith("[[");
                section = header(line, arrayTable);
                if (section != null) sections.add(section);
                if (arrayTable && stopAtArrayTable) break;
                inArrayTable = arrayTable;
                continue;
            }
            int eq = equalsOutsideQuotes(line);
            if (eq <= 0) continue;
            // A value may run over lines (a multi-line string, array or table): take all of it, so
            // none of its lines is read as a key or a header.
            String rest = line.substring(eq + 1).strip();
            StringBuilder value = new StringBuilder(rest);
            ValueExtent extent = new ValueExtent();
            boolean ended = extent.feed(rest);
            while (!ended && n + 1 < body.size()) {
                String next = body.get(++n);
                value.append('\n').append(next);
                ended = extent.feed(next);
            }
            // Keys inside an [[…]] body are per-element values, not the flat scalars this
            // scanner serves — a same-named key there must not satisfy a wanted lookup.
            String key = dottedKey(line.substring(0, eq));
            if (inArrayTable || section == null || key == null) continue;
            String qualified = section.isEmpty() ? key : section + "." + key;
            String text = value.toString();
            // An inline table (jdk = { vendor = "corretto", required = true }): each of its keys
            // reads as "key.sub", the way the same keys under a [key] header do.
            if (text.startsWith("{") && wantsUnder(wanted, qualified)) {
                for (Map.Entry<String, String> e : inlineTable(text).entrySet()) {
                    String sub = qualified + "." + e.getKey();
                    if (wanted.contains(sub) && !values.containsKey(sub)) values.put(sub, e.getValue());
                }
            } else if (!wanted.contains(qualified) || values.containsKey(qualified) || arrays.containsKey(qualified)) {
                continue;
            } else if (text.startsWith("[")) {
                List<String> elements = arrayElements(text);
                if (elements != null) arrays.put(qualified, elements);
            } else if (!multilineString(text)) {
                values.put(qualified, scalar(text));
            }
            if (values.size() + arrays.size() == wanted.size()) break; // all found — stop reading
        }
        return new TomlScan(values, arrays, sections);
    }

    private static boolean wantsUnder(Set<String> wanted, String key) {
        for (String w : wanted) if (w.startsWith(key + ".")) return true;
        return false;
    }

    /**
     * The dotted table name of a {@code [header]} or {@code [[header]]} line, quoted parts unquoted;
     * null for a header this scanner cannot spell.
     */
    private static @Nullable String header(String line, boolean arrayTable) {
        int open = arrayTable ? 2 : 1;
        int i = open;
        while (i < line.length() && line.charAt(i) != ']') {
            char c = line.charAt(i);
            i = c == '"' || c == '\'' ? afterQuoted(line, i) : i + 1;
            if (i < 0) return null;
        }
        if (i >= line.length() || arrayTable && !line.startsWith("]]", i)) return null;
        return dottedKey(line.substring(open, i));
    }

    /**
     * A key as written ({@code a}, {@code a.b}, {@code "a".'b'}) in its dotted bare spelling; null
     * when it is empty, malformed, or a quoted part holds a {@code .} and would read as two parts.
     */
    static @Nullable String dottedKey(String raw) {
        List<String> parts = new ArrayList<>();
        int i = 0;
        while (true) {
            while (i < raw.length() && isBlank(raw.charAt(i))) i++;
            if (i >= raw.length()) return null;
            char c = raw.charAt(i);
            String part;
            if (c == '"' || c == '\'') {
                int end = afterQuoted(raw, i);
                if (end < 0) return null;
                part = scalar(raw.substring(i, end));
                if (part.isEmpty() || part.indexOf('.') >= 0) return null;
                i = end;
            } else {
                int start = i;
                while (i < raw.length() && isBareKeyChar(raw.charAt(i))) i++;
                if (i == start) return null;
                part = raw.substring(start, i);
            }
            parts.add(part);
            while (i < raw.length() && isBlank(raw.charAt(i))) i++;
            if (i == raw.length()) return String.join(".", parts);
            if (raw.charAt(i) != '.') return null;
            i++;
        }
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t';
    }

    private static boolean isBareKeyChar(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-';
    }

    /** The index just past the one-line string opening at {@code at}; -1 when it does not close on the line. */
    private static int afterQuoted(String s, int at) {
        char quote = s.charAt(at);
        for (int i = at + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && quote == '"') i++;
            else if (c == quote) return i + 1;
        }
        return -1;
    }

    /** The index of the first {@code =} outside a quoted key part; -1 when there is none. */
    private static int equalsOutsideQuotes(String line) {
        for (int i = 0; i < line.length(); ) {
            char c = line.charAt(i);
            if (c == '=') return i;
            if (c == '"' || c == '\'') {
                i = afterQuoted(line, i);
                if (i < 0) return -1;
            } else {
                i++;
            }
        }
        return -1;
    }

    private static boolean multilineString(String value) {
        return value.startsWith("\"\"\"") || value.startsWith("'''");
    }

    /**
     * Where a value ends, fed one line at a time: open brackets and braces, and a multi-line
     * string, carry over to the next line; one-line strings and comments do not.
     */
    private static final class ValueExtent {
        private int depth;
        private @Nullable String multiline;

        /** True when the value is complete at the end of {@code line}. */
        boolean feed(String line) {
            int i = 0;
            while (i < line.length()) {
                if (multiline != null) {
                    int close = closingDelimiter(line, i, multiline);
                    if (close < 0) return false;
                    i = close + 3;
                    while (i < line.length() && line.charAt(i) == multiline.charAt(0)) i++;
                    multiline = null;
                    continue;
                }
                char c = line.charAt(i);
                if (c == '#') break;
                if (line.startsWith("\"\"\"", i) || line.startsWith("'''", i)) {
                    multiline = line.substring(i, i + 3);
                    i += 3;
                } else if (c == '"' || c == '\'') {
                    int end = afterQuoted(line, i);
                    i = end < 0 ? line.length() : end;
                } else {
                    if (c == '[' || c == '{') depth++;
                    else if ((c == ']' || c == '}') && depth > 0) depth--;
                    i++;
                }
            }
            return depth == 0 && multiline == null;
        }

        private static int closingDelimiter(String line, int from, String delimiter) {
            if (delimiter.charAt(0) == '\'') return line.indexOf(delimiter, from);
            for (int i = from; i < line.length(); i++) {
                if (line.charAt(i) == '\\') i++;
                else if (line.startsWith(delimiter, i)) return i;
            }
            return -1;
        }
    }

    /**
     * The scalar entries of an inline table {@code { a = "x", b = 2, c = true }}, values as {@link
     * #scalar} reads them, up to its own closing brace; empty for one that does not close or nests
     * a table or an array (exotic, so absent).
     */
    static Map<String, String> inlineTable(String text) {
        Map<String, String> out = new HashMap<>();
        if (!text.startsWith("{")) return out;
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                int end = afterQuoted(text, i);
                if (end < 0) return out;
                cur.append(text, i, end);
                i = end - 1;
            } else if (c == '}') {
                parts.add(cur.toString());
                for (String part : parts) {
                    int eq = equalsOutsideQuotes(part);
                    if (eq <= 0) continue;
                    String key = dottedKey(part.substring(0, eq));
                    String value = part.substring(eq + 1).strip();
                    if (key != null && !multilineString(value)) out.put(key, scalar(value));
                }
                return out;
            } else if (c == '{' || c == '[' || c == '#') {
                return out; // nested or commented: exotic, reads as absent
            } else if (c == ',') {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        return out;
    }

    /**
     * The elements of a string array, basic ({@code "…"}) and literal ({@code '…'}), over as many
     * lines as it runs; other scalars are skipped and an unquoted {@code #} starts a comment that
     * runs to the end of its line. Null for an array that does not close or holds an array, a table
     * or a multi-line string (exotic, so absent).
     */
    private static @Nullable List<String> arrayElements(String text) {
        List<String> elements = new ArrayList<>();
        int i = 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == ']') return elements;
            if (c == '[' || c == '{' || text.startsWith("\"\"\"", i) || text.startsWith("'''", i)) return null;
            if (c == '#') {
                int eol = text.indexOf('\n', i);
                if (eol < 0) return null;
                i = eol + 1;
            } else if (c == '"' || c == '\'') {
                int end = afterQuoted(text, i);
                if (end < 0) return null;
                elements.add(scalar(text.substring(i, end)));
                i = end;
            } else {
                i++;
            }
        }
        return null;
    }

    /** The scanned value for {@code "section.key"} / {@code "key"}, or {@code null} when absent. */
    public @Nullable String get(String qualifiedKey) {
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
