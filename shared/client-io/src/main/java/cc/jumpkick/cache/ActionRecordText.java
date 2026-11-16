// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The text of one action-cache key record, {@code actions/keys/<actionKey>}: {@code TASK} and {@code
 * KEY} lines, then what the record says about itself ({@link Meta}: {@code FORMAT}, {@code KIND},
 * {@code CREATED}, {@code PRODUCER}, {@code HOST}, {@code TOOLCHAIN}, {@code TARGET}, {@code WALL}), then {@code INPUT <value> <name>}, {@code OUTPUT
 * <sha256>/<bytes> <path>} and {@code EXEC <path>} lines in name order, then {@code SAID <line>}
 * lines in the order the action said them, then {@code END <outputCount>}. An output that is a
 * marker scalar rather than a blob carries no size. A record without that last line, or whose count disagrees with its
 * {@code OUTPUT} lines, was cut short and does not parse: every prefix of a record is otherwise a
 * well-formed record with fewer outputs. The engine's action cache and the workers that store their
 * own entries (the code graph's fragments) write and read it here, so the prune and the sweep see
 * one format.
 *
 * <p>A reader skips a line it does not know before {@code END}, so a kind of line added later
 * leaves an older reader reading the rest. A change of what a record holds is therefore no change of
 * what any key hashes.
 *
 * <p>A record is read as untrusted input, since it may come from a restored CI cache or another
 * machine: {@link #parse} refuses ({@link Refused}) one whose {@code KEY} is not the key it was
 * looked up under, whose {@code OUTPUT} or {@code EXEC} path is not a plain relative path ({@link
 * #isSafeRelative}), or whose {@code OUTPUT} value is neither a sha256 nor a marker scalar ({@link
 * #isOutputValue}). Every reader takes a refusal as a miss.
 */
public final class ActionRecordText {

    private ActionRecordText() {}

    /** The prefix of a record's last line, which counts its {@code OUTPUT} lines. */
    private static final String END = "END ";

    /**
     * How old a record's mtime may grow before a read refreshes it: the window a reused blob's is
     * refreshed in ({@link Cas#REUSE_REFRESH}), so a record in active use always looks younger than
     * the prune's grace, and another engine's prune never takes it mid-restore.
     */
    public static final Duration READ_REFRESH = Cas.REUSE_REFRESH;

    /**
     * Marks the key record at {@code record} as used now: its mtime is the last use the prune ranks
     * by, and a read changes nothing else. Writes only when the mtime is {@link #READ_REFRESH} or
     * more old, so a record hit on every build costs one stat a hit and at most one write per
     * {@code READ_REFRESH}, whichever engine or worker reads it. Best-effort: a read-only cache root, or a record a concurrent prune
     * just took, keeps its previous rank.
     */
    public static void markRead(Path record) {
        try {
            long now = Clock.SYSTEM.millis();
            if (now - Files.getLastModifiedTime(record).toMillis() < READ_REFRESH.toMillis()) return;
            Files.setLastModifiedTime(record, FileTime.fromMillis(now));
        } catch (IOException ignored) {
            // A ranking hint, never correctness.
        }
    }

    /**
     * One record as read: inputs and outputs by name, the outputs restored executable, and what the
     * action said as it ran ({@link #render(String, String, Map, Map, Set, List) said}).
     */
    public record Parsed(
            String taskId,
            String actionKey,
            Map<String, String> inputs,
            Map<String, String> outputs,
            Set<String> executables,
            List<String> said,
            Meta meta) {

        public Parsed(
                String taskId,
                String actionKey,
                Map<String, String> inputs,
                Map<String, String> outputs,
                Set<String> executables,
                List<String> said) {
            this(taskId, actionKey, inputs, outputs, executables, said, Meta.NONE);
        }
    }

    /** The record format {@link #render} writes; {@link #parse} refuses a newer one. */
    public static final int FORMAT = 1;

    /**
     * What a record says about itself beside its inputs and outputs: the format it is written in,
     * when and by which jk ({@code jk/<cache-key version>}) on which host ({@code <os>-<arch>}) it
     * was made, the toolchain that made it ({@code toolchain:<kind>:<identity>}) and the platform
     * its outputs are for ({@code <os>-<arch>[-<libc>]}), how long the step that made it worked
     * ({@code wallMillis}, {@code -1} when not known), and the byte size of each output that is a
     * blob, by output path. The host is where the outputs were made; the target is what they run
     * on, absent for outputs that run anywhere (bytecode, resources, a jar), so a reader may take a
     * record made on any host unless it names a target. Neither is part of the key. A record
     * written without a field reads it as null, zero or empty.
     */
    public record Meta(
            int format,
            @Nullable Kind kind,
            @Nullable Instant created,
            @Nullable String producer,
            @Nullable String host,
            @Nullable String toolchain,
            @Nullable String target,
            long wallMillis,
            Map<String, Long> sizes) {

        public static final Meta NONE = new Meta(0, null, null, null, null, null, null, -1, Map.of());

        public Meta {
            sizes = Map.copyOf(sizes);
        }

        /**
         * A {@code kind} record made now, to the second, by {@code producer} on {@code host}, for no
         * particular target, its blobs {@code sizes} bytes each.
         */
        public static Meta now(
                @Nullable Kind kind, String producer, String host, long wallMillis, Map<String, Long> sizes) {
            return new Meta(
                    FORMAT,
                    kind,
                    Instant.ofEpochSecond(Clock.SYSTEM.millis() / 1000),
                    producer,
                    host,
                    null,
                    null,
                    wallMillis,
                    sizes);
        }
    }

    /**
     * What a record is, which decides what a second write under its key means. jk's own cache
     * keeps the latest write of every kind; a store shared by several writers keeps {@link
     * #OUTPUTS}, {@link #VERDICT} and {@link #ALIAS} records write-once (a different second result
     * under one key is a reproducibility finding, not an update) and keeps {@link #TEST_MARKER} and
     * {@link #CLASS_LEDGER} records as observations, by writer.
     */
    public enum Kind {
        /** A step's output files, by digest. */
        OUTPUTS("outputs"),
        /** A check that passed and produced nothing: the record is the result. */
        VERDICT("verdict"),
        /** A suite's counts under its stamp, red or green. */
        TEST_MARKER("test-marker"),
        /** Per-class outcomes under a suite's stamp, or none when cleared. */
        CLASS_LEDGER("class-ledger"),
        /** Another key's outputs stored again under this key, for inputs read without what the build wrote. */
        ALIAS("alias");

        private final String word;

        Kind(String word) {
            this.word = word;
        }

        /** The word a {@code KIND} line spells. */
        public String word() {
            return word;
        }

        /** The kind {@code word} spells, or null for a word this jk does not know. */
        public static @Nullable Kind of(String word) {
            for (Kind k : values()) {
                if (k.word.equals(word)) return k;
            }
            return null;
        }
    }

    /**
     * Whether {@code record} may go to a store others read: only a result this jk would replay on
     * the machine that produced it. Outputs, aliases and verdicts are; a test marker only when it
     * is green; a class ledger only when it holds at least one class and every class passed. A red
     * marker, a ledger holding a failure or cleared to nothing, and a record whose kind is unknown
     * stay on the machine that wrote them. The one place this is decided.
     */
    public static boolean shareable(Parsed record) {
        Kind kind = record.meta().kind();
        if (kind == null) return false;
        return switch (kind) {
            case OUTPUTS, ALIAS ->
                !record.outputs().isEmpty()
                        && record.outputs().values().stream().allMatch(ActionRecordText::isSha256);
            case VERDICT -> record.outputs().isEmpty();
            case TEST_MARKER -> zeroCount(record.outputs().get("tests.failed"));
            case CLASS_LEDGER ->
                !record.outputs().isEmpty()
                        && record.outputs().values().stream().allMatch(ActionRecordText::greenClass);
        };
    }

    /** A count that reads as zero: a failure count no reader could take for a failure. */
    private static boolean zeroCount(@Nullable String count) {
        return count != null && isSize(count) && Long.parseLong(count) == 0;
    }

    /** A class-ledger value {@code tests,failed,skipped} whose failed count is zero. */
    private static boolean greenClass(String value) {
        String[] parts = value.split(",", -1);
        return parts.length == 3 && zeroCount(parts[1]) && isSize(parts[0]) && isSize(parts[2]);
    }

    /**
     * The bytes of every distinct blob {@code outputs} names, from {@code sizes} (output path to
     * bytes), without a stat: what a record costs to store or fetch. Zero for a record of markers.
     */
    public static long totalBytes(Map<String, String> outputs, Map<String, Long> sizes) {
        Map<String, Long> bySha = new TreeMap<>();
        for (Map.Entry<String, String> e : outputs.entrySet()) {
            Long size = sizes.get(e.getKey());
            if (size != null && isSha256(e.getValue())) bySha.put(e.getValue(), size);
        }
        long total = 0;
        for (long size : bySha.values()) total += size;
        return total;
    }

    public static String render(
            String taskId,
            String actionKey,
            Map<String, String> inputs,
            Map<String, String> outputs,
            Set<String> executables) {
        return render(taskId, actionKey, inputs, outputs, executables, List.of());
    }

    /**
     * As {@link #render(String, String, Map, Map, Set)}, with {@code said}: what the action told the
     * user as it ran, one line each, in order, for a hit to say again. A line carries no line break;
     * one that does is folded to a space, since the record is read a line at a time.
     */
    public static String render(
            String taskId,
            String actionKey,
            Map<String, String> inputs,
            Map<String, String> outputs,
            Set<String> executables,
            List<String> said) {
        return render(taskId, actionKey, inputs, outputs, executables, said, Meta.NONE);
    }

    /**
     * As {@link #render(String, String, Map, Map, Set, List)}, with what the record says about
     * itself ({@link Meta}). The {@code FORMAT} line is always {@link #FORMAT}.
     */
    public static String render(
            String taskId,
            String actionKey,
            Map<String, String> inputs,
            Map<String, String> outputs,
            Set<String> executables,
            List<String> said,
            Meta meta) {
        StringBuilder sb = new StringBuilder();
        sb.append("TASK ").append(taskId).append('\n');
        sb.append("KEY ").append(actionKey).append('\n');
        sb.append("FORMAT ").append(FORMAT).append('\n');
        Kind kind = meta.kind() != null ? meta.kind() : inferredKind(outputs);
        if (kind != null) sb.append("KIND ").append(kind.word()).append('\n');
        if (meta.created() != null) sb.append("CREATED ").append(meta.created()).append('\n');
        if (meta.producer() != null)
            sb.append("PRODUCER ").append(oneWord(meta.producer())).append('\n');
        if (meta.host() != null) sb.append("HOST ").append(oneWord(meta.host())).append('\n');
        if (meta.toolchain() != null)
            sb.append("TOOLCHAIN ").append(oneWord(meta.toolchain())).append('\n');
        if (meta.target() != null)
            sb.append("TARGET ").append(oneWord(meta.target())).append('\n');
        if (meta.wallMillis() >= 0) sb.append("WALL ").append(meta.wallMillis()).append('\n');
        for (Map.Entry<String, String> e : new TreeMap<>(inputs).entrySet()) {
            sb.append("INPUT ")
                    .append(encodeValue(e.getValue()))
                    .append(' ')
                    .append(e.getKey())
                    .append('\n');
        }
        for (Map.Entry<String, String> e : new TreeMap<>(outputs).entrySet()) {
            Long size = meta.sizes().get(e.getKey());
            String value = size != null && isSha256(e.getValue()) ? e.getValue() + "/" + size : e.getValue();
            sb.append("OUTPUT ")
                    .append(encodeValue(value))
                    .append(' ')
                    .append(e.getKey())
                    .append('\n');
        }
        for (String rel : new TreeSet<>(executables)) {
            sb.append("EXEC ").append(rel).append('\n');
        }
        for (String line : said) {
            sb.append("SAID ")
                    .append(line.replace('\r', ' ').replace('\n', ' '))
                    .append('\n');
        }
        sb.append(END).append(outputs.size()).append('\n');
        return sb.toString();
    }

    /**
     * The record as it may leave this machine for a shared cache: its {@code TASK}, {@code KEY},
     * {@code OUTPUT} and {@code EXEC} lines, and the {@code INPUT} lines that name nothing of the
     * machine (a module-relative path, a fact such as {@code jdk} or {@code options}). An input
     * named by an absolute path (a store jar, a JDK, a file declared by absolute path) or whose
     * value holds one stays home, and so does every {@code SAID} line: a compiler's warning names
     * the source by its absolute path. Environment values a key hashed leave as their names only
     * ({@link #withoutEnvValues}). No key hashes either, so the hit it serves is the same.
     */
    public static String renderShared(Parsed record) {
        if (!shareable(record)) {
            throw new Refused("a "
                    + (record.meta().kind() == null
                            ? "record of no kind"
                            : record.meta().kind().word())
                    + " record that this jk would not replay does not leave the machine");
        }
        Map<String, String> portable = new TreeMap<>();
        for (Map.Entry<String, String> e : record.inputs().entrySet()) {
            if (!namesMachinePath(e.getKey()) && !namesMachinePath(e.getValue()))
                portable.put(withoutEnvValues(e.getKey()), withoutEnvValues(e.getValue()));
        }
        return render(
                record.taskId(),
                record.actionKey(),
                portable,
                record.outputs(),
                record.executables(),
                List.of(),
                record.meta());
    }

    /**
     * The kind of a record written without one: a verdict when it names no output, outputs when
     * every output is a blob, else none, since markers are written with their kind.
     */
    private static @Nullable Kind inferredKind(Map<String, String> outputs) {
        if (outputs.isEmpty()) return Kind.VERDICT;
        for (String value : outputs.values()) {
            if (!isSha256(value)) return null;
        }
        return Kind.OUTPUTS;
    }

    /**
     * Environment tokens a key hashes ({@code env:NAME=<digest>}, {@code plugin-env:}, {@code
     * test-env:}, {@code inherited-env:<digest>}) with their values withheld: a digest of a short
     * secret can be brute-forced from a record that leaves the machine. The names stay, so a miss
     * can still say which variable moved; the key, which hashed the values here, is unchanged.
     */
    static String withoutEnvValues(String text) {
        if (!text.contains("env:")) return text;
        String named = ENV_TOKEN.matcher(text).replaceAll("$1:$2=-");
        return INHERITED_ENV.matcher(named).replaceAll("inherited-env:-");
    }

    private static final Pattern ENV_TOKEN = Pattern.compile("\\b((?:plugin-|test-)?env):([^=,;\\s]+)=[^,;\\s]*");

    private static final Pattern INHERITED_ENV = Pattern.compile("\\binherited-env:[^,;\\s]*");

    /** {@code text} as one space-free word, so a header line reads back whole. */
    private static String oneWord(String text) {
        return text.replace(' ', '_').replace('\r', '_').replace('\n', '_');
    }

    /**
     * Whether {@code text} spells a path of this machine: absolute once any {@code word:} prefixes
     * are stripped ({@code cp:/home/…}, {@code C:/…}), or holding the user's home anywhere.
     */
    static boolean namesMachinePath(String text) {
        String home = System.getProperty("user.home");
        if (home != null && home.length() > 1 && (text.contains(home) || text.contains(home.replace('\\', '/'))))
            return true;
        String rest = text;
        int colon;
        while ((colon = rest.indexOf(':')) > 0 && isPrefixWord(rest, colon)) rest = rest.substring(colon + 1);
        return rest.startsWith("/") || rest.startsWith("\\");
    }

    private static boolean isPrefixWord(String s, int end) {
        for (int i = 0; i < end; i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.')) return false;
        }
        return true;
    }

    /** A record that is whole but not safe to trust: the reason says which line is wrong. */
    public static final class Refused extends IllegalArgumentException {
        Refused(String reason) {
            super(reason);
        }
    }

    /**
     * The record {@code content} holds, stored under {@code expectedKey}.
     *
     * @throws Refused when its {@code KEY} is not {@code expectedKey} or a path or value is unsafe
     * @throws RuntimeException when it is cut short: no {@code TASK} or {@code KEY}, a line without
     *     its value, or no {@code END} line last that counts its outputs
     */
    public static Parsed parse(String content, String expectedKey) {
        @Nullable String taskId = null;
        @Nullable String actionKey = null;
        Map<String, String> inputs = new LinkedHashMap<>();
        Map<String, String> outputs = new LinkedHashMap<>();
        Set<String> executables = new LinkedHashSet<>();
        List<String> said = new ArrayList<>();
        Map<String, Long> sizes = new LinkedHashMap<>();
        int format = 0;
        @Nullable Kind kind = null;
        @Nullable Instant created = null;
        @Nullable String producer = null;
        @Nullable String host = null;
        @Nullable String toolchain = null;
        @Nullable String target = null;
        long wallMillis = -1;
        int declaredOutputs = -1;
        for (String line : content.split("\n")) {
            if (line.isBlank()) continue;
            if (declaredOutputs >= 0) throw new IllegalArgumentException("record continues past its END line");
            if (line.startsWith(END)) {
                declaredOutputs = Integer.parseInt(line.substring(END.length()).trim());
            } else if (line.startsWith("TASK ")) {
                taskId = line.substring("TASK ".length()).trim();
            } else if (line.startsWith("KEY ")) {
                actionKey = line.substring("KEY ".length()).trim();
            } else if (line.startsWith("INPUT ")) {
                String body = line.substring("INPUT ".length());
                int sp = body.indexOf(' ');
                inputs.put(body.substring(sp + 1), decodeValue(body.substring(0, sp)));
            } else if (line.startsWith("OUTPUT ")) {
                String body = line.substring("OUTPUT ".length());
                int sp = body.indexOf(' ');
                String path = body.substring(sp + 1);
                String value = decodeValue(body.substring(0, sp));
                int slash = value.indexOf('/');
                if (slash == 64 && isSha256(value.substring(0, 64)) && isSize(value.substring(65))) {
                    sizes.put(path, Long.parseLong(value.substring(65)));
                    value = value.substring(0, 64);
                }
                outputs.put(path, value);
            } else if (line.startsWith("FORMAT ")) {
                format = Integer.parseInt(line.substring("FORMAT ".length()).trim());
            } else if (line.startsWith("KIND ")) {
                kind = Kind.of(line.substring("KIND ".length()).trim());
            } else if (line.startsWith("CREATED ")) {
                created = Instant.parse(line.substring("CREATED ".length()).trim());
            } else if (line.startsWith("PRODUCER ")) {
                producer = line.substring("PRODUCER ".length()).trim();
            } else if (line.startsWith("WALL ")) {
                wallMillis = Long.parseLong(line.substring("WALL ".length()).trim());
            } else if (line.startsWith("HOST ")) {
                host = line.substring("HOST ".length()).trim();
            } else if (line.startsWith("TOOLCHAIN ")) {
                toolchain = line.substring("TOOLCHAIN ".length()).trim();
            } else if (line.startsWith("TARGET ")) {
                target = line.substring("TARGET ".length()).trim();
            } else if (line.startsWith("EXEC ")) {
                executables.add(line.substring("EXEC ".length()).trim());
            } else if (line.startsWith("SAID ")) {
                said.add(line.substring("SAID ".length()));
            }
        }
        if (declaredOutputs != outputs.size()) {
            throw new IllegalArgumentException(
                    "record cut short: END " + declaredOutputs + " for " + outputs.size() + " outputs");
        }
        Objects.requireNonNull(taskId, "taskId in record");
        Objects.requireNonNull(actionKey, "actionKey in record");
        if (!actionKey.equals(expectedKey)) {
            throw new Refused("record under " + expectedKey + " names key " + actionKey);
        }
        for (Map.Entry<String, String> e : outputs.entrySet()) {
            if (!isSafeRelative(e.getKey()))
                throw new Refused("OUTPUT path is not a plain relative path: " + e.getKey());
            if (!isOutputValue(e.getValue())) throw new Refused("OUTPUT value is not a digest: " + e.getValue());
        }
        for (String rel : executables) {
            if (!isSafeRelative(rel)) throw new Refused("EXEC path is not a plain relative path: " + rel);
        }
        if (format > FORMAT) throw new Refused("record format " + format + " is newer than this jk reads");
        return new Parsed(
                taskId,
                actionKey,
                inputs,
                outputs,
                executables,
                List.copyOf(said),
                new Meta(format, kind, created, producer, host, toolchain, target, wallMillis, sizes));
    }

    private static boolean isSize(String digits) {
        if (digits.isEmpty() || digits.length() > 18) return false;
        for (int i = 0; i < digits.length(); i++) {
            if (digits.charAt(i) < '0' || digits.charAt(i) > '9') return false;
        }
        return true;
    }

    /**
     * True when {@code rel} is a path a restore may resolve under its directory: non-empty, {@code
     * /}-separated, not absolute (no leading {@code /}, no drive letter), no backslash or control
     * character, and no empty, {@code .} or {@code ..} segment.
     */
    public static boolean isSafeRelative(@Nullable String rel) {
        if (rel == null || rel.isEmpty() || rel.charAt(0) == '/') return false;
        if (rel.length() >= 2 && rel.charAt(1) == ':' && Character.isLetter(rel.charAt(0))) return false;
        for (int i = 0; i < rel.length(); i++) {
            char c = rel.charAt(i);
            if (c == '\\' || Character.isISOControl(c)) return false;
        }
        for (String segment : rel.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
        }
        return true;
    }

    /** True for 64 hex digits: the shape a blob is keyed by in {@link Cas}. */
    public static boolean isSha256(@Nullable String value) {
        if (value == null || value.length() != 64) return false;
        for (int i = 0; i < 64; i++) {
            if (Character.digit(value.charAt(i), 16) < 0) return false;
        }
        return true;
    }

    /**
     * True for an {@code OUTPUT} value a record may hold: a blob's sha256, or a marker scalar (a
     * test count, a short status) that names no path: no {@code /}, backslash or control
     * character, and not {@code .} or {@code ..}. A restore lays down only sha256 values.
     */
    public static boolean isOutputValue(@Nullable String value) {
        if (value == null || value.isEmpty()) return false;
        if (isSha256(value)) return true;
        if (value.equals(".") || value.equals("..")) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '/' || c == '\\' || Character.isISOControl(c)) return false;
        }
        return true;
    }

    /**
     * A value is split from its name at the first space, so it carries no space of its own: {@code
     * %} and space are percent-encoded on the way out and decoded on the way back. A content sha, the
     * usual value, passes through untouched.
     */
    public static String encodeValue(String value) {
        if (value.indexOf(' ') < 0 && value.indexOf('%') < 0) return value;
        return value.replace("%", "%25").replace(" ", "%20");
    }

    public static String decodeValue(String value) {
        if (value.indexOf('%') < 0) return value;
        return value.replace("%20", " ").replace("%25", "%");
    }
}
