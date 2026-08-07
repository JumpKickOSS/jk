// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Human-readable index of files under {@code state/aot/}: {@code aot.toml}. Opaque
 * {@code <tool>-<16hex>.aot} names are hashed from JDK + GC + classpath (workers) or engine jar +
 * JDK (engine); this file records the inputs so a human can tell which cache is which.
 *
 * <p>Best-effort only — never throws to callers. Concurrent writers take a sibling lock file.
 */
public final class AotManifest {

    public static final String FILE_NAME = "aot.toml";
    public static final int SCHEMA = 1;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);

    private AotManifest() {}

    /** {@code aotDir/aot.toml}. */
    public static Path path(Path aotDir) {
        return aotDir.resolve(FILE_NAME);
    }

    /**
     * One cache (or sticky failure marker). Required: {@link #file()}. Other fields are optional
     * and omitted from TOML when null/empty.
     */
    public record Entry(
            String file,
            String tool,
            String key,
            String status,
            Long sizeBytes,
            String jdkHome,
            String jdkVendor,
            String jdkVersion,
            String gc,
            List<String> classpath,
            List<String> jvmFlags,
            String jkVersion,
            String engineJar,
            Long engineJarSize,
            Long engineJarMtimeMs,
            String created,
            String lastUsed) {

        public Entry {
            Objects.requireNonNull(file, "file");
            classpath = classpath == null ? List.of() : List.copyOf(classpath);
            jvmFlags = jvmFlags == null ? List.of() : List.copyOf(jvmFlags);
        }

        public static Builder builder(String file) {
            return new Builder(file);
        }

        public Builder toBuilder() {
            return new Builder(file)
                    .tool(tool)
                    .key(key)
                    .status(status)
                    .sizeBytes(sizeBytes)
                    .jdkHome(jdkHome)
                    .jdkVendor(jdkVendor)
                    .jdkVersion(jdkVersion)
                    .gc(gc)
                    .classpath(classpath)
                    .jvmFlags(jvmFlags)
                    .jkVersion(jkVersion)
                    .engineJar(engineJar)
                    .engineJarSize(engineJarSize)
                    .engineJarMtimeMs(engineJarMtimeMs)
                    .created(created)
                    .lastUsed(lastUsed);
        }

        public static final class Builder {
            private final String file;
            private String tool;
            private String key;
            private String status;
            private Long sizeBytes;
            private String jdkHome;
            private String jdkVendor;
            private String jdkVersion;
            private String gc;
            private List<String> classpath = List.of();
            private List<String> jvmFlags = List.of();
            private String jkVersion;
            private String engineJar;
            private Long engineJarSize;
            private Long engineJarMtimeMs;
            private String created;
            private String lastUsed;

            private Builder(String file) {
                this.file = file;
            }

            public Builder tool(String v) {
                this.tool = v;
                return this;
            }

            public Builder key(String v) {
                this.key = v;
                return this;
            }

            public Builder status(String v) {
                this.status = v;
                return this;
            }

            public Builder sizeBytes(Long v) {
                this.sizeBytes = v;
                return this;
            }

            public Builder jdkHome(String v) {
                this.jdkHome = v;
                return this;
            }

            public Builder jdkVendor(String v) {
                this.jdkVendor = v;
                return this;
            }

            public Builder jdkVersion(String v) {
                this.jdkVersion = v;
                return this;
            }

            public Builder gc(String v) {
                this.gc = v;
                return this;
            }

            public Builder classpath(List<String> v) {
                this.classpath = v == null ? List.of() : List.copyOf(v);
                return this;
            }

            /** Split a platform classpath string into entries. */
            public Builder classpathString(String cp) {
                if (cp == null || cp.isBlank()) {
                    this.classpath = List.of();
                    return this;
                }
                String sep = java.io.File.pathSeparator;
                List<String> parts = new ArrayList<>();
                for (String p : cp.split(java.util.regex.Pattern.quote(sep), -1)) {
                    if (!p.isBlank()) parts.add(p);
                }
                this.classpath = List.copyOf(parts);
                return this;
            }

            public Builder jvmFlags(List<String> v) {
                this.jvmFlags = v == null ? List.of() : List.copyOf(v);
                return this;
            }

            public Builder jkVersion(String v) {
                this.jkVersion = v;
                return this;
            }

            public Builder engineJar(String v) {
                this.engineJar = v;
                return this;
            }

            public Builder engineJarSize(Long v) {
                this.engineJarSize = v;
                return this;
            }

            public Builder engineJarMtimeMs(Long v) {
                this.engineJarMtimeMs = v;
                return this;
            }

            public Builder created(String v) {
                this.created = v;
                return this;
            }

            public Builder lastUsed(String v) {
                this.lastUsed = v;
                return this;
            }

            public Entry build() {
                return new Entry(
                        file,
                        tool,
                        key,
                        status,
                        sizeBytes,
                        jdkHome,
                        jdkVendor,
                        jdkVersion,
                        gc,
                        classpath,
                        jvmFlags,
                        jkVersion,
                        engineJar,
                        engineJarSize,
                        engineJarMtimeMs,
                        created,
                        lastUsed);
            }
        }
    }

    /** ISO-8601 UTC timestamp for {@code created}/{@code last_used}. */
    public static String nowIso() {
        return ISO.format(Instant.now());
    }

    /**
     * Upsert {@code entry} by {@link Entry#file()}. Preserves an existing {@code created} when the
     * new entry omits it. Never throws.
     */
    public static void upsert(Path aotDir, Entry entry) {
        if (aotDir == null || entry == null) return;
        withLock(aotDir, () -> {
            Map<String, Entry> map = loadMap(aotDir);
            Entry prev = map.get(entry.file());
            Entry merged = entry;
            if (prev != null) {
                Entry.Builder b = entry.toBuilder();
                if (blank(entry.created()) && !blank(prev.created())) b.created(prev.created());
                if (blank(entry.tool()) && !blank(prev.tool())) b.tool(prev.tool());
                if (blank(entry.key()) && !blank(prev.key())) b.key(prev.key());
                if (blank(entry.jdkHome()) && !blank(prev.jdkHome())) b.jdkHome(prev.jdkHome());
                if (blank(entry.jdkVendor()) && !blank(prev.jdkVendor())) b.jdkVendor(prev.jdkVendor());
                if (blank(entry.jdkVersion()) && !blank(prev.jdkVersion())) b.jdkVersion(prev.jdkVersion());
                if (blank(entry.gc()) && !blank(prev.gc())) b.gc(prev.gc());
                if (entry.classpath().isEmpty() && !prev.classpath().isEmpty()) b.classpath(prev.classpath());
                if (entry.jvmFlags().isEmpty() && !prev.jvmFlags().isEmpty()) b.jvmFlags(prev.jvmFlags());
                if (blank(entry.jkVersion()) && !blank(prev.jkVersion())) b.jkVersion(prev.jkVersion());
                if (blank(entry.engineJar()) && !blank(prev.engineJar())) b.engineJar(prev.engineJar());
                if (entry.engineJarSize() == null && prev.engineJarSize() != null)
                    b.engineJarSize(prev.engineJarSize());
                if (entry.engineJarMtimeMs() == null && prev.engineJarMtimeMs() != null)
                    b.engineJarMtimeMs(prev.engineJarMtimeMs());
                merged = b.build();
            } else if (blank(entry.created())) {
                merged = entry.toBuilder().created(nowIso()).build();
            }
            map.put(merged.file(), merged);
            writeMap(aotDir, map);
        });
    }

    /** Remove entries whose {@code file} is in {@code fileNames}. Never throws. */
    public static void remove(Path aotDir, Iterable<String> fileNames) {
        if (aotDir == null || fileNames == null) return;
        withLock(aotDir, () -> {
            Map<String, Entry> map = loadMap(aotDir);
            boolean changed = false;
            for (String f : fileNames) {
                if (f != null && map.remove(f) != null) changed = true;
            }
            if (changed) writeMap(aotDir, map);
        });
    }

    public static void remove(Path aotDir, String fileName) {
        if (fileName == null) return;
        remove(aotDir, List.of(fileName));
    }

    /**
     * Drop entries whose on-disk file (and optional {@code .noaot} marker) no longer exist. A
     * {@code pending} row is the documented state of a train still running (its {@code .aot}
     * intentionally doesn't exist yet) — those stay. Never throws.
     */
    public static void reconcile(Path aotDir) {
        if (aotDir == null || !Files.isDirectory(aotDir)) return;
        withLock(aotDir, () -> {
            Map<String, Entry> map = loadMap(aotDir);
            List<String> gone = new ArrayList<>();
            for (Map.Entry<String, Entry> me : map.entrySet()) {
                if ("pending".equals(me.getValue().status())) continue;
                String file = me.getKey();
                Path p = aotDir.resolve(file);
                Path noaotSibling = aotDir.resolve(file + ".noaot");
                Path noaotAlt = stemNoaot(aotDir, file);
                if (!Files.exists(p) && !Files.exists(noaotSibling) && (noaotAlt == null || !Files.exists(noaotAlt))) {
                    gone.add(file);
                }
            }
            if (gone.isEmpty()) return;
            for (String g : gone) map.remove(g);
            writeMap(aotDir, map);
        });
    }

    /** Read all entries (empty if missing/corrupt). Never throws. */
    public static List<Entry> load(Path aotDir) {
        try {
            return new ArrayList<>(loadMap(aotDir).values());
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Merge {@code aot.toml} with on-disk {@code *.aot} / sticky {@code .noaot} markers so a list
     * command still works for caches trained before the manifest existed. Never throws; never
     * writes.
     */
    public static List<Entry> list(Path aotDir) {
        if (aotDir == null || !Files.isDirectory(aotDir)) return List.of();
        Map<String, Entry> map = new LinkedHashMap<>();
        try {
            map.putAll(loadMap(aotDir));
        } catch (RuntimeException ignored) {
            // start from empty
        }
        try (var stream = Files.list(aotDir)) {
            for (Path p : stream.toList()) {
                String name = p.getFileName().toString();
                if (name.equals(FILE_NAME)
                        || name.endsWith(".lock")
                        || name.contains(".tmp-")
                        || name.endsWith(".training")) continue;
                if (name.endsWith(".aot") && Files.isRegularFile(p)) {
                    Entry prev = map.get(name);
                    String status = usableSize(p) > 0 ? "ready" : "empty";
                    Entry.Builder b = (prev != null ? prev.toBuilder() : Entry.builder(name)).status(status);
                    if (prev == null || blank(prev.tool()) || blank(prev.key())) fillToolKey(b, name);
                    b.sizeBytes(usableSize(p));
                    if (prev == null || blank(prev.lastUsed())) {
                        try {
                            b.lastUsed(ISO.format(Files.getLastModifiedTime(p).toInstant()));
                        } catch (IOException ignored) {
                        }
                    }
                    map.put(name, b.build());
                } else if (name.endsWith(".aot.noaot") && Files.isRegularFile(p)) {
                    // worker sticky marker: <file>.aot.noaot
                    String primary = name.substring(0, name.length() - ".noaot".length());
                    if (!map.containsKey(primary)
                            || "pending".equals(map.get(primary).status())
                            || !Files.exists(aotDir.resolve(primary))) {
                        map.put(primary, noaotRow(map.get(primary), primary));
                    }
                } else if (name.endsWith(".noaot")
                        && !name.endsWith(".aot.noaot")
                        && Files.isRegularFile(p)
                        && name.startsWith("engine-")) {
                    // engine sticky: engine-<ver>-<key>.noaot
                    String stem = name.substring(0, name.length() - ".noaot".length());
                    String primary = stem.endsWith(".aot") ? stem : stem + ".aot";
                    if (!map.containsKey(primary) || !Files.exists(aotDir.resolve(primary))) {
                        map.put(primary, noaotRow(map.get(primary), primary));
                    }
                }
            }
        } catch (IOException ignored) {
            // best-effort list
        }
        List<Entry> out = new ArrayList<>(map.values());
        out.sort(Comparator.comparing(Entry::file, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /**
     * A {@code noaot} row that keeps whatever the manifest already recorded (JDK home/vendor,
     * GC, flags — the diagnostic detail explaining <em>which</em> setup failed) rather than
     * flattening to a bare file name.
     */
    private static Entry noaotRow(Entry prev, String primary) {
        Entry.Builder b = prev != null ? prev.toBuilder() : Entry.builder(primary);
        if (prev == null || blank(prev.tool()) || blank(prev.key())) fillToolKey(b, primary);
        return b.status("noaot").build();
    }

    private static long usableSize(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.size(p) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    // ---- internals --------------------------------------------------------------------------

    private static Path stemNoaot(Path aotDir, String file) {
        // engine uses engine-<ver>-<key>.noaot (strip .aot); workers use file.aot.noaot
        if (file.endsWith(".aot")) {
            return aotDir.resolve(file.substring(0, file.length() - ".aot".length()) + ".noaot");
        }
        return null;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** Per-directory JVM locks: {@link FileChannel#lock()} throws on same-JVM overlap. */
    private static final ConcurrentMap<Path, ReentrantLock> DIR_LOCKS = new ConcurrentHashMap<>();

    private static void withLock(Path aotDir, IoRunnable body) {
        try {
            Files.createDirectories(aotDir);
            Path lockPath = aotDir.resolve(FILE_NAME + ".lock").toAbsolutePath().normalize();
            // Threads first (a second lock() in the same JVM throws OverlappingFileLockException,
            // which would silently drop that update), then processes via the file lock.
            ReentrantLock jvmLock = DIR_LOCKS.computeIfAbsent(lockPath, k -> new ReentrantLock());
            jvmLock.lock();
            try (FileChannel ch = FileChannel.open(
                            lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
                    FileLock lock = ch.lock()) {
                body.run();
            } finally {
                jvmLock.unlock();
            }
            // The 0-byte .lock file intentionally stays on disk. Unlinking it while another
            // process still holds the flock lets a third process lock a fresh inode at the same
            // path — two writers inside the critical section at once.
        } catch (Exception e) {
            // AOT is an accelerator; a bad manifest must never break builds — but don't lose the
            // trail entirely when diagnosing why an update vanished.
            System.getLogger(AotManifest.class.getName()).log(System.Logger.Level.DEBUG, "aot.toml update skipped", e);
        }
    }

    @FunctionalInterface
    private interface IoRunnable {
        void run() throws IOException;
    }

    private static Map<String, Entry> loadMap(Path aotDir) {
        Path file = path(aotDir);
        if (!Files.isRegularFile(file)) return new LinkedHashMap<>();
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            return parse(text);
        } catch (IOException | RuntimeException e) {
            // Unreadable or corrupt (e.g. hand-edit gone wrong): start over — the next write
            // regenerates a valid file, which is the recovery the header comment promises.
            return new LinkedHashMap<>();
        }
    }

    /**
     * Minimal parser for the format we write (schema 1 [[cache]] tables), hardened for the
     * hand-edits the header invites: trailing {@code #} comments, inline multi-item arrays, and
     * fields placed before {@code file =} inside a table all parse; anything genuinely corrupt
     * makes {@link #loadMap} start over rather than wedging writes.
     */
    static Map<String, Entry> parse(String text) {
        Map<String, Entry> out = new LinkedHashMap<>();
        if (text == null || text.isBlank()) return out;
        String[] lines = text.split("\n", -1);
        Entry.Builder cur = null;
        boolean inTable = false;
        List<String[]> pendingScalars = new ArrayList<>();
        Map<String, List<String>> pendingArrays = new LinkedHashMap<>();
        String arrayField = null;
        List<String> arrayBuf = null;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.equals("[[cache]]")) {
                finishArray(cur, arrayField, arrayBuf);
                arrayField = null;
                arrayBuf = null;
                if (cur != null) {
                    Entry e = cur.build();
                    out.put(e.file(), e);
                }
                cur = null; // set when file= arrives
                inTable = true;
                pendingScalars.clear();
                pendingArrays.clear();
                continue;
            }
            // array continuation
            if (arrayBuf != null) {
                String v = stripTrailingComment(line);
                if (v.equals("]")) {
                    finishArray(cur, arrayField, arrayBuf);
                    if (cur == null && arrayField != null) {
                        pendingArrays.put(arrayField, new ArrayList<>(arrayBuf));
                    }
                    arrayField = null;
                    arrayBuf = null;
                } else {
                    v = stripComma(v);
                    if (v.startsWith("\"")) arrayBuf.add(unquote(v));
                }
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).strip();
            String val = stripTrailingComment(line.substring(eq + 1).strip());
            if (key.equals("file")) {
                cur = Entry.builder(unquote(val));
                // Replay any fields that appeared before file= in this table.
                for (String[] kv : pendingScalars) applyScalar(cur, kv[0], kv[1]);
                for (Map.Entry<String, List<String>> a : pendingArrays.entrySet()) {
                    applyArray(cur, a.getKey(), a.getValue());
                }
                pendingScalars.clear();
                pendingArrays.clear();
                continue;
            }
            if (cur == null && !inTable) continue; // top-level schema/updated — ignore
            if (val.equals("[")) {
                arrayField = key;
                arrayBuf = new ArrayList<>();
                continue;
            }
            if (val.startsWith("[") && val.endsWith("]")) {
                List<String> items = parseInlineArray(val.substring(1, val.length() - 1));
                if (cur != null) {
                    applyArray(cur, key, items);
                } else {
                    pendingArrays.put(key, items);
                }
                continue;
            }
            if (cur != null) {
                applyScalar(cur, key, val);
            } else {
                pendingScalars.add(new String[] {key, val});
            }
        }
        finishArray(cur, arrayField, arrayBuf);
        if (cur != null) {
            Entry e = cur.build();
            out.put(e.file(), e);
        }
        return out;
    }

    /** Items of an inline array body: every quoted string, escapes respected. */
    private static List<String> parseInlineArray(String inner) {
        List<String> items = new ArrayList<>();
        int i = 0;
        while (i < inner.length()) {
            if (inner.charAt(i) == '"') {
                int close = closingQuote(inner, i);
                if (close < 0) break;
                items.add(unquote(inner.substring(i, close + 1)));
                i = close + 1;
            } else {
                i++;
            }
        }
        return items;
    }

    /**
     * Drop a trailing {@code # comment}. For quoted values the string ends at its closing quote;
     * for bare values everything from the first {@code #} goes.
     */
    private static String stripTrailingComment(String val) {
        String t = val.strip();
        if (t.startsWith("\"")) {
            int close = closingQuote(t, 0);
            return close < 0 ? t : t.substring(0, close + 1);
        }
        int hash = t.indexOf('#');
        return hash < 0 ? t : t.substring(0, hash).strip();
    }

    /** Index of the quote closing the one at {@code open}, honoring backslash escapes; -1 if none. */
    private static int closingQuote(String s, int open) {
        for (int i = open + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    private static void finishArray(Entry.Builder cur, String field, List<String> buf) {
        if (cur != null && field != null && buf != null) applyArray(cur, field, buf);
    }

    private static void applyArray(Entry.Builder cur, String key, List<String> items) {
        switch (key) {
            case "classpath" -> cur.classpath(items);
            case "jvm_flags" -> cur.jvmFlags(items);
            default -> {
                /* ignore unknown */
            }
        }
    }

    private static void applyScalar(Entry.Builder cur, String key, String val) {
        String s = val.startsWith("\"") ? unquote(val) : val;
        switch (key) {
            case "tool" -> cur.tool(s);
            case "key" -> cur.key(s);
            case "status" -> cur.status(s);
            case "size_bytes" -> cur.sizeBytes(parseLong(s));
            case "jdk_home" -> cur.jdkHome(s);
            case "jdk_vendor" -> cur.jdkVendor(s);
            case "jdk_version" -> cur.jdkVersion(s);
            case "gc" -> cur.gc(s);
            case "jk_version" -> cur.jkVersion(s);
            case "engine_jar" -> cur.engineJar(s);
            case "engine_jar_size" -> cur.engineJarSize(parseLong(s));
            case "engine_jar_mtime_ms" -> cur.engineJarMtimeMs(parseLong(s));
            case "created" -> cur.created(s);
            case "last_used" -> cur.lastUsed(s);
            default -> {
                /* ignore */
            }
        }
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.digit(s.charAt(i), 16) < 0) return false;
        }
        return true;
    }

    private static String stripComma(String s) {
        String t = s.strip();
        if (t.endsWith(",")) t = t.substring(0, t.length() - 1).strip();
        return t;
    }

    private static String unquote(String s) {
        String t = s.strip();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            StringBuilder sb = new StringBuilder(t.length());
            for (int i = 1; i < t.length() - 1; i++) {
                char c = t.charAt(i);
                if (c == '\\' && i + 1 < t.length() - 1) {
                    char n = t.charAt(++i);
                    switch (n) {
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case 'u' -> {
                            // A hand-edited Windows path like "C:{backslash}upgrade" puts non-hex
                            // after the unicode escape; treat it as literal text, don't throw.
                            String hex = i + 4 < t.length() - 1 ? t.substring(i + 1, i + 5) : null;
                            if (hex != null && isHex(hex)) {
                                sb.append((char) Integer.parseInt(hex, 16));
                                i += 4;
                            } else {
                                sb.append('u');
                            }
                        }
                        default -> sb.append(n);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
        return t;
    }

    private static void writeMap(Path aotDir, Map<String, Entry> map) throws IOException {
        List<Entry> ordered = new ArrayList<>(map.values());
        ordered.sort(Comparator.comparing(Entry::file, String.CASE_INSENSITIVE_ORDER));
        StringBuilder sb = new StringBuilder();
        sb.append("# JumpKick AOT cache manifest — human index of opaque *.aot hashes in this directory.\n");
        sb.append("# Written automatically on train / use / sweep. Safe to delete (regenerated next train).\n");
        sb.append("schema = ").append(SCHEMA).append('\n');
        sb.append("updated = ").append(MinimalToml.quote(nowIso())).append("\n\n");
        for (Entry e : ordered) {
            sb.append("[[cache]]\n");
            sb.append("file = ").append(MinimalToml.quote(e.file())).append('\n');
            if (!blank(e.tool()))
                sb.append("tool = ").append(MinimalToml.quote(e.tool())).append('\n');
            if (!blank(e.key()))
                sb.append("key = ").append(MinimalToml.quote(e.key())).append('\n');
            if (!blank(e.status()))
                sb.append("status = ").append(MinimalToml.quote(e.status())).append('\n');
            if (e.sizeBytes() != null)
                sb.append("size_bytes = ").append(e.sizeBytes()).append('\n');
            if (!blank(e.created()))
                sb.append("created = ").append(MinimalToml.quote(e.created())).append('\n');
            if (!blank(e.lastUsed()))
                sb.append("last_used = ")
                        .append(MinimalToml.quote(e.lastUsed()))
                        .append('\n');
            if (!blank(e.jdkHome()))
                sb.append("jdk_home = ").append(MinimalToml.quote(e.jdkHome())).append('\n');
            if (!blank(e.jdkVendor()))
                sb.append("jdk_vendor = ")
                        .append(MinimalToml.quote(e.jdkVendor()))
                        .append('\n');
            if (!blank(e.jdkVersion()))
                sb.append("jdk_version = ")
                        .append(MinimalToml.quote(e.jdkVersion()))
                        .append('\n');
            if (!blank(e.gc()))
                sb.append("gc = ").append(MinimalToml.quote(e.gc())).append('\n');
            if (!blank(e.jkVersion()))
                sb.append("jk_version = ")
                        .append(MinimalToml.quote(e.jkVersion()))
                        .append('\n');
            if (!blank(e.engineJar()))
                sb.append("engine_jar = ")
                        .append(MinimalToml.quote(e.engineJar()))
                        .append('\n');
            if (e.engineJarSize() != null)
                sb.append("engine_jar_size = ").append(e.engineJarSize()).append('\n');
            if (e.engineJarMtimeMs() != null)
                sb.append("engine_jar_mtime_ms = ").append(e.engineJarMtimeMs()).append('\n');
            writeStringArray(sb, "classpath", e.classpath());
            writeStringArray(sb, "jvm_flags", e.jvmFlags());
            sb.append('\n');
        }
        AtomicWrites.replace(path(aotDir), sb.toString());
    }

    private static void writeStringArray(StringBuilder sb, String key, List<String> items) {
        if (items == null || items.isEmpty()) return;
        sb.append(key).append(" = [\n");
        for (String item : items) {
            sb.append("  ").append(MinimalToml.quote(item)).append(",\n");
        }
        sb.append("]\n");
    }

    /**
     * Derive tool + key from a cache file name ({@code tool-16hex.aot} or
     * {@code engine-version-16hex.aot}).
     */
    /**
     * Known worker tool tags (may contain hyphens). Used to split
     * {@code <tool>-<jk-version>-<16hex>.aot} for the human index.
     */
    private static final List<String> WORKER_TOOLS = List.of("java-compiler", "kotlinc", "groovy", "plugin");

    public static void fillToolKey(Entry.Builder b, String fileName) {
        if (fileName == null || !fileName.endsWith(".aot")) return;
        String stem = fileName.substring(0, fileName.length() - ".aot".length());
        if (stem.startsWith("engine-")) {
            // engine-<version>-<16hex>
            int lastDash = stem.lastIndexOf('-');
            if (lastDash > "engine-".length() && stem.length() - lastDash - 1 == 16) {
                String key = stem.substring(lastDash + 1);
                String rest = stem.substring("engine-".length(), lastDash);
                b.tool("engine").key(key).jkVersion(rest);
                return;
            }
        }
        // <tool>-[<jk-version>-]<16hex>
        if (stem.length() > 17 && stem.charAt(stem.length() - 17) == '-') {
            String key = stem.substring(stem.length() - 16);
            if (!key.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return;
            }
            String head = stem.substring(0, stem.length() - 17); // tool or tool-version
            for (String tool : WORKER_TOOLS) {
                if (head.equals(tool)) {
                    b.tool(tool).key(key);
                    return;
                }
                if (head.startsWith(tool + "-")) {
                    b.tool(tool).key(key).jkVersion(head.substring(tool.length() + 1));
                    return;
                }
            }
            // Unknown tool tag — treat whole head as tool (legacy / third-party).
            b.tool(head).key(key);
        }
    }

    /** Best-effort size of a cache file, or null. */
    public static Long sizeOf(Path cache) {
        try {
            if (cache != null && Files.isRegularFile(cache)) return Files.size(cache);
        } catch (IOException ignored) {
        }
        return null;
    }
}
