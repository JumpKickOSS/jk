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
 * <path>} lines in name order, then {@code SAID <line>} lines in the order the action said them.
 * The engine's action cache and the workers that store their own entries (the code graph's
 * fragments) write and read it here, so the prune and the sweep see one format.
 *
 * <p>A reader skips a line it does not know, so a kind of line added later leaves an older reader
 * reading the rest, and a record written before it reads as having none: {@code SAID} came after
 * the others, and a record without it is an action that said nothing. A change of what a record
 * holds is therefore no change of what any key hashes.
 */
public final class ActionRecordText {

    private ActionRecordText() {}

    /**
     * How old a record's mtime may grow before a read refreshes it: half the grace in which the
     * prune takes nothing ({@link Cas#MIN_AGE_FOR_SWEEP}), so a record in active use always looks
     * younger than the grace, and another engine's prune never takes it mid-restore.
     */
    public static final Duration READ_REFRESH = Cas.MIN_AGE_FOR_SWEEP.dividedBy(2);

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
        return sb.toString();
    }

    /**
     * The record {@code content} holds.
     *
     * @throws RuntimeException when it is cut short: no {@code TASK} or {@code KEY}, or a line
     *     without its value
     */
    public static Parsed parse(String content) {
        @Nullable String taskId = null;
        @Nullable String actionKey = null;
        Map<String, String> inputs = new LinkedHashMap<>();
        Map<String, String> outputs = new LinkedHashMap<>();
        Set<String> executables = new LinkedHashSet<>();
        List<String> said = new ArrayList<>();
        for (String line : content.split("\n")) {
            if (line.isBlank()) continue;
            if (line.startsWith("TASK ")) {
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
        return new Parsed(
                Objects.requireNonNull(taskId, "taskId in record"),
                Objects.requireNonNull(actionKey, "actionKey in record"),
                inputs,
                outputs,
                executables,
                List.copyOf(said));
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
