// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The text of one action-cache key record, {@code actions/keys/<actionKey>}: {@code TASK} and {@code
 * KEY} lines, then {@code INPUT <value> <name>}, {@code OUTPUT <sha256> <path>} and {@code EXEC
 * <path>} lines in name order. The engine's action cache and the workers that store their own
 * entries (the code graph's fragments) write and read it here, so the prune and the sweep see one
 * format.
 */
public final class ActionRecordText {

    private ActionRecordText() {}

    /** One record as read: inputs and outputs by name, the outputs restored executable. */
    public record Parsed(
            String taskId,
            String actionKey,
            Map<String, String> inputs,
            Map<String, String> outputs,
            Set<String> executables) {}

    public static String render(
            String taskId,
            String actionKey,
            Map<String, String> inputs,
            Map<String, String> outputs,
            Set<String> executables) {
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
            }
        }
        return new Parsed(
                Objects.requireNonNull(taskId, "taskId in record"),
                Objects.requireNonNull(actionKey, "actionKey in record"),
                inputs,
                outputs,
                executables);
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
