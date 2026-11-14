// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The text of one action-cache key record, {@code actions/keys/<actionKey>}: {@code TASK} and {@code
 * KEY} lines, then {@code INPUT <value> <name>}, {@code OUTPUT <sha256> <path>} and {@code EXEC
 * <path>} lines in name order, then {@code SAID <line>} lines in the order the action said them,
 * then {@code END <outputCount>}. A record without that last line, or whose count disagrees with its
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
            List<String> said) {}

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
        StringBuilder sb = new StringBuilder();
        sb.append("TASK ").append(taskId).append('\n');
        sb.append("KEY ").append(actionKey).append('\n');
        for (Map.Entry<String, String> e : new TreeMap<>(inputs).entrySet()) {
            sb.append("INPUT ")
                    .append(encodeValue(e.getValue()))
                    .append(' ')
                    .append(e.getKey())
                    .append('\n');
        }
        for (Map.Entry<String, String> e : new TreeMap<>(outputs).entrySet()) {
            sb.append("OUTPUT ")
                    .append(encodeValue(e.getValue()))
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
     * the source by its absolute path. No key hashes either, so the hit it serves is the same.
     */
    public static String renderShared(Parsed record) {
        Map<String, String> portable = new TreeMap<>();
        for (Map.Entry<String, String> e : record.inputs().entrySet()) {
            if (!namesMachinePath(e.getKey()) && !namesMachinePath(e.getValue()))
                portable.put(e.getKey(), e.getValue());
        }
        return render(record.taskId(), record.actionKey(), portable, record.outputs(), record.executables(), List.of());
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
                outputs.put(body.substring(sp + 1), decodeValue(body.substring(0, sp)));
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
        return new Parsed(taskId, actionKey, inputs, outputs, executables, List.copyOf(said));
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
